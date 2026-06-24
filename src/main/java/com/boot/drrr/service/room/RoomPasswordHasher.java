package com.boot.drrr.service.room;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class RoomPasswordHasher {
    public String hashNullable(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return null;
        }
        return hash(rawPassword.trim());
    }

    public boolean matches(String rawPassword, String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            return true;
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        return passwordHash.equals(hash(rawPassword.trim()));
    }

    private String hash(String rawPassword) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
