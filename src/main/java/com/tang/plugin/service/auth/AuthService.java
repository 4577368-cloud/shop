package com.tang.plugin.service.auth;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.JwtAuthProperties;
import com.tang.plugin.domain.entity.user.AppUser;
import com.tang.plugin.domain.entity.user.PasswordResetToken;
import com.tang.plugin.domain.entity.user.UserRefreshToken;
import com.tang.plugin.dto.auth.AuthDtos;
import com.tang.plugin.dto.auth.AuthDtos.ForgotPasswordResponse;
import com.tang.plugin.dto.auth.AuthDtos.RefreshResponse;
import com.tang.plugin.dto.auth.AuthDtos.ResetPasswordResponse;
import com.tang.plugin.dto.auth.AuthDtos.UserResponse;
import com.tang.plugin.repository.AppUserRepository;
import com.tang.plugin.repository.PasswordResetTokenRepository;
import com.tang.plugin.repository.UserRefreshTokenRepository;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * User auth business logic: register / login / logout / me / change-password / refresh.
 * Tokens are returned for the controller to set as httpOnly cookies.
 */
@Slf4j
@Service
public class AuthService {

    // Basic email format check (RFC 5322 simplified).
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    // Password: 8-128 chars, at least one letter + one digit.
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d!@#$%^&*()_+=\\-]{8,128}$");

    // Dummy bcrypt hash used when email not found — prevents timing attacks (always run bcrypt).
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** Reset token TTL: 30 minutes. */
    private static final long RESET_TOKEN_TTL_SECONDS = 1800L;

    @Resource
    private AppUserRepository userRepository;
    @Resource
    private UserRefreshTokenRepository refreshTokenRepository;
    @Resource
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Resource
    private PasswordService passwordService;
    @Resource
    private JwtService jwtService;
    @Resource
    private JwtAuthProperties authProperties;

    // ===== Register =====

    public AuthResult register(AuthDtos.RegisterRequest req, HttpServletRequest httpRequest) {
        validateEmail(req.email());
        validatePassword(req.password());
        if (StringUtils.isBlank(req.name()) || req.name().trim().length() > 128) {
            throw new CustomException("Name is required (max 128 chars)", 400, "INVALID_NAME");
        }
        String email = req.email().trim().toLowerCase();
        String name = req.name().trim();

        if (userRepository.findByEmail(email).isPresent()) {
            throw new CustomException("Email already registered", 409, "EMAIL_TAKEN");
        }

        AppUser user = new AppUser()
                .setEmail(email)
                .setPasswordHash(passwordService.hash(req.password()))
                .setName(name)
                .setStatus("active");
        try {
            userRepository.insert(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Concurrent registration with same email — unique constraint caught it.
            throw new CustomException("Email already registered", 409, "EMAIL_TAKEN");
        }
        log.info("User registered: id={} email={}", user.getId(), maskEmail(email));

        return issueTokens(user, httpRequest);
    }

    // ===== Login =====

    public AuthResult login(AuthDtos.LoginRequest req, HttpServletRequest httpRequest) {
        validateEmail(req.email());
        String email = req.email().trim().toLowerCase();

        // Always run bcrypt to prevent timing-based user enumeration.
        AppUser user = userRepository.findByEmail(email).orElse(null);
        boolean passwordOk;
        if (user != null) {
            passwordOk = passwordService.matches(req.password(), user.getPasswordHash());
        } else {
            passwordOk = passwordService.matches(req.password(), DUMMY_HASH);
        }

        if (user == null || !passwordOk) {
            throw new CustomException("Invalid email or password", 401, "INVALID_CREDENTIALS");
        }
        if (!"active".equals(user.getStatus())) {
            throw new CustomException("Account is " + user.getStatus(), 403, "ACCOUNT_INACTIVE");
        }

        userRepository.updateLastLogin(user.getId());
        log.info("User logged in: id={} email={}", user.getId(), maskEmail(email));
        return issueTokens(user, httpRequest);
    }

    // ===== Logout =====

    public void logout(String refreshToken) {
        if (StringUtils.isNotBlank(refreshToken)) {
            String hash = jwtService.hashRefreshToken(refreshToken);
            refreshTokenRepository.revokeByTokenHash(hash);
        }
    }

    // ===== Me =====

    public UserResponse me(Long userId) {
        AppUser user = requireUser(userId);
        return toUserResponse(user);
    }

    // ===== Change Password =====

    public void changePassword(Long userId, AuthDtos.ChangePasswordRequest req) {
        validatePassword(req.newPassword());
        AppUser user = requireUser(userId);

        if (!passwordService.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new CustomException("Current password is incorrect", 400, "WRONG_PASSWORD");
        }
        if (req.currentPassword().equals(req.newPassword())) {
            throw new CustomException("New password must differ from current", 400, "SAME_PASSWORD");
        }

        userRepository.updatePasswordHash(user.getId(), passwordService.hash(req.newPassword()));
        // Revoke all sessions after password change (force re-login on other devices).
        refreshTokenRepository.revokeAllByUserId(user.getId());
        log.info("Password changed for user id={}", userId);
    }

    // ===== Forgot / Reset password (P6) =====

    /**
     * 忘记密码：生成 reset token。
     *
     * <p>防枚举：无论 email 是否存在都返回 200。仅在 email 存在时生成 token + 记日志。
     *
     * <p>开发阶段：直接返回 token 供前端跳转 reset 页面。
     * P7 接入邮件后：改为发送邮件，response 中不返回 token（返回 expiresAt 即可）。
     */
    public ForgotPasswordResponse forgotPassword(AuthDtos.ForgotPasswordRequest req,
                                                  HttpServletRequest httpRequest) {
        if (req == null || StringUtils.isBlank(req.email())) {
            throw new CustomException("Email is required", 400, "INVALID_EMAIL");
        }
        validateEmail(req.email());
        String email = req.email().trim().toLowerCase();

        AppUser user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            // 防枚举：记日志但不暴露给前端
            log.info("Forgot-password for unknown email: {}", maskEmail(email));
            return new ForgotPasswordResponse(null, null);
        }

        String rawToken = jwtService.generateRawRefreshToken();  // 32 字节随机
        String tokenHash = jwtService.hashToken(rawToken);

        PasswordResetToken token = new PasswordResetToken()
                .setUserId(user.getId())
                .setTokenHash(tokenHash)
                .setExpiresAt(Instant.now().plusSeconds(RESET_TOKEN_TTL_SECONDS))
                .setIp(extractClientIp(httpRequest))
                .setUserAgent(truncate(httpRequest.getHeader("User-Agent"), 512));
        passwordResetTokenRepository.insert(token);

        log.info("Password reset token issued: userId={} email={} expiresIn={}min",
                user.getId(), maskEmail(email), RESET_TOKEN_TTL_SECONDS / 60);

        // 开发阶段：返回 raw token。生产阶段改为发邮件，此处返回 null。
        return new ForgotPasswordResponse(rawToken, token.getExpiresAt());
    }

