package com.yiqi.aitoolbench.run;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class RunImageCounter {
    private static final Pattern SAFE_URL = Pattern.compile("(?i)^(https?://|/(?!/)).+");
    private static final Pattern IMAGE_EXTENSION = Pattern.compile("(?i)\\.(png|jpe?g|webp|gif|svg)(\\?.*)?$");
    private static final Pattern PROMO_IMAGE_FILE = Pattern.compile("(?i)/api/files/demo-download/file_[a-f0-9]{32}(\\?.*)?$");

    private RunImageCounter() {
    }

    public static long countImages(String toolKey, String status, Map<String, Object> outputData) {
        if (!"success".equalsIgnoreCase(status) || outputData == null || outputData.isEmpty()) return 0;
        var imageUrls = new LinkedHashSet<String>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        collect(outputData, toolKey, imageUrls, visited);
        return imageUrls.size();
    }

    private static void collect(Object value, String toolKey, Set<String> imageUrls, Set<Object> visited) {
        if (value == null || !visited.add(value)) return;
        if (value instanceof Collection<?> items) {
            items.forEach(item -> collect(item, toolKey, imageUrls, visited));
            return;
        }
        if (!(value instanceof Map<?, ?> item)) return;

        var url = firstText(item, "url", "downloadUrl", "fileUrl", "imageUrl");
        if (isSafeUrl(url) && isImage(item, url, toolKey)) imageUrls.add(url);
        item.values().forEach(child -> collect(child, toolKey, imageUrls, visited));
    }

    private static boolean isImage(Map<?, ?> item, String url, String toolKey) {
        var contentType = text(item.get("contentType")).toLowerCase(Locale.ROOT);
        var type = text(item.get("type"));
        return contentType.startsWith("image/")
                || "image".equalsIgnoreCase(type)
                || IMAGE_EXTENSION.matcher(url).find()
                || ("promo-image".equals(toolKey) && PROMO_IMAGE_FILE.matcher(url).find());
    }

    private static boolean isSafeUrl(String value) {
        return !value.isBlank() && SAFE_URL.matcher(value).matches();
    }

    private static String firstText(Map<?, ?> item, String... keys) {
        for (var key : keys) {
            var value = text(item.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }
}
