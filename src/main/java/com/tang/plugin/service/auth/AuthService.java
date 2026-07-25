package com.tang.plugin.service.auth;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.JwtAuthProperties;
import com.tang.plugin.domain.entity.user.AppUser;
import com.tang.plugin.domain.entity.user.UserRefreshToken;
import com.tang.plugin.dto.auth.AuthDtos;
import com.tang.plugin.dto.auth.AuthDtos.RefreshResponse;
import com.tang.plugin.dto.auth.AuthDtos.UserResponse;
import com.tang.plugin.repository.AppUserRepository;
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

    @Resource
    private AppUserRepository userRepository;
    @Resource
    private UserRefreshTokenRepository refreshTokenRepository;
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