    /**
     * 重置密码：验证 token + 改密 + 吊销所有会话。
     *
     * <p>幂等性：通过 markUsed 的乐观锁保证（used_at IS NULL 才更新）。
     * 同一 token 重复调用返回错误，不会重复改密。
     */
    public ResetPasswordResponse resetPassword(AuthDtos.ResetPasswordRequest req) {
        if (req == null || StringUtils.isBlank(req.resetToken()) || StringUtils.isBlank(req.newPassword())) {
            throw new CustomException("resetToken and newPassword are required", 400, "INVALID_REQUEST");
        }
        validatePassword(req.newPassword());

        String rawToken = req.resetToken().trim();
        String tokenHash = jwtService.hashToken(rawToken);

        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new CustomException("Invalid or expired reset token", 400, "INVALID_TOKEN"));

        if (token.getUsedAt() != null) {
            throw new CustomException("Reset token already used", 400, "TOKEN_ALREADY_USED");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new CustomException("Reset token expired", 400, "TOKEN_EXPIRED");
        }

        // 一次性标记（乐观锁，防并发重放）
        int marked = passwordResetTokenRepository.markUsed(tokenHash);
        if (marked == 0) {
            throw new CustomException("Reset token already used (concurrent)", 409, "TOKEN_ALREADY_USED");
        }

        AppUser user = requireUser(token.getUserId());
        userRepository.updatePasswordHash(user.getId(), passwordService.hash(req.newPassword()));
        // 吊销所有会话：强制所有设备重新登录
        refreshTokenRepository.revokeAllByUserId(user.getId());

        log.info("Password reset completed: userId={}", user.getId());
        return new ResetPasswordResponse(true);
    }

    // ===== Refresh =====

    public RefreshResponse refresh(String rawRefreshToken) {
        if (StringUtils.isBlank(rawRefreshToken)) {
            throw new CustomException("Missing refresh token", 401, "NO_REFRESH_TOKEN");
        }
        String hash = jwtService.hashRefreshToken(rawRefreshToken);
        UserRefreshToken token = refreshTokenRepository.findActiveByTokenHash(hash)
                .orElseThrow(() -> new CustomException("Invalid or expired refresh token", 401, "INVALID_REFRESH_TOKEN"));

        if (token.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.revokeByTokenHash(hash);
            throw new CustomException("Refresh token expired", 401, "EXPIRED_REFRESH_TOKEN");
        }

        AppUser user = requireUser(token.getUserId());
        String newAccessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        return new RefreshResponse(newAccessToken);
    }

    // ===== Helpers =====

    public AuthResult issueTokens(AppUser user, HttpServletRequest httpRequest) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        String rawRefreshToken = jwtService.generateRawRefreshToken();
        String refreshHash = jwtService.hashRefreshToken(rawRefreshToken);

        UserRefreshToken token = new UserRefreshToken()
                .setUserId(user.getId())
                .setTokenHash(refreshHash)
                .setExpiresAt(Instant.now().plusSeconds(
                        authProperties.getJwt().getRefreshTtlSeconds()))
                .setUserAgent(truncate(httpRequest.getHeader("User-Agent"), 512))
                .setIp(extractClientIp(httpRequest));
        refreshTokenRepository.insert(token);

        return new AuthResult(accessToken, rawRefreshToken, toUserResponse(user));
    }

    private AppUser requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", 404, "USER_NOT_FOUND"));
    }

    private UserResponse toUserResponse(AppUser user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getAvatarUrl(),
                user.getLocale(),
                user.getTimezone(),
                user.getCurrency(),
                user.getAiResponseLanguage(),
                user.getStatus()
        );
    }

    private void validateEmail(String email) {
        if (StringUtils.isBlank(email) || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new CustomException("Invalid email format", 400, "INVALID_EMAIL");
        }
    }

    private void validatePassword(String password) {
        if (StringUtils.isBlank(password) || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new CustomException("Password must be 8-128 chars with at least one letter and one digit",
                    400, "WEAK_PASSWORD");
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(forwarded)) {
            return truncate(forwarded.split(",")[0].trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Mask email for logging: ab***@example.com */
    private String maskEmail(String email) {
        if (email == null) return "null";
        int at = email.indexOf('@');
        if (at < 0) return "***";
        if (at <= 1) return "***" + email.substring(at);
        return email.charAt(0) + "***" + email.substring(at);
    }

    // ===== Result carrier (tokens + user) =====
    public record AuthResult(String accessToken, String refreshToken, UserResponse user) {}
}
