package com.yiqi.aitoolbench.security;

import com.yiqi.aitoolbench.auth.AuthContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionInterceptorOrganizationTest {
    private PermissionService permissions;
    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        permissions = mock(PermissionService.class);
        interceptor = new PermissionInterceptor(permissions);
    }

    @Test
    void organizationGetRequiresDepartmentReadInsteadOfRoleWrite() throws Exception {
        var departmentReader = context("department-reader", "admin:department:read");
        var roleWriter = context("role-writer", "admin:role:write");
        allowContext(departmentReader, "admin:department:read");
        allowContext(roleWriter, "admin:role:write");

        assertAllowed("GET", "/api/admin/organization-departments", departmentReader.token());
        assertForbidden("GET", "/api/admin/organization-departments", roleWriter.token());
    }

    @Test
    void organizationMutationsRequireDepartmentWriteInsteadOfReadOrRoleWrite() throws Exception {
        var departmentWriter = context("department-writer", "admin:department:write");
        var departmentReader = context("department-reader", "admin:department:read");
        var roleWriter = context("role-writer", "admin:role:write");
        allowContext(departmentWriter, "admin:department:write");
        allowContext(departmentReader, "admin:department:read");
        allowContext(roleWriter, "admin:role:write");

        for (var endpoint : List.of(
                new Endpoint("POST", "/api/admin/organization-departments"),
                new Endpoint("PUT", "/api/admin/organization-departments/dept_1"),
                new Endpoint("DELETE", "/api/admin/organization-departments/dept_1"),
                new Endpoint("POST", "/api/admin/organization-departments/dept_1/status"))) {
            assertAllowed(endpoint.method(), endpoint.path(), departmentWriter.token());
            assertForbidden(endpoint.method(), endpoint.path(), departmentReader.token());
            assertForbidden(endpoint.method(), endpoint.path(), roleWriter.token());
        }
    }

    @Test
    void legacyDepartmentExactPathKeepsDepartmentReadAndWritePermissions() throws Exception {
        var departmentReader = context("department-reader", "admin:department:read");
        var departmentWriter = context("department-writer", "admin:department:write");
        var roleWriter = context("role-writer", "admin:role:write");
        allowContext(departmentReader, "admin:department:read");
        allowContext(departmentWriter, "admin:department:write");
        allowContext(roleWriter, "admin:role:write");

        assertAllowed("GET", "/api/admin/departments", departmentReader.token());
        assertForbidden("GET", "/api/admin/departments", roleWriter.token());
        assertAllowed("POST", "/api/admin/departments", departmentWriter.token());
        assertForbidden("POST", "/api/admin/departments", departmentReader.token());
        assertForbidden("POST", "/api/admin/departments", roleWriter.token());
    }

    @Test
    void similarUnknownPathsFallBackToRoleWritePermission() throws Exception {
        var departmentReader = context("department-reader", "admin:department:read");
        var departmentWriter = context("department-writer", "admin:department:write");
        var roleWriter = context("role-writer", "admin:role:write");
        allowContext(departmentReader, "admin:department:read");
        allowContext(departmentWriter, "admin:department:write");
        allowContext(roleWriter, "admin:role:write");

        for (var path : List.of(
                "/api/admin/departments-export",
                "/api/admin/organization-departments-extra")) {
            assertForbidden("GET", path, departmentReader.token());
            assertAllowed("GET", path, roleWriter.token());
            assertForbidden("POST", path, departmentWriter.token());
            assertAllowed("POST", path, roleWriter.token());
        }
    }

    private AuthContext context(String token, String permission) {
        return new AuthContext(token, "u_test", "tester", "测试用户", false, 1, List.of(permission));
    }

    private void allowContext(AuthContext context, String permission) {
        when(permissions.requireContext("Bearer " + context.token())).thenReturn(context);
        when(permissions.hasPermission(context, permission)).thenReturn(true);
    }

    private void assertAllowed(String method, String path, String token) throws Exception {
        assertThat(invoke(method, path, token).allowed()).as(method + " " + path).isTrue();
    }

    private void assertForbidden(String method, String path, String token) throws Exception {
        var result = invoke(method, path, token);
        assertThat(result.allowed()).as(method + " " + path).isFalse();
        assertThat(result.response().getStatus()).as(method + " " + path).isEqualTo(403);
    }

    private Result invoke(String method, String path, String token) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        return new Result(interceptor.preHandle(request, response, new Object()), response);
    }

    private record Endpoint(String method, String path) {}

    private record Result(boolean allowed, MockHttpServletResponse response) {}
}
