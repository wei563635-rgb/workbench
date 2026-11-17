package com.yiqi.aitoolbench.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.security.SecretCrypto;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

abstract class ProviderHttpSupport implements AiProviderAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    protected final HttpClient httpClient;
    private final SecretCrypto secretCrypto;

    protected ProviderHttpSupport(HttpClient httpClient, SecretCrypto secretCrypto) {
        this.httpClient = httpClient;
        this.secretCrypto = secretCrypto;
    }

    protected ProviderResponse post(AiModelConfig config, String fallbackPath, Map<String, Object> payload) {
        var request = HttpRequest.newBuilder(URI.create(endpoint(config.apiBaseUrl(), fallbackPath)))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + credential(config))
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload), StandardCharsets.UTF_8))
                .build();
        return requireHttpSuccess(send(request));
    }

    protected ProviderResponse get(AiModelConfig config, String endpoint) {
        var request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization", "Bearer " + credential(config))
                .GET()
                .build();
        return requireHttpSuccess(send(request));
    }

    protected String endpoint(String baseUrl, String fallbackPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("模型 apiBaseUrl 不能为空");
        }
        var trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (trimmed.endsWith(fallbackPath)) {
            return trimmed;
        }
        return trimmed + fallbackPath;
    }

    protected String option(AiModelConfig config, String key, String fallback) {
        var value = config.providerOptions().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    protected int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text && !text.isBlank()) return Integer.parseInt(text);
        return 0;
    }

    protected ProviderResponse requireNoBusinessError(ProviderResponse response) {
        var error = response.body().get("error");
        if (error != null && (!(error instanceof String text) || !text.isBlank())) {
            throw new ProviderGatewayException();
        }
        return response;
    }

    protected ProviderGatewayException providerFailure() {
        return new ProviderGatewayException();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private ProviderResponse send(HttpRequest request) {
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ProviderResponse(response.statusCode(), toMap(response.body()));
        } catch (IOException ex) {
            throw new ProviderGatewayException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderGatewayException(ex);
        }
    }

    private ProviderResponse requireHttpSuccess(ProviderResponse response) {
        if (!response.successful()) throw new ProviderGatewayException();
        return response;
    }

    private String credential(AiModelConfig config) {
        var value = secretCrypto.decrypt(config.encryptedApiKey());
        if (value.isBlank()) throw new IllegalArgumentException("模型供应商凭证未配置: " + config.modelName());
        return value;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException ex) {
            throw new IllegalArgumentException("AI 供应商请求序列化失败", ex);
        }
    }

    private Map<String, Object> toMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return JSON.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException ex) {
            throw new ProviderGatewayException(ex);
        }
    }

    protected record ProviderResponse(int statusCode, Map<String, Object> body) {
        boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
