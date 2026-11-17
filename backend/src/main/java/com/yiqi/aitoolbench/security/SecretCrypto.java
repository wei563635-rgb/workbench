package com.yiqi.aitoolbench.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretCrypto {
    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretCrypto(
            @Value("${app.ai.gateway-mode:mock}") String gatewayMode,
            @Value("${app.ai.master-key:}") String encodedMasterKey) {
        if (encodedMasterKey == null || encodedMasterKey.isBlank()) {
            if ("http".equalsIgnoreCase(gatewayMode)) {
                throw new IllegalStateException("HTTP AI 网关模式必须配置 256 位主密钥");
            }
            this.masterKey = null;
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedMasterKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("AI 凭证主密钥必须是 Base64 编码的 256 位密钥", ex);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("AI 凭证主密钥必须是 256 位");
        }
        this.masterKey = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "";
        }
        requireMasterKey();
        var iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            var encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("供应商凭证加密失败", ex);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        if (!encrypted.startsWith(PREFIX)) {
            throw new IllegalArgumentException("不支持的供应商凭证密文格式");
        }
        requireMasterKey();
        try {
            var payload = Base64.getUrlDecoder().decode(encrypted.substring(PREFIX.length()));
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("供应商凭证密文无效");
            }
            var iv = new byte[IV_LENGTH];
            var ciphertext = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            System.arraycopy(payload, IV_LENGTH, ciphertext, 0, ciphertext.length);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("供应商凭证解密失败", ex);
        }
    }

    private void requireMasterKey() {
        if (masterKey == null) {
            throw new IllegalStateException("未配置 AI 凭证主密钥");
        }
    }
}
