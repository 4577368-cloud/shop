package com.tang.plugin.service.auth;

import com.tang.plugin.config.JwtAuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

/**
 * JWT signing/verification (access token) + opaque refresh token generation.
 * Access token: JWT (HS256), contains userId + email, short-lived (7d).
 * Refresh token: 32-byte random, SHA-256 hashed in DB, long-lived (30d).
 */
@Service
public class JwtService {

    @Resource
    private JwtAuthProperties properties;

    private SecretKey accessKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    void init() {
        String secret = properties.getJwt().getSecret();
        if (StringUtils.isBlank(secret) || secret.length() < 32) {
            throw new IllegalStateException(
                    "TANG_PLUGIN_JWT_SECRET must be set and at least 32 characters. Current length: "
                            + (secret == null ? 0 : secret.length()));
        }
        this.accessKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // ===== Access Token (JWT) =====

    /** Generate a signed JWT access token. */
    public String generateAccessToken(Long userId, String email) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(properties.getJwt().getAccessTtlSeconds());
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(accessKey)
                .compact();
    }

    /** Parse + verify a JWT access token. Returns userId, or throws if invalid/expired. */
    public Long verifyAccessToken(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(accessKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.parseLong(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    // ===== Refresh Token (opaque random) =====

    /** Generate a raw refresh token (64 hex chars = 32 bytes). Client gets this; DB stores the hash. */
    public String generateRawRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** SHA-256 hash of an opaque token for DB storage. Used for refresh tokens, OAuth state, etc. */
    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** SHA-256 hash of the refresh token for DB storage. */
    public String hashRefreshToken(String rawToken) {
        return hashToken(rawToken);
    }
}
