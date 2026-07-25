package com.tang.plugin.controller.user;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.UserRefreshToken;
import com.tang.plugin.dto.user.UserSessionDtos;
import com.tang.plugin.dto.user.UserSessionDtos.SessionItem;
import com.tang.plugin.dto.user.UserSessionDtos.SessionListResponse;
import com.tang.plugin.repository.UserRefreshTokenRepository;
import com.tang.plugin.service.auth.CookieHelper;
import com.tang.plugin.service.auth.JwtService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户会话管理接口（P6 security page）。
 *
 * <p>路径前缀 {@code /api/plugin/user/} 受 JWT 保护。
 *
 * <ul>
 *   <li>GET /api/plugin/user/sessions — 列出当前用户所有活跃会话</li>
 *   <li>DELETE /api/plugin/user/sessions/{id} — 远程登出某会话</li>
 * </ul>
 *
 * <p>当前会话识别：通过 cookie 中的 refresh token hash 匹配，标记 current=true。
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/user/sessions")
public class UserSessionController {

    @Resource
    private UserRefreshTokenRepository refreshTokenRepository;
    @Resource
    private JwtService jwtService;

    @GetMapping
    public ResponseEntity<SessionListResponse> listSessions(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        String currentTokenHash = currentRefreshTokenHash(httpRequest);

        List<UserRefreshToken> tokens = refreshTokenRepository.listActiveByUserId(userId);
        List<SessionItem> items = tokens.stream()
                .map(t -> new SessionItem(
                        t.getId(),
                        t.getUserAgent(),
                        t.getIp(),
                        t.getCreatedAt(),
                        t.getExpiresAt(),
                        t.getExpiresAt() != null && t.getExpiresAt().isBefore(Instant.now()),
                        t.getTokenHash().equals(currentTokenHash)
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(new SessionListResponse(items, items.size()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<UserSessionDtos.RevokeSessionResponse> revokeSession(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        String currentTokenHash = currentRefreshTokenHash(httpRequest);

        // 先查目标 token，判断是否是当前会话
        List<UserRefreshToken> tokens = refreshTokenRepository.listActiveByUserId(userId);
        UserRefreshToken target = tokens.stream()
                .filter(t -> t.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new CustomException("Session not found", 404, "SESSION_NOT_FOUND"));

        boolean isCurrent = target.getTokenHash().equals(currentTokenHash);
        int revoked = refreshTokenRepository.revokeByIdAndUser(id, userId);
        if (revoked == 0) {
            // 可能已被其他请求撤销
            return ResponseEntity.ok(new UserSessionDtos.RevokeSessionResponse(id, false));
        }
        log.info("Session revoked: userId={} sessionId={} isCurrent={}", userId, id, isCurrent);
        return ResponseEntity.ok(new UserSessionDtos.RevokeSessionResponse(id, true));
    }

    private Long currentUserId(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (userId == null) {
            throw new IllegalStateException("userId not found in request attributes");
        }
        return userId;
    }

    /** 读 cookie 中的 refresh token 并 hash，用于识别当前会话。 */
    private String currentRefreshTokenHash(HttpServletRequest httpRequest) {
        Cookie[] cookies = httpRequest.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (CookieHelper.REFRESH_COOKIE.equals(c.getName())) {
                String raw = c.getValue();
                if (raw != null && !raw.isBlank()) {
                    return jwtService.hashRefreshToken(raw);
                }
            }
        }
        return null;
    }
}
