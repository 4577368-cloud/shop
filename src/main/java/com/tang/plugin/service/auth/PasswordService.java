package com.tang.plugin.service.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password hashing service. Uses BCrypt with cost=12 (≈400ms/hash on modern hardware).
 * Cost is higher than Spring's default (10) to slow down offline brute-force attacks;
 * login latency stays acceptable because successful logins are infrequent per user.
 *
 * <p>Never log plaintext passwords.
 *
 * <p>Gradual upgrade: when a user logs in with a hash whose cost is below 12, the auth
 * flow re-hashes the password with cost=12 and persists it (see AuthService.login).
 */
@Service
public class PasswordService {

    /** Target BCrypt cost factor. */
    public static final int TARGET_COST = 12;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(TARGET_COST);

    /** Hash a plaintext password. Returns BCrypt hash string. */
    public String hash(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new IllegalArgumentException("Password must not be empty");
        }
        return encoder.encode(plainPassword);
    }

    /** Verify a plaintext password against a BCrypt hash. */
    public boolean matches(String plainPassword, String hashedPassword) {
        if (plainPassword == null || plainPassword.isEmpty() || hashedPassword == null || hashedPassword.isEmpty()) {
            return false;
        }
        return encoder.matches(plainPassword, hashedPassword);
    }

    /**
     * Returns true if a stored hash uses a lower cost factor than {@link #TARGET_COST}.
     * Used to trigger gradual re-hashing on login.
     */
    public boolean needsUpgrade(String hashedPassword) {
        if (hashedPassword == null || hashedPassword.length() < 7) return false;
        // BCrypt hash format: $2a$<cost>$<salt+hash>  — cost is at chars 4..5
        try {
            int cost = Integer.parseInt(hashedPassword.substring(4, 6));
            return cost < TARGET_COST;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
