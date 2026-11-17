package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.ai.AiModelConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:model-config-admin;DB_CLOSE_DELAY=-1",
        "app.ai.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class ModelConfigAdminApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WorkbenchRepository repository;
    @Autowired private ApiController controller;

    @Test
    void acceptsPlaintextCredentialButNeverReturnsItOrCiphertext() throws Exception {
        var token = login();
        var response = mvc.perform(put("/api/admin/model-configs/coze-copywriting")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"apiKey":"supplier-test-secret","providerOptions":{"botId":"test-bot"}}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var body = objectMapper.readTree(response);
        assertThat(body.has("apiKey")).isFalse();
        assertThat(body.has("encryptedApiKey")).isFalse();
        assertThat(response).doesNotContain("supplier-test-secret", "enc:v1:");
        assertThat(body.get("apiKeyMasked").asText()).isNotBlank();

        var listResponse = mvc.perform(get("/api/admin/model-configs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listResponse).doesNotContain("supplier-test-secret", "enc:v1:");
        assertThat(repository.loadModelConfigs().get("coze-copywriting").encryptedApiKey())
                .startsWith("enc:v1:")
                .doesNotContain("supplier-test-secret");

        var operationLogs = mvc.perform(get("/api/admin/operation-logs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(operationLogs).doesNotContain("supplier-test-secret", "enc:v1:");
    }

    @Test
    void trimsEditableFieldsAndPreservesEncryptedCredentialWhenApiKeyIsBlank() throws Exception {
        var token = login();
        mvc.perform(put("/api/admin/model-configs/coze-copywriting")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"preserve-me-secret\"}"))
                .andExpect(status().isOk());
        var encryptedApiKey = modelConfig("coze-copywriting").encryptedApiKey();

        mvc.perform(put("/api/admin/model-configs/coze-copywriting")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modelName":"  coze-copywriting-v2  ",
                                  "apiBaseUrl":"  https://api.coze.cn/v3  ",
                                  "apiKey":"   ",
                                  "timeoutSeconds":90,
                                  "retryCount":2
                                }
                                """))
                .andExpect(status().isOk());

        var updated = modelConfig("coze-copywriting");
        assertThat(updated.modelName()).isEqualTo("coze-copywriting-v2");
        assertThat(updated.apiBaseUrl()).isEqualTo("https://api.coze.cn/v3");
        assertThat(updated.timeoutSeconds()).isEqualTo(90);
        assertThat(updated.retryCount()).isEqualTo(2);
        assertThat(updated.encryptedApiKey())
                .isEqualTo(encryptedApiKey)
                .startsWith("enc:v1:")
                .doesNotContain("preserve-me-secret");
    }

    @Test
    void rejectsInvalidEditableModelConfigurationFields() throws Exception {
        var token = login();
        var invalidRequests = List.of(
                Map.of("modelName", "   "),
                Map.of("apiBaseUrl", "ftp://api.coze.cn/v3"),
                Map.of("apiBaseUrl", "not-a-url"),
                Map.of("timeoutSeconds", 0),
                Map.of("timeoutSeconds", 601),
                Map.of("retryCount", -1),
                Map.of("retryCount", 6));

        for (var request : invalidRequests) {
            mvc.perform(put("/api/admin/model-configs/coze-copywriting")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsApiKeyLongerThan8192Characters() throws Exception {
        var token = login();

        mvc.perform(put("/api/admin/model-configs/coze-copywriting")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("apiKey", "x".repeat(8193)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsRecursivelyNestedSensitiveProviderOptionKeys() throws Exception {
        var token = login();

        mvc.perform(put("/api/admin/model-configs/coze-copywriting")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerOptions":{"nested":[{"API-Key":"must-not-be-stored"}]}}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(repository.loadModelConfigs().get("coze-copywriting").providerOptions())
                .doesNotContainKey("nested");
    }

    @Test
    void filtersSensitiveKeysFromLegacyProviderOptionsInResponses() throws Exception {
        var token = login();
        var legacy = new AiModelConfig(
                "legacy-options", "遗留配置", "coze", "text", "legacy-options", "", "",
                Map.of("safe", "visible", "nested", Map.of("authorization", "must-not-leak")),
                30, 0, false);
        @SuppressWarnings("unchecked")
        var modelConfigs = (Map<String, AiModelConfig>) ReflectionTestUtils.getField(controller, "modelConfigs");
        assertThat(modelConfigs).isNotNull();
        modelConfigs.put(legacy.id(), legacy);

        var response = mvc.perform(get("/api/admin/model-configs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("visible").doesNotContain("must-not-leak", "authorization");
    }

    private String login() throws Exception {
        var response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.get("accessToken").asText();
    }

    private AiModelConfig modelConfig(String id) {
        @SuppressWarnings("unchecked")
        var modelConfigs = (Map<String, AiModelConfig>) ReflectionTestUtils.getField(controller, "modelConfigs");
        assertThat(modelConfigs).isNotNull();
        return modelConfigs.get(id);
    }
}
