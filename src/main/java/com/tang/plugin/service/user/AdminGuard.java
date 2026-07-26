package com.tang.plugin.service.user;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.AppUser;
import com.tang.plugin.repository.AppUserRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Minimal admin authorization guard. No RBAC yet — admin list is configured via env.
 * Rejects with 403 when the calling user is not in the allow-list.
 *
 * <p>Used to protect test-only endpoints like {@code /billing/recharge} and
 * {@code /billing/credits/grant} that must not be reachable by regular users.
 */
@Slf4j
@Component
public class AdminGuard {

    private final Set<String> adminEmails;

    @Resource
    private AppUserRepository appUserRepository;

    public AdminGuard(@Value("${tang.plugin.security.admin-emails:}") String rawEmails) {
        if (rawEmails == null || rawEmails.isBlank()) {
            this.adminEmails = Collections.emptySet();
        } else {
            this.adminEmails = Arrays.stream(rawEmails.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * Assert the user identified by {@code userId} is an admin.
     *
     * @throws CustomException 403 FORBIDDEN if not admin or user not found.
     */
    public void assertAdmin(Long userId) {
        if (userId == null) {
            throw new CustomException("Authentication required", 401, "UNAUTHENTICATED");
        }
        if (adminEmails.isEmpty()) {
            // When no admins are configured, fail closed for safety.
            log.warn("AdminGuard rejecting request: no admin emails configured");
            throw new CustomException("Admin access not configured", 403, "FORBIDDEN");
        }
        AppUser user = appUserRepository.findById(userId).orElse(null);
        if (user == null || user.getEmail() == null) {
            throw new CustomException("User not found", 403, "FORBIDDEN");
        }
        String email = user.getEmail().trim().toLowerCase();
        if (!adminEmails.contains(email)) {
            log.warn("AdminGuard rejecting request: userId={} email={} not in admin list", userId, mask(email));
            throw new CustomException("Admin access required", 403, "FORBIDDEN");
        }
    }

    private static String mask(String email) {
        if (email == null || email.length() < 6) return "***";
        int at = email.indexOf('@');
        if (at < 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return "***" + domain;
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }
}
