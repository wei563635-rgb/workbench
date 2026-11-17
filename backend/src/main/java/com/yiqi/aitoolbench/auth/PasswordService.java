package com.yiqi.aitoolbench.auth;

import java.security.SecureRandom;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {
    private static final String TEMP_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) {
            return false;
        }
        if (storedPassword.startsWith("$2")) {
            return encoder.matches(rawPassword, storedPassword);
        }
        return rawPassword.equals(storedPassword);
    }

    public boolean needsRehash(String storedPassword) {
        return storedPassword == null || !storedPassword.startsWith("$2");
    }

    public void validateNewPassword(String account, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("新密码至少 8 位");
        }
        if (!newPassword.matches(".*[A-Za-z].*") || !newPassword.matches(".*\\d.*")) {
            throw new IllegalArgumentException("新密码必须包含字母和数字");
        }
        if (newPassword.equals(account)) {
            throw new IllegalArgumentException("新密码不能与账号相同");
        }
        if (newPassword.equals(oldPassword)) {
            throw new IllegalArgumentException("新密码不能与旧密码相同");
        }
    }

    public String generateTemporaryPassword() {
        var value = new StringBuilder("Aa1");
        for (var i = 0; i < 9; i++) {
            value.append(TEMP_CHARS.charAt(random.nextInt(TEMP_CHARS.length())));
        }
        return value.toString();
    }
}
