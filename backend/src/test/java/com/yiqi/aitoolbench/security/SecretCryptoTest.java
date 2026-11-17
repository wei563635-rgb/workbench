package com.yiqi.aitoolbench.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCryptoTest {
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithRandomAesGcmIvAndDecrypts() {
        var crypto = new SecretCrypto("http", TEST_KEY);

        var first = crypto.encrypt("supplier-secret");
        var second = crypto.encrypt("supplier-secret");

        assertThat(first).startsWith("enc:v1:").doesNotContain("supplier-secret");
        assertThat(second).isNotEqualTo(first);
        assertThat(crypto.decrypt(first)).isEqualTo("supplier-secret");
    }

    @Test
    void rejectsMissingMasterKeyInHttpMode() {
        assertThatThrownBy(() -> new SecretCrypto("http", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("主密钥");
    }

    @Test
    void rejectsInvalidMasterKeyLength() {
        var shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SecretCrypto("http", shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256");
    }
}
