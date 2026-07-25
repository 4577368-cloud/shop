package com.tang.plugin.service.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password hashing service. Uses BCrypt (cost=10 default from spring-security-crypto).
 * Never log plaintext passwords.
 */
@Service
public class PasswordService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

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
}
