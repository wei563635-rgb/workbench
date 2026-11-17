package com.yiqi.aitoolbench.security;

import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.auth.AuthService;
import com.yiqi.aitoolbench.tool.ToolRepository;
import com.yiqi.aitoolbench.toolsession.ToolSessionService;
import com.yiqi.aitoolbench.user.UserRepository;
import java.time.Clock;
import java.util.Comparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    private final AuthService authService;
    private final UserRepository userRepository;
    private final ToolRepository toolRepository;
    private final ToolSessionService toolSessionService;
    private final Clock clock;

    @Autowired
    public PermissionService(AuthService authService, UserRepository userRepository, ToolRepository toolRepository,
            ToolSessionService toolSessionService) {
        this(authService, userRepository, toolRepository, toolSessionService, Clock.systemUTC());
    }

    public PermissionService(AuthService authService, UserRepository userRepository, ToolRepository toolRepository,
            ToolSessionService toolSessionService, Clock clock) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.toolRepository = toolRepository;
        this.toolSessionService = toolSessionService;
        this.clock = clock;
    }

    public AuthContext requireContext(String authorization) {
        var token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length())
                : authorization;
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("登录态无效或已过期");
        }
        if (token.startsWith("tool_session_")) {
            var scoped = toolSessionService.introspect(token, clock.instant());
            var nonAdmin = scoped.permissions().stream().filter(code -> !code.startsWith("admin:")).toList();
            return new AuthContext(token, scoped.userId(), scoped.account(), scoped.displayName(), false, 0,
                    nonAdmin, true, scoped.toolKey(), scoped.projectId());
        }
        return authService.requireContext(token);
    }

    public boolean isSuperAdmin(AuthContext context) {
        return !context.toolSession() && userRepository.listActiveUserRoleCodes(context.userId()).contains("admin")
            && userRepository.listRoles().stream().anyMatch(r -> "admin".equals(r.code()) && r.builtIn() && r.enabled());
    }

    public boolean hasPermission(AuthContext context, String permissionCode) {
        return context.permissions().contains(permissionCode);
    }

    public void requirePermission(AuthContext context, String permissionCode) {
        if (!hasPermission(context, permissionCode)) {
            throw new ForbiddenException("缺少权限: " + permissionCode);
        }
    }

    public boolean hasToolAccess(AuthContext context, String toolKey) {
        if (context.toolSession()) {
            return context.toolKey().equals(toolKey);
        }
        return toolRepository.hasToolAccess(context.userId(), toolKey);
    }

    public boolean hasProjectScope(AuthContext context, String projectId) {
        return !context.toolSession() || context.projectId().equals(projectId);
    }

    public void requireToolAccess(AuthContext context, String toolKey) {
        if (!hasToolAccess(context, toolKey)) {
            throw new ForbiddenException("无权使用工具: " + toolKey);
        }
    }

    public DataScope effectiveDataScope(AuthContext context) {
        if (context.toolSession()) {
            return DataScope.SELF;
        }
        return userRepository.listDataScopes(context.userId()).stream()
                .map(DataScope::from)
                .max(Comparator.comparingInt(DataScope::priority))
                .orElse(DataScope.SELF);
    }
}
