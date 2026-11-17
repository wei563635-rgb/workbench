package com.yiqi.aitoolbench.auth;

import java.util.List;

public record AuthContext(
        String token,
        String userId,
        String account,
        String displayName,
        boolean mustChangePassword,
        int sessionVersion,
        List<String> permissions,
        boolean toolSession,
        String toolKey,
        String projectId) {
    public AuthContext(String token, String userId, String account, String displayName, boolean mustChangePassword,
            int sessionVersion, List<String> permissions) {
        this(token, userId, account, displayName, mustChangePassword, sessionVersion, permissions, false, null, null);
    }
}
