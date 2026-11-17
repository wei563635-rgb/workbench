package com.yiqi.aitoolbench.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yiqi.aitoolbench.auth.AuthContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PermissionInterceptorToolSessionTest {
    private PermissionService permissions;
    private PermissionInterceptor interceptor;
    private AuthContext toolSession;

    @BeforeEach
    void setUp() {
        permissions = mock(PermissionService.class);
        interceptor = new PermissionInterceptor(permissions);
        toolSession = new AuthContext("tool_session_opaque", "u1", "member", "成员", false, 0,
                List.of("workbench:access"), true, "promo-image", "p1");
        when(permissions.requireContext("Bearer tool_session_opaque")).thenReturn(toolSession);
        when(permissions.hasPermission(toolSession, "workbench:access")).thenReturn(true);
    }

    @Test
    void toolSessionAllowsOnlyExactRuntimeApiWhitelist() throws Exception {
        assertAllowed("POST", "/api/tool-runs/start");
        assertAllowed("POST", "/api/tool-runs/run_1/events");
        assertAllowed("POST", "/api/tool-runs/run_1/finish");
        assertAllowed("GET", "/api/tool-runs/run_1");
        assertAllowed("POST", "/api/files/upload");
        assertAllowed("POST", "/api/project-links");
        assertAllowed("POST", "/api/ai/chat/completions");
        assertAllowed("POST", "/api/ai/images/generations");
        assertAllowed("GET", "/api/ai/tasks/task_1");

        assertForbidden("POST", "/api/projects/p2/status");
        assertForbidden("POST", "/api/projects");
        assertForbidden("GET", "/api/bootstrap");
        assertForbidden("GET", "/api/stats/summary");
        assertForbidden("GET", "/api/ai/tasks/task_1/extra");
        assertForbidden("GET", "/api/admin/users");
        assertForbidden("POST", "/api/tool-sessions/tickets");
        assertForbidden("GET", "/api/tool-runs");
        assertForbidden("POST", "/api/tool-runs/start/extra");
        assertForbidden("GET", "/api/tool-runs/run_1/extra");
    }

    @Test
    void ordinaryPlatformSessionIsNotRestrictedByToolWhitelist() throws Exception {
        var platform = new AuthContext("session_normal", "u1", "member", "成员", false, 1,
                List.of("workbench:access"));
        when(permissions.requireContext("Bearer session_normal")).thenReturn(platform);
        when(permissions.hasPermission(platform, "workbench:access")).thenReturn(true);
        assertThat(invoke("POST", "/api/projects", "Bearer session_normal").allowed()).isTrue();
        assertThat(invoke("GET", "/api/bootstrap", "Bearer session_normal").allowed()).isTrue();
    }

    @Test
    void serviceCredentialCannotAuthenticateOrdinaryOrAdminApisAndErrorDoesNotLeakIt() throws Exception {
        when(permissions.requireContext("Bearer service-test-credential"))
                .thenThrow(new IllegalArgumentException("登录态无效或已过期"));
        for (var path : List.of("/api/bootstrap", "/api/admin/users")) {
            var result = invoke("GET", path, "Bearer service-test-credential");
            assertThat(result.allowed()).isFalse();
            assertThat(result.response().getStatus()).isEqualTo(401);
            assertThat(result.response().getContentAsString()).doesNotContain("service-test-credential");
        }
    }

    private void assertAllowed(String method, String path) throws Exception {
        assertThat(invoke(method, path, "Bearer tool_session_opaque").allowed()).as(path).isTrue();
    }

    private void assertForbidden(String method, String path) throws Exception {
        var result = invoke(method, path, "Bearer tool_session_opaque");
        assertThat(result.allowed()).as(path).isFalse();
        assertThat(result.response().getStatus()).as(path).isEqualTo(403);
    }

    private Result invoke(String method, String path, String authorization) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", authorization);
        var response = new MockHttpServletResponse();
        return new Result(interceptor.preHandle(request, response, new Object()), response);
    }

    private record Result(boolean allowed, MockHttpServletResponse response) {}
}
