package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;

import com.yiqi.aitoolbench.ai.AiModelConfig;
import com.yiqi.aitoolbench.security.SecretCrypto;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AiModelConfigRepositoryTest {
    private JdbcTemplate jdbc;
    private SecretCrypto crypto;
    private WorkbenchRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:model-config-" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        crypto = new SecretCrypto("http", Base64.getEncoder().encodeToString(new byte[32]));
        repository = new WorkbenchRepository(jdbc, crypto);
    }

    @Test
    void persistsProviderOptionsAndEncryptedCredential() {
        var encrypted = crypto.encrypt("supplier-secret");
        repository.saveModelConfig(new AiModelConfig(
                "coze-copywriting", "Coze 文案", "coze", "text", "coze-copywriting",
                "https://example.invalid", encrypted, Map.of("botId", "bot-from-options"),
                60, 1, true));

        var loaded = repository.loadModelConfigs().get("coze-copywriting");

        assertThat(loaded.provider()).isEqualTo("coze");
        assertThat(loaded.providerOptions()).containsEntry("botId", "bot-from-options");
        assertThat(loaded.encryptedApiKey()).startsWith("enc:v1:");
        assertThat(crypto.decrypt(loaded.encryptedApiKey())).isEqualTo("supplier-secret");
        assertThat(jdbc.queryForObject("select api_key from model_config where id=?", String.class,
                "coze-copywriting")).isEmpty();
    }

    @Test
    void migratesLegacyPlaintextCredentialOnFirstRead() {
        jdbc.update("""
                insert into model_config
                    (id, name, model_type, model_name, api_base_url, api_key,
                     timeout_seconds, retry_count, enabled, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "legacy", "旧配置", "text", "legacy-model", "https://example.invalid",
                "legacy-secret", 30, 0, true, "1970-01-01T00:00:00Z");

        var loaded = repository.loadModelConfigs().get("legacy");

        assertThat(crypto.decrypt(loaded.encryptedApiKey())).isEqualTo("legacy-secret");
        assertThat(jdbc.queryForObject("select api_key from model_config where id='legacy'", String.class))
                .isEmpty();
        assertThat(jdbc.queryForObject("select api_key_encrypted from model_config where id='legacy'", String.class))
                .startsWith("enc:v1:");
    }

    @Test
    void clearsResidualLegacyPlaintextWhenCiphertextAlreadyExists() {
        var encrypted = crypto.encrypt("current-secret");
        jdbc.update("""
                insert into model_config
                    (id, name, model_type, model_name, api_base_url, api_key, api_key_encrypted,
                     timeout_seconds, retry_count, enabled, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "residual", "残留配置", "text", "residual-model", "https://example.invalid",
                "stale-plaintext", encrypted, 30, 0, true, "1970-01-01T00:00:00Z");

        var loaded = repository.loadModelConfigs().get("residual");

        assertThat(loaded.encryptedApiKey()).isEqualTo(encrypted);
        assertThat(jdbc.queryForObject("select api_key from model_config where id='residual'", String.class))
                .isEmpty();
    }
}
