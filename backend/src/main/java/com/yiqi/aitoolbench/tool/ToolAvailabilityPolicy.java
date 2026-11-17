package com.yiqi.aitoolbench.tool;

public final class ToolAvailabilityPolicy {
    public static final String INTEGRATED_TOOL_KEY = "promo-image";
    public static final String DETAIL_PAGE_TOOL_KEY = "detail-page";
    public static final String DETAIL_PAGE_SSO_TOOL_KEY = "detail-page-sso";
    public static final String CLOUD_AI_TOOL_KEY = "cloud-ai";

    public static final String NEW_CHAT_TOOL_KEY = "new-chat";

    private ToolAvailabilityPolicy() {
    }

    public static String defaultStatus(String toolKey) {
        return INTEGRATED_TOOL_KEY.equals(toolKey) || DETAIL_PAGE_TOOL_KEY.equals(toolKey)
                || DETAIL_PAGE_SSO_TOOL_KEY.equals(toolKey)
                ? "normal"
                : "unopened";
    }
}
