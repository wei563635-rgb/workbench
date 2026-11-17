package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:project-permissions;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ProjectPermissionIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void enforcesDepartmentScopeAndExplicitProjectMembership() throws Exception {
        var adminToken = login("admin", "admin123");
        var departments = body(get("/api/admin/organization-departments"), adminToken);
        var departmentA = departments.get(0).get("id").asText();
        var departmentB = departments.get(1).get("id").asText();

        var owner = createUser(adminToken, "project_owner", "项目负责人",
                "department_leader", departmentA);
        var departmentLeader = createUser(adminToken, "department_observer", "部门观察者",
                "department_leader", departmentA);
        var sameDepartmentMember = createUser(adminToken, "same_department_member", "同部门普通成员",
                "member", departmentA);
        var crossDepartmentMember = createUser(adminToken, "cross_department_member", "跨部门项目成员",
                "member", departmentB);

        var ownerToken = activateAndLogin(owner);
        var leaderToken = activateAndLogin(departmentLeader);
        var sameDepartmentToken = activateAndLogin(sameDepartmentMember);
        var crossDepartmentToken = activateAndLogin(crossDepartmentMember);

        var project = body(post("/api/projects"), adminToken, Map.of(
                "name", "权限集成项目",
                "departmentId", departmentA,
                "ownerUserId", owner.get("id").asText(),
                "memberUserIds", List.of(owner.get("id").asText(), crossDepartmentMember.get("id").asText())));
        var projectId = project.get("id").asText();

        assertThat(projectIds(body(get("/api/projects"), ownerToken))).contains(projectId);
        assertThat(projectIds(body(get("/api/projects"), leaderToken))).contains(projectId);
        assertThat(projectIds(body(get("/api/projects"), sameDepartmentToken))).doesNotContain(projectId);
        assertThat(projectIds(body(get("/api/projects"), crossDepartmentToken))).contains(projectId);

        mvc.perform(post("/api/projects/{projectId}/status", projectId)
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"已完成\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/projects/{projectId}/status", projectId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"已完成\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode createUser(
            String adminToken, String account, String name, String roleCode, String departmentId) throws Exception {
        return body(post("/api/admin/users"), adminToken, Map.of(
                "account", account,
                "name", name,
                "roleIds", List.of(roleCode),
                "departmentKeys", List.of(departmentId),
                "primaryDepartmentId", departmentId));
    }

    private String activateAndLogin(JsonNode created) throws Exception {
        var temporaryToken = login(created.get("account").asText(), created.get("temporaryPassword").asText());
        mvc.perform(post("/api/auth/change-password")
                        .header("Authorization", bearer(temporaryToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "oldPassword", created.get("temporaryPassword").asText(),
                                "newPassword", "ProjectAccess123"))))
                .andExpect(status().isOk());
        return login(created.get("account").asText(), "ProjectAccess123");
    }

    private String login(String account, String password) throws Exception {
        var response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("account", account, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response).get("accessToken").asText();
    }

    private JsonNode body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          String token) throws Exception {
        var response = mvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private JsonNode body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          String token, Object payload) throws Exception {
        var response = mvc.perform(request.header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private List<String> projectIds(JsonNode projects) {
        return java.util.stream.StreamSupport.stream(projects.spliterator(), false)
                .map(item -> item.get("id").asText())
                .toList();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
