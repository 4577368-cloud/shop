package com.tang.plugin.controller.auth;

import com.tang.plugin.dto.auth.AuthDtos;
import com.tang.plugin.dto.auth.AuthDtos.AuthResponse;
import com.tang.plugin.dto.auth.AuthDtos.RefreshResponse;
import com.tang.plugin.service.auth.AuthService;
import com.tang.plugin.service.auth.CookieHelper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * User auth endpoints: register / login / logout / refresh / me / change-password.
 * JWT access token + opaque refresh token are set as httpOnly cookies.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/auth")
public class AuthController {

    @Resource
    private AuthService authService;
    @Resource
    private CookieHelper cookieHelper;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthDtos.RegisterRequest req,
                                                  HttpServletRequest httpRequest,
                                                  HttpServletResponse httpResponse) {
        AuthService.AuthResult result = authService.register(req, httpRequest);
        setAuthCookies(httpResponse, result.accessToken(), result.refreshToken());
        return ResponseEntity.ok(new AuthResponse(result.user()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthDtos.LoginRequest req,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        AuthService.AuthResult result = authService.login(req, httpRequest);
        setAuthCookies(httpResponse, result.accessToken(), result.refreshToken());
        return ResponseEntity.ok(new AuthResponse(result.user()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest,
                                        HttpServletResponse httpResponse) {
        String refreshToken = readCookie(httpRequest, CookieHelper.REFRESH_COOKIE);
        authService.logout(refreshToken);
        httpResponse.addHeader("Set-Cookie", cookieHelper.clearAccessCookie().toString());
        httpResponse.addHeader("Set-Cookie", cookieHelper.clearRefreshCookie().toString());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@RequestBody(required = false) AuthDtos.RefreshRequest body,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) {
        // Refresh token comes from cookie (preferred) or request body (fallback for non-browser clients).
        String rawRefreshToken = readCookie(httpRequest, CookieHelper.REFRESH_COOKIE);
        if (rawRefreshToken == null && body != null) {
            rawRefreshToken = body.refreshToken();
        }
        RefreshResponse resp = authService.refresh(rawRefreshToken, httpRequest);
        // Rotate access + refresh cookies (refresh token rotation: H-1).
        httpResponse.addHeader("Set-Cookie",
                cookieHelper.buildAccessCookie(resp.accessToken()).toString());
        httpResponse.addHeader("Set-Cookie",
                cookieHelper.buildRefreshCookie(resp.refreshToken()).toString());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserResponse> me(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return ResponseEntity.ok(authService.me(userId));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@RequestBody AuthDtos.ChangePasswordRequest req,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        authService.changePassword(userId, req);
        // After password change, all refresh tokens are revoked. Clear cookies.
        httpResponse.addHeader("Set-Cookie", cookieHelper.clearAccessCookie().toString());
        httpResponse.addHeader("Set-Cookie", cookieHelper.clearRefreshCookie().toString());
        return ResponseEntity.noContent().build();
    }

    /**
     * 忘记密码（公开接口）。
     * 无论 email 是否存在都返回 200（防枚举）。
     * 开发阶段：返回 resetToken 供前端跳转 reset 页面。
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<AuthDtos.ForgotPasswordResponse> forgotPassword(
            @RequestBody AuthDtos.ForgotPasswordRequest req,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.forgotPassword(req, httpRequest));
    }

    /**
     * 重置密码（公开接口）。
     * 验证 resetToken + 改密 + 吊销所有会话。
     * 成功后用户需重新登录。
     */
    @PostMapping("/reset-password")
    public ResponseEntity<AuthDtos.ResetPasswordResponse> resetPassword(
            @RequestBody AuthDtos.ResetPasswordRequest req) {
        return ResponseEntity.ok(authService.resetPassword(req));
    }

    // ===== Helpers =====

    private void setAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addHeader("Set-Cookie", cookieHelper.buildAccessCookie(accessToken).toString());
        response.addHeader("Set-Cookie", cookieHelper.buildRefreshCookie(refreshToken).toString());
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
