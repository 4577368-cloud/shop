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

    private static final String ISSUER = "tang-source-plugin";
    private static final String AUDIENCE = "tangbuy-frontend";

    /** Generate a signed JWT access token (standalone login — no shop claim). */
    public String generateAccessToken(Long userId, String email) {
        return generateAccessToken(userId, email, null, null);
    }

    /**
     * Generate a signed JWT access token.
     * When {@code shopName}/{@code shopDomain} are set (embedded session exchange), controllers
     * may prefer shop identity from the token over client-supplied query params.
     */
    public String generateAccessToken(Long userId, String email, String shopName, String shopDomain) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(properties.getJwt().getAccessTtlSeconds());
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim("typ", "access")
                .id(java.util.UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp));
        if (StringUtils.isNotBlank(shopName)) {
            builder.claim("shopName", shopName.trim());
        }
        if (StringUtils.isNotBlank(shopDomain)) {
            builder.claim("shopDomain", shopDomain.trim().toLowerCase());
        }
        return builder.signWith(accessKey).compact();
    }

    /** Parse + verify a JWT access token. Returns userId, or returns null if invalid/expired. */
    public Long verifyAccessToken(String token) {
        AccessTokenClaims claims = parseAccessToken(token);
        return claims == null ? null : claims.userId();
    }

    /**
     * Parse access token into structured claims (user + optional shop).
     * Returns null when invalid/expired.
     */
    public AccessTokenClaims parseAccessToken(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(accessKey)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String typ = claims.get("typ", String.class);
            if (!"access".equals(typ)) {
                return null;
            }
            Long userId = Long.parseLong(claims.getSubject());
            String shopName = claims.get("shopName", String.class);
            String shopDomain = claims.get("shopDomain", String.class);
            return new AccessTokenClaims(userId, shopName, shopDomain);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public record AccessTokenClaims(Long userId, String shopName, String shopDomain) {}

    public String generatePluginToken(Long userId, String userName, String pluginType,
                                      String shopName, String shopId, long ttlSeconds) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim("typ", "plugin")
                .claim("userName", userName)
                .claim("pluginType", pluginType)
                .claim("shopName", shopName)
                .claim("shopId", shopId)
                .id(java.util.UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(accessKey)
                .compact();
    }

    public PluginTokenClaims parsePluginToken(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(accessKey)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!"plugin".equals(claims.get("typ", String.class))) {
                return null;
            }
            return new PluginTokenClaims(
                    Long.parseLong(claims.getSubject()),
                    claims.get("userName", String.class),
                    claims.get("pluginType", String.class),
                    claims.get("shopName", String.class),
                    claims.get("shopId", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public record PluginTokenClaims(Long userId, String userName, String pluginType,
                                    String shopName, String shopId) {}

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
