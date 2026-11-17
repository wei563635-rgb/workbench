package com.yiqi.aitoolbench.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yiqi.aitoolbench.auth.AuthContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PermissionInterceptorUpstreamAccountTest {
    private PermissionService permissions;
    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        permissions = mock(PermissionService.class);
        interceptor = new PermissionInterceptor(permissions);
    }

    @Test
    void readsAndMutationsUseIndependentUpstreamAccountPermissions() throws Exception {
        var reader = context("reader", "admin:upstream-account:read");
        var writer = context("writer", "admin:upstream-account:write");
        allow(reader, "admin:upstream-account:read");
        allow(writer, "admin:upstream-account:write");

        assertThat(invoke("GET", "/api/admin/upstream-accounts", reader).allowed()).isTrue();
        assertThat(invoke("GET", "/api/admin/upstream-accounts/proxies", writer).allowed()).isFalse();
        assertThat(invoke("POST", "/api/admin/upstream-accounts/oauth/start", writer).allowed()).isTrue();
        assertThat(invoke("POST", "/api/admin/upstream-accounts", reader).allowed()).isFalse();
        assertThat(invoke("POST", "/api/admin/upstream-accounts", writer).allowed()).isTrue();
        assertThat(invoke("PUT", "/api/admin/upstream-accounts/7", reader).allowed()).isFalse();
        assertThat(invoke("DELETE", "/api/admin/upstream-accounts/7", reader).allowed()).isFalse();
    }

    private AuthContext context(String token, String permission) {
        return new AuthContext(token, "u_test", "tester", "测试用户", false, 1, List.of(permission));
    }

    private void allow(AuthContext context, String permission) {
        when(permissions.requireContext("Bearer " + context.token())).thenReturn(context);
        when(permissions.hasPermission(context, permission)).thenReturn(true);
    }

    private Result invoke(String method, String path, AuthContext context) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer " + context.token());
        var response = new MockHttpServletResponse();
        return new Result(interceptor.preHandle(request, response, new Object()), response);
    }

    private record Result(boolean allowed, MockHttpServletResponse response) {}
}
