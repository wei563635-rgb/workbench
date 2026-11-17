package com.yiqi.aitoolbench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LogRepositoryAggregateTest {
    @Test
    void aggregatesAllTokenUsageAndGeneratedImagesFromDatabase() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:log-aggregate;DB_CLOSE_DELAY=-1", "sa", "");
        var repository = new LogRepository(new JdbcTemplate(dataSource), new ObjectMapper());
        repository.insertAiLog(log("chat", "success", 120, Map.of("content", "ok")));
        repository.insertAiLog(log("chat", "success", 90, Map.of("content", "coze"), "coze-copywriting"));
        repository.insertAiLog(log("image", "success", 0, Map.of(
                "images", List.of("a.png", "b.png"),
                "usage", Map.of("credits_reserved", 1.836))));
        repository.insertAiLog(log("image", "failed", 0, Map.of(
                "images", List.of("failed.png"),
                "usage", Map.of("credits_reserved", 1.836))));

        var aggregate = repository.aggregateAiStats();

        assertEquals(4, aggregate.callCount());
        assertEquals(120, aggregate.totalTokens());
        assertEquals(0, new BigDecimal("1.836").compareTo(aggregate.totalCredits()));
        assertEquals(2, aggregate.generatedImageCount());

        var imageLog = repository.listAiLogs().stream()
                .filter(log -> "image".equals(log.modelType()) && "success".equals(log.status()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, imageLog.totalTokens());
    }

    private ApiController.AiCallLog log(String type, String status, int tokens, Map<String, Object> response) {
        return log(type, status, tokens, response, "model_1");
    }

    private ApiController.AiCallLog log(String type, String status, int tokens, Map<String, Object> response,
            String modelName) {
        return new ApiController.AiCallLog(
                "log_" + type + "_" + status + "_" + modelName,
                "run_1",
                "tool_1",
                "user_1",
                modelName,
                type,
                Map.of(),
                response,
                tokens,
                0,
                tokens,
                BigDecimal.valueOf(tokens * 0.00002),
                status,
                Instant.parse("2026-07-13T00:00:00Z"));
    }
}
