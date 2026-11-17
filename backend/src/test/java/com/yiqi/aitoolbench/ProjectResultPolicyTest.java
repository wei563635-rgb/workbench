package com.yiqi.aitoolbench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectResultPolicyTest {
    @Test
    void onlyCompletedSuccessfulRunsAreOutputs() {
        var completed = run("success", "run", Instant.parse("2026-07-13T00:00:00Z"));
        var opened = run("running", "open", null);

        assertTrue(ProjectResultPolicy.isCompleted(completed));
        assertFalse(ProjectResultPolicy.isCompleted(opened));
        assertFalse(ProjectResultPolicy.isCompleted(run("failed", "run", Instant.parse("2026-07-13T00:00:00Z"))));
    }

    private ApiController.ToolRun run(String status, String eventType, Instant finishedAt) {
        return new ApiController.ToolRun(
                "run_1", "consumer-insight", "u_1", "project_1", status, eventType,
                "input", "output", Map.of(), Instant.parse("2026-07-13T00:00:00Z"), finishedAt);
    }
}
