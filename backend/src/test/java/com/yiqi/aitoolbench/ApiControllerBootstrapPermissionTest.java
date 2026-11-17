package com.yiqi.aitoolbench;

import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.security.DataScope;
import com.yiqi.aitoolbench.security.PermissionService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bootstrap-permissions;DB_CLOSE_DELAY=-1",
        "app.detail-page.entry-url=http://test-detail-page.example:18082/"
})
@AutoConfigureMockMvc
class ApiControllerBootstrapPermissionTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private PermissionService permissionService;

    @BeforeEach
    void setUpPermissionBehavior() {
        when(permissionService.hasPermission(any(), anyString()))
                .thenAnswer(invocation -> ((AuthContext) invocation.getArgument(0)).permissions()
                        .contains(invocation.getArgument(1)));
        when(permissionService.hasToolAccess(any(), anyString())).thenReturn(false);
        when(permissionService.hasProjectScope(any(), anyString())).thenReturn(true);
        when(permissionService.effectiveDataScope(any())).thenReturn(DataScope.SELF);
    }

    @Test
    void bootstrapExposesOrganizationWithoutUsersForDepartmentOnlyReader() throws Exception {
        var context = context("department-reader", List.of("workbench:access", "admin:department:read"));
        when(permissionService.requireContext("Bearer department-reader")).thenReturn(context);

        mockMvc.perform(get("/api/bootstrap").header("Authorization", "Bearer department-reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationDepartments").isArray())
                .andExpect(jsonPath("$.organizationDepartments").isNotEmpty())
                .andExpect(jsonPath("$.users").doesNotExist());
    }

    @Test
    void bootstrapExposesEnabledOrganizationForWorkbenchProjectEditor() throws Exception {
        var context = context("workbench-reader", List.of("workbench:access"));
        when(permissionService.requireContext("Bearer workbench-reader")).thenReturn(context);

        mockMvc.perform(get("/api/bootstrap").header("Authorization", "Bearer workbench-reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationDepartments").isArray())
                .andExpect(jsonPath("$.organizationDepartments").isNotEmpty())
                .andExpect(jsonPath("$.users").doesNotExist());
    }

    @Test
    void deploymentGateAllowsBothDetailPageEntriesToUseTheApprovedExternalIntegration() throws Exception {
        var detailManifest = """
                {"toolKey":"detail-page","name":"详情页制作","departmentKey":"ai-design",
                 "entry":"/platform-entry","integrationType":"external","status":"normal",
                 "callbackEnabled":true,"aiGatewayRequired":true,
                 "requiredApis":["/api/tool-runs/start","/api/tool-runs/{runId}/finish","/api/ai/chat/completions"]}
                """;
        mockMvc.perform(post("/developer/validate").contentType(APPLICATION_JSON).content(detailManifest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));

        mockMvc.perform(post("/developer/validate").contentType(APPLICATION_JSON)
                        .content(detailManifest.replace("detail-page", "detail-page-sso")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));

        var otherManifest = detailManifest.replace("detail-page", "unapproved-external-tool");
        mockMvc.perform(post("/developer/validate").contentType(APPLICATION_JSON).content(otherManifest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false));
    }

    @Test
    void bootstrapUsesConfiguredDetailPageEntryUrl() throws Exception {
        var context = context("detail-reader", List.of("workbench:access"));
        when(permissionService.requireContext("Bearer detail-reader")).thenReturn(context);
        when(permissionService.hasToolAccess(any(), eq("detail-page"))).thenReturn(true);
        when(permissionService.hasToolAccess(any(), eq("detail-page-sso"))).thenReturn(true);

        mockMvc.perform(get("/api/bootstrap").header("Authorization", "Bearer detail-reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andExpect(jsonPath("$.tools[0].key").value("detail-page"))
                .andExpect(jsonPath("$.tools[0].entryUrl").value("http://test-detail-page.example:18082/"));

        assertEquals("http://test-detail-page.example:18082/", jdbc.queryForObject(
                "select entry_url from tool_config where tool_key='detail-page'", String.class));
        assertEquals("http://test-detail-page.example:18082/", jdbc.queryForObject(
                "select entry_url from tool_config where tool_key='detail-page-sso'", String.class));
        assertEquals("EXTERNAL", jdbc.queryForObject(
                "select integration_type from tool_config where tool_key='detail-page-sso'", String.class));
    }

    private AuthContext context(String token, List<String> permissions) {
        return new AuthContext(token, "u_test", "tester", "测试用户", false, 1, permissions);
    }
}
