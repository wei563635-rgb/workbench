package com.yiqi.aitoolbench.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToolAvailabilityPolicyTest {
    @Test
    void detailPageIsAvailableAfterItsExternalEntryIsConfigured() {
        assertEquals("normal", ToolAvailabilityPolicy.defaultStatus("detail-page"));
        assertEquals("normal", ToolAvailabilityPolicy.defaultStatus("detail-page-sso"));
    }
}
