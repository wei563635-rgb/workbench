package com.yiqi.aitoolbench.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class PermissionInterceptor implements HandlerInterceptor {
    private static final Set<String> TOOL_SESSION_RUNTIME_APIS = Set.of(
            "POST /api/tool-runs/start",
            "POST /api/files/upload",
            "POST /api/project-links",
            "POST /api/ai/chat/completions",
            "POST /api/ai/images/generations");
    private static final Pattern TOOL_RUN_MUTATION = Pattern.compile(
            "^/api/tool-runs/[A-Za-z0-9_-]+/(events|finish)$");
    private static final Pattern TOOL_RUN_READ = Pattern.compile(
            "^/api/tool-runs/[A-Za-z0-9_-]+$");
    private static final Pattern AI_TASK_READ = Pattern.compile(
            "^/api/ai/tasks/[A-Za-z0-9_-]+$");

    private final PermissionService permissionService;

    public PermissionInterceptor(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        var path = request.getRequestURI();
        var method = request.getMethod();
        try {
            var context = permissionService.requireContext(request.getHeader("Authorization"));
            if (context.mustChangePassword() && !isPasswordChangeAllowed(method, path)) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "请先修改初始密码");
                return false;
            }
            if (context.toolSession() && !isToolSessionRuntimeApi(method, path)) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "工具会话无权访问该接口");
                return false;
            }
            if ((path.startsWith("/api/admin/ai-usage-records") || path.startsWith("/api/admin/ai-call-logs")) && !permissionService.isSuperAdmin(context)) {
                writeError(response,403,"FORBIDDEN","使用记录仅超级管理员可查看");return false;
            }
            var requiredPermission = requiredPermission(method, path);
            if (requiredPermission != null && !permissionService.hasPermission(context, requiredPermission)) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "无权访问该接口");
                return false;
            }
            return true;
        } catch (IllegalArgumentException | UnauthorizedException ex) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "接口需要登录后访问");
            return false;
        }
    }

    private boolean isToolSessionRuntimeApi(String method, String path) {
        if (TOOL_SESSION_RUNTIME_APIS.contains(method + " " + path)) {
            return true;
        }
        return ("POST".equals(method) && TOOL_RUN_MUTATION.matcher(path).matches())
                || ("GET".equals(method) && (TOOL_RUN_READ.matcher(path).matches()
                || AI_TASK_READ.matcher(path).matches()));
    }

    private String requiredPermission(String method, String path) {
        if (matchesPath(path, "/api/admin/relay/quotas")) {
            return "GET".equals(method) ? "admin:relay-quota:read" : "admin:relay-quota:write";
        }
        if (matchesPath(path, "/api/admin/relay/usage")) return "admin:relay-usage:read";
        if (matchesPath(path, "/api/admin/relay/audit")) return "admin:relay-audit:read";
        if (path.startsWith("/api/auth/") || path.startsWith("/api/legacy/auth/")) {
            return null;
        }
        if (path.startsWith("/api/admin/users")) {
            return "GET".equals(method) ? "admin:user:read" : "admin:user:write";
        }
        if (path.startsWith("/api/admin/brand-pages")) {
            return "GET".equals(method) ? "brand:page:read" : "admin:brand-page:write";
        }
        if (path.startsWith("/api/brand-pages")) {
            return "brand:page:read";
        }
        if (matchesPath(path, "/api/admin/organization-departments")
                || matchesPath(path, "/api/admin/departments")) {
            return "GET".equals(method) ? "admin:department:read" : "admin:department:write";
        }
        if (path.equals("/api/admin/permissions")) {
            return "admin:role:read";
        }
        if (path.startsWith("/api/admin/roles")) {
            return "GET".equals(method) ? "admin:role:read" : "admin:role:write";
        }
        if (path.startsWith("/api/admin/tools")) {
            return "GET".equals(method) ? "admin:tool:read" : "admin:tool:write";
        }
        if (path.startsWith("/api/admin/model-configs")) {
            return "GET".equals(method) ? "admin:model:read" : "admin:model:write";
        }
        if (path.startsWith("/api/admin/upstream-accounts")) {
            return "GET".equals(method) ? "admin:upstream-account:read" : "admin:upstream-account:write";
        }
        if (path.startsWith("/api/admin/ai-usage-records")) return null;
        if (path.startsWith("/api/admin/ai-call-logs")) {
            return "admin:ai-log:read";
        }
        if (path.startsWith("/api/admin/operation-logs")) {
            return "admin:log:read";
        }
        if (path.startsWith("/api/admin/deployments/request")) {
            return "admin:deploy:approve";
        }
        if (path.startsWith("/api/admin/deployments")) {
            return "admin:deploy:read";
        }
        if (path.startsWith("/api/admin/")) {
            return "admin:role:write";
        }
        if (path.startsWith("/api/")) {
            return "workbench:access";
        }
        return null;
    }

    private boolean matchesPath(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private boolean isPasswordChangeAllowed(String method, String path) {
        return ("POST".equals(method) && "/api/auth/change-password".equals(path))
                || ("POST".equals(method) && "/api/auth/logout".equals(path))
                || ("GET".equals(method) && "/api/auth/me".equals(path))
                || ("POST".equals(method) && "/api/legacy/auth/change-password".equals(path))
                || ("GET".equals(method) && "/api/legacy/auth/me".equals(path));
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
