package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Application user (login identity). Decoupled from Shopify store auth.
 * Table: app_user
 */
@Data
@Accessors(chain = true)
public class AppUser {
    private Long id;
    private String email;
    private String passwordHash;
    private String name;
    private String avatarUrl;
    private String locale;
    private String timezone;
    private String currency;
    private String aiResponseLanguage;
    private String status;       // active / suspended / deleted
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Integer delFlag;
}
