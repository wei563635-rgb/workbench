package com.yiqi.aitoolbench;

final class ProjectResultPolicy {
    private ProjectResultPolicy() {}

    static boolean isCompleted(ApiController.ToolRun run) {
        return run != null
                && "success".equalsIgnoreCase(run.status())
                && "run".equalsIgnoreCase(run.eventType())
                && run.finishedAt() != null;
    }
}
