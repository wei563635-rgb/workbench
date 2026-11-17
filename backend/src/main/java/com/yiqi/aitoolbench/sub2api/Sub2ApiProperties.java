package com.yiqi.aitoolbench.sub2api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Sub2ApiProperties {
    private final String baseUrl;
    private final String adminApiKey;
    private final int connectTimeoutSeconds;
    private final int requestTimeoutSeconds;

    public Sub2ApiProperties(
            @Value("${app.sub2api.base-url:}") String baseUrl,
            @Value("${app.sub2api.admin-api-key:}") String adminApiKey,
            @Value("${app.sub2api.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${app.sub2api.request-timeout-seconds:30}") int requestTimeoutSeconds) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.adminApiKey = adminApiKey == null ? "" : adminApiKey.trim();
        this.connectTimeoutSeconds = Math.max(1, connectTimeoutSeconds);
        this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String adminApiKey() {
        return adminApiKey;
    }

    public int connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public int requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public boolean configured() {
        return !baseUrl.isBlank() && !adminApiKey.isBlank();
    }

    private String normalizeBaseUrl(String value) {
        var normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
