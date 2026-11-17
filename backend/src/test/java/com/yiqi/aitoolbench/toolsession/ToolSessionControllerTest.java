package com.yiqi.aitoolbench.toolsession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yiqi.aitoolbench.GlobalExceptionHandler;
import com.yiqi.aitoolbench.ApiController;
import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.org.OrganizationDirectoryService;
import com.yiqi.aitoolbench.security.PermissionService;
import com.yiqi.aitoolbench.security.UnauthorizedException;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ToolSessionControllerTest {
    private ToolSessionService service;
    private PermissionService permissions;
    private ApiController apiController;
    private OrganizationDirectoryService organizationDirectoryService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ToolSessionService.class);
        permissions = mock(PermissionService.class);
        apiController = mock(ApiController.class);
        organizationDirectoryService = mock(OrganizationDirectoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ToolSessionController(service, permissions, "test-service-credential", apiController,
                                organizationDirectoryService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void detailPageEntryIssuesTicketsForEveryAuthorizedUser() throws Exception {
        var pilot = new AuthContext("session_1", "u1", "pilot", "试用员工", false, 1,
                List.of("workbench:access"));
        var legacy = new AuthContext("session_2", "u2", "legacy", "普通员工", false, 1,
                List.of("workbench:access"));
        when(permissions.requireContext("Bearer session_1")).thenReturn(pilot);
        when(permissions.requireContext("Bearer session_2")).thenReturn(legacy);
        when(service.issue(pilot, "detail-page-sso", "p1")).thenReturn(new ToolSessionService.IssuedTicket(
                "id", "tool_ticket_opaque", Instant.parse("2026-07-15T02:01:00Z")));
        when(service.issue(legacy, "detail-page-sso", "p1")).thenReturn(new ToolSessionService.IssuedTicket(
                "id2", "tool_ticket_other", Instant.parse("2026-07-15T02:01:00Z")));

        mvc.perform(post("/api/tool-sessions/detail-page-entry").header("Authorization", "Bearer session_1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"p1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("workbench"))
                .andExpect(jsonPath("$.entryUrl")
                        .value("/tools/detail-page-sso/platform-entry?ticket=tool_ticket_opaque"));
        mvc.perform(post("/api/tool-sessions/detail-page-entry").header("Authorization", "Bearer session_2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"p1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("workbench"))
                .andExpect(jsonPath("$.entryUrl")
                        .value("/tools/detail-page-sso/platform-entry?ticket=tool_ticket_other"));
    }

    @Test
    void authorizedUserCanIssueSsoTicketDirectly() throws Exception {
        var context = new AuthContext("session_2", "u2", "legacy", "普通员工", false, 1,
                List.of("workbench:access"));
        when(permissions.requireContext("Bearer session_2")).thenReturn(context);
        when(service.issue(context, "detail-page-sso", "p1")).thenReturn(new ToolSessionService.IssuedTicket(
                "id", "tool_ticket_opaque", Instant.parse("2026-07-15T02:01:00Z")));
        mvc.perform(post("/api/tool-sessions/tickets").header("Authorization", "Bearer session_2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolKey\":\"detail-page-sso\",\"projectId\":\"p1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void platformUserIssuesTicket() throws Exception {
        var context = new AuthContext("session_1", "u1", "member", "成员", false, 1,
                List.of("workbench:access"));
        when(permissions.requireContext("Bearer session_1")).thenReturn(context);
        when(service.issue(context, "promo-image", "p1")).thenReturn(new ToolSessionService.IssuedTicket(
                "id", "tool_ticket_opaque", Instant.parse("2026-07-15T02:01:00Z")));
        mvc.perform(post("/api/tool-sessions/tickets").header("Authorization", "Bearer session_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolKey\":\"promo-image\",\"projectId\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryUrl")
                        .value("/tools/promo-image/platform-entry?ticket=tool_ticket_opaque"))
                .andExpect(jsonPath("$.ticket").doesNotExist());
    }

    @Test
    void cardEntryUsesPrivateProjectWithoutClientProjectSelection() throws Exception {
        var pilot = new AuthContext("session_1", "u1", "pilot", "试用员工", false, 1,
                List.of("workbench:access"));
        var legacy = new AuthContext("session_2", "u2", "legacy", "普通员工", false, 1,
                List.of("workbench:access"));
        when(permissions.requireContext("Bearer session_1")).thenReturn(pilot);
        when(permissions.requireContext("Bearer session_2")).thenReturn(legacy);
        when(apiController.ensureToolScopeProject(pilot)).thenReturn("tool_scope_u1");
        when(apiController.ensureToolScopeProject(legacy)).thenReturn("tool_scope_u2");
        when(service.issue(pilot, "promo-image", "tool_scope_u1")).thenReturn(new ToolSessionService.IssuedTicket(
                "id", "promo_ticket", Instant.parse("2026-07-15T02:01:00Z")));
        when(service.issue(pilot, "detail-page-sso", "tool_scope_u1")).thenReturn(new ToolSessionService.IssuedTicket(
                "id", "detail_ticket", Instant.parse("2026-07-15T02:01:00Z")));
        when(service.issue(legacy, "detail-page-sso", "tool_scope_u2")).thenReturn(new ToolSessionService.IssuedTicket(
                "id2", "detail_ticket_other", Instant.parse("2026-07-15T02:01:00Z")));

        mvc.perform(post("/api/tool-sessions/tickets").header("Authorization", "Bearer session_1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"toolKey\":\"promo-image\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryUrl").value("/tools/promo-image/platform-entry?ticket=promo_ticket"));
        mvc.perform(post("/api/tool-sessions/detail-page-entry").header("Authorization", "Bearer session_1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value("tool_scope_u1"))
                .andExpect(jsonPath("$.mode").value("workbench"));
        mvc.perform(post("/api/tool-sessions/detail-page-entry").header("Authorization", "Bearer session_2")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value("tool_scope_u2"))
                .andExpect(jsonPath("$.mode").value("workbench"));
    }

    @Test
    void serviceCredentialExchangesAndIntrospects() throws Exception {
        when(service.exchange(eq("tool_ticket_opaque"), eq("promo-image"), any())).thenReturn(
                new ToolSessionService.ExchangedSession("tool_session_opaque", "u1", "promo-image", "p1",
                        Instant.parse("2026-07-15T10:00:00Z")));
        when(service.introspect(eq("tool_session_opaque"), any())).thenReturn(
                new ToolSessionService.ToolSessionContext("u1", "member", "成员", List.of("workbench:access"),
                        "member", List.of("ai-design"), "promo-image", "p1",
                        Instant.parse("2026-07-15T10:00:00Z")));
        mvc.perform(post("/api/tool-sessions/exchange")
                        .header("Authorization", "Bearer test-service-credential")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":\"tool_ticket_opaque\",\"toolKey\":\"promo-image\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.toolSession").value("tool_session_opaque"));
        mvc.perform(post("/api/tool-sessions/introspect")
                        .header("Authorization", "Bearer test-service-credential")
                        .header("X-Tool-Session", "tool_session_opaque"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value("p1"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.name").value("成员"))
                .andExpect(jsonPath("$.role").value("member"))
                .andExpect(jsonPath("$.departmentKeys[0]").value("ai-design"))
                .andExpect(jsonPath("$.permissions").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString("tokenHash"))));
    }

    @Test
    void detailPageContextRequiresServiceCredential() throws Exception {
        when(service.detailPageContext(eq("tool_session_detail"), any())).thenReturn(
                new ToolSessionService.DetailPageContext("u1", "account", "姓名", List.of("design"),
                        List.of("admin", "member"), "detail-page-sso", "p1",
                        Instant.parse("2026-07-15T10:00:00Z")));
        mvc.perform(post("/api/tool-sessions/detail-page-context")
                        .header("Authorization", "Bearer test-service-credential")
                        .header("X-Tool-Session", "tool_session_detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u1"))
                .andExpect(jsonPath("$.roleCodes[0]").value("admin"))
                .andExpect(jsonPath("$.roleCodes[1]").value("member"))
                .andExpect(jsonPath("$.permissions").doesNotExist())
                .andExpect(jsonPath("$.departmentKeys[0]").value("design"));
        mvc.perform(post("/api/tool-sessions/detail-page-context")
                        .header("Authorization", "Bearer invalid")
                        .header("X-Tool-Session", "tool_session_detail"))
                .andExpect(status().isForbidden());
    }

    @Test
    void detailPageDirectoryRequiresServiceCredentialAndReturnsHierarchyAndMemberships() throws Exception {
        when(organizationDirectoryService.snapshot()).thenReturn(new OrganizationDirectoryService.Directory(
                Instant.parse("2026-10-22T00:00:00Z"),
                List.of(new OrganizationDirectoryService.Department("design", null, "设计部", true, 10),
                        new OrganizationDirectoryService.Department("visual", "design", "视觉组", true, 20)),
                List.of(new OrganizationDirectoryService.User("u1", "alice", "阿丽", true)),
                List.of(new OrganizationDirectoryService.Membership("u1", "visual", true)),
                List.of(new OrganizationDirectoryService.ManagerScope("u1", "DEPARTMENT", List.of("visual")))));
        mvc.perform(post("/api/tool-sessions/detail-page-directory")
                        .header("Authorization", "Bearer test-service-credential"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments[1].parentId").value("design"))
                .andExpect(jsonPath("$.users[0].userId").value("u1"))
                .andExpect(jsonPath("$.memberships[0].departmentId").value("visual"))
                .andExpect(jsonPath("$.memberships[0].primary").value(true))
                .andExpect(jsonPath("$.managerScopes[0].managedDepartmentIds[0]").value("visual"));
        mvc.perform(post("/api/tool-sessions/detail-page-directory")
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tool-sessions/detail-page-directory"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidServiceCredentialDoesNotLeakCredentialsOrOpaqueTokens() throws Exception {
        mvc.perform(post("/api/tool-sessions/exchange").header("Authorization", "Bearer wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":\"tool_ticket_secret\",\"toolKey\":\"promo-image\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(Matchers.not(Matchers.containsString("wrong-secret"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("tool_ticket_secret"))));
    }

    @Test
    void expiredTicketReturnsUnauthorizedWithoutLeakingIt() throws Exception {
        when(service.exchange(eq("tool_ticket_expired"), eq("promo-image"), any()))
                .thenThrow(new UnauthorizedException("票据无效或已过期"));
        mvc.perform(post("/api/tool-sessions/exchange")
                        .header("Authorization", "Bearer test-service-credential")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":\"tool_ticket_expired\",\"toolKey\":\"promo-image\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("tool_ticket_expired"))));
    }
}
