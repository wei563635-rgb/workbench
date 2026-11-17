package com.yiqi.aitoolbench.ai;

import java.util.Map;

public record AiModelConfig(
        String id,
        String name,
        String provider,
        String modelType,
        String modelName,
        String apiBaseUrl,
        String encryptedApiKey,
        Map<String, Object> providerOptions,
        int timeoutSeconds,
        int retryCount,
        boolean enabled) {

    public AiModelConfig {
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(providerOptions);
    }
}
