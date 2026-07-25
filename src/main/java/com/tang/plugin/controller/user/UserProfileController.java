package com.tang.plugin.controller.user;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.AppUser;
import com.tang.plugin.dto.user.UserProfileDtos;
import com.tang.plugin.dto.user.UserProfileDtos.ProfileResponse;
import com.tang.plugin.dto.user.UserProfileDtos.UpdateProfileRequest;
import com.tang.plugin.repository.AppUserRepository;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 用户个人资料接口（P6）。
 *
 * <p>路径前缀 {@code /api/plugin/user/} 受 JWT 保护（JwtAuthFilter）。
 *
 * <ul>
 *   <li>GET /api/plugin/user/profile — 读取当前用户资料</li>
 *   <li>PUT /api/plugin/user/profile — 更新资料（email / status 不可改）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/user/profile")
public class UserProfileController {

    /** 允许的 locale 值（与 i18n 配置一致）。 */
    private static final Set<String> ALLOWED_LOCALES = Set.of("zh", "en", "fr", "es");
    /** 允许的 currency 值。 */
    private static final Set<String> ALLOWED_CURRENCIES = Set.of("CNY", "USD", "EUR");
    /** 允许的 AI 响应语言。 */
    private static final Set<String> ALLOWED_AI_LANGS = Set.of("zh", "en", "fr", "es");

    @Resource
    private AppUserRepository userRepository;

    @GetMapping
    public ResponseEntity<ProfileResponse> getProfile(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", 404, "USER_NOT_FOUND"));
        return ResponseEntity.ok(toProfileResponse(user));
    }

    @PutMapping
    public ResponseEntity<ProfileResponse> updateProfile(
            HttpServletRequest httpRequest,
            @RequestBody UpdateProfileRequest req) {
        Long userId = currentUserId(httpRequest);
        if (req == null) {
            throw new CustomException("Request body is required", 400, "INVALID_REQUEST");
        }

        // 字段校验（仅对非空字段校验，空字段表示不更新）
        if (StringUtils.isNotBlank(req.name())) {
            String name = req.name().trim();
            if (name.isEmpty() || name.length() > 128) {
                throw new CustomException("Name must be 1-128 chars", 400, "INVALID_NAME");
            }
        }
        if (StringUtils.isNotBlank(req.avatarUrl()) && req.avatarUrl().length() > 512) {
            throw new CustomException("avatarUrl too long (max 512)", 400, "INVALID_AVATAR");
        }
        if (StringUtils.isNotBlank(req.locale()) && !ALLOWED_LOCALES.contains(req.locale())) {
            throw new CustomException("locale must be one of " + ALLOWED_LOCALES, 400, "INVALID_LOCALE");
        }
        if (StringUtils.isNotBlank(req.currency()) && !ALLOWED_CURRENCIES.contains(req.currency())) {
            throw new CustomException("currency must be one of " + ALLOWED_CURRENCIES, 400, "INVALID_CURRENCY");
        }
        if (StringUtils.isNotBlank(req.aiResponseLanguage())
                && !ALLOWED_AI_LANGS.contains(req.aiResponseLanguage())) {
            throw new CustomException("aiResponseLanguage must be one of " + ALLOWED_AI_LANGS,
                    400, "INVALID_AI_LANG");
        }

        // 将空字符串转为 null，让 COALESCE 跳过
        String name = StringUtils.isBlank(req.name()) ? null : req.name().trim();
        String avatarUrl = StringUtils.isBlank(req.avatarUrl()) ? null : req.avatarUrl().trim();
        String locale = StringUtils.isBlank(req.locale()) ? null : req.locale();
        String timezone = StringUtils.isBlank(req.timezone()) ? null : req.timezone();
        String currency = StringUtils.isBlank(req.currency()) ? null : req.currency();
        String aiLang = StringUtils.isBlank(req.aiResponseLanguage()) ? null : req.aiResponseLanguage();

        userRepository.updateProfile(userId, name, avatarUrl, locale, timezone, currency, aiLang);

        AppUser updated = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found after update", 500, "USER_NOT_FOUND"));
        log.info("Profile updated: userId={}", userId);
        return ResponseEntity.ok(toProfileResponse(updated));
    }

    private ProfileResponse toProfileResponse(AppUser user) {
        return new ProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getAvatarUrl(),
                user.getLocale(),
                user.getTimezone(),
                user.getCurrency(),
                user.getAiResponseLanguage(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getLastLoginAt()
        );
    }

    private Long currentUserId(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (userId == null) {
            throw new IllegalStateException("userId not found in request attributes");
        }
        return userId;
    }
}
