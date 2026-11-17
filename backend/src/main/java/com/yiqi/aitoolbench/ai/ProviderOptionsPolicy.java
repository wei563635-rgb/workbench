package com.yiqi.aitoolbench.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ProviderOptionsPolicy {
    private static final Set<String> SENSITIVE_PARTS = Set.of(
            "apikey", "token", "secret", "authorization", "credential", "password");

    private ProviderOptionsPolicy() {}

    public static void rejectSensitiveKeys(Map<String, Object> options) {
        inspect(options == null ? Map.of() : options, "providerOptions");
    }

    public static Map<String, Object> sanitized(Map<String, Object> options) {
        return sanitizeMap(options == null ? Map.of() : options);
    }

    private static void inspect(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                var key = String.valueOf(entry.getKey());
                if (isSensitive(key)) {
                    throw new IllegalArgumentException("providerOptions 不允许敏感字段: " + path + "." + key);
                }
                inspect(entry.getValue(), path + "." + key);
            }
        } else if (value instanceof Iterable<?> iterable) {
            var index = 0;
            for (var item : iterable) {
                inspect(item, path + "[" + index++ + "]");
            }
        }
    }

    private static Map<String, Object> sanitizeMap(Map<?, ?> input) {
        var result = new LinkedHashMap<String, Object>();
        input.forEach((rawKey, value) -> {
            var key = String.valueOf(rawKey);
            if (!isSensitive(key)) {
                result.put(key, sanitizeValue(value));
            }
        });
        return result;
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map);
        }
        if (value instanceof Iterable<?> iterable) {
            var result = new ArrayList<>();
            iterable.forEach(item -> result.add(sanitizeValue(item)));
            return List.copyOf(result);
        }
        return value;
    }

    private static boolean isSensitive(String key) {
        var normalized = key.toLowerCase(Locale.ROOT).chars()
                .filter(Character::isLetterOrDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return SENSITIVE_PARTS.stream().anyMatch(normalized::contains);
    }
}
