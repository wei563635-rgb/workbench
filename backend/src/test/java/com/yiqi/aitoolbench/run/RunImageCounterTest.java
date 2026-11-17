package com.yiqi.aitoolbench.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunImageCounterTest {
    @Test
    void countsUniquePersistedImagesAcrossNestedOutputShapes() {
        var output = Map.<String, Object>of(
                "attachments", List.of(
                        Map.of("downloadUrl", "/api/files/demo-download/file_0123456789abcdef0123456789abcdef"),
                        Map.of("url", "https://cdn.example.com/result.PNG?version=1"),
                        Map.of("url", "https://cdn.example.com/result.PNG?version=1")),
                "nested", Map.of("result", Map.of(
                        "fileUrl", "/files/rendered-output",
                        "contentType", "image/webp")));

        assertEquals(3, RunImageCounter.countImages("promo-image", "success", output));
    }

    @Test
    void ignoresFailedRunsNonImagesUnsafeUrlsAndMissingTargets() {
        var output = Map.<String, Object>of("attachments", List.of(
                Map.of("url", "javascript:alert(1)", "type", "image"),
                Map.of("downloadUrl", "/api/files/report.pdf", "contentType", "application/pdf"),
                Map.of("name", "missing-url.png")));

        assertEquals(0, RunImageCounter.countImages("promo-image", "success", output));
        assertEquals(0, RunImageCounter.countImages("promo-image", "failed", Map.of(
                "attachment", Map.of("url", "/failed.png"))));
        assertEquals(0, RunImageCounter.countImages("promo-image", "running", Map.of(
                "attachment", Map.of("url", "/running.png"))));
    }

    @Test
    void opaquePlatformFileOnlyCountsAsImageForPromoTool() {
        var output = Map.<String, Object>of("file", Map.of(
                "downloadUrl", "/api/files/demo-download/file_abcdefabcdefabcdefabcdefabcdefab"));

        assertEquals(1, RunImageCounter.countImages("promo-image", "SUCCESS", output));
        assertEquals(0, RunImageCounter.countImages("detail-page", "success", output));
    }
}
