package com.yiqi.aitoolbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user-management;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApiControllerUserManagementTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkbenchRepository workbenchRepository;

    @Test
    void importedChineseAccountUsesRequestedInitialPasswordAndRequiresChange() throws Exception {
        var token = login();
        var departments = json(mockMvc.perform(get("/api/admin/organization-departments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        var departmentId = departments.get(0).get("id").asText();
        var created = json(mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "account", "导入测试", "name", "导入测试", "roleIds", java.util.List.of("member"),
                                "departmentKeys", java.util.List.of(departmentId),
                                "primaryDepartmentId", departmentId,
                                "initialPassword", "导入测试123456"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(created.get("mustChangePassword").asBoolean()).isTrue();
        var loggedIn = json(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"导入测试\",\"password\":\"导入测试123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(loggedIn.get("mustChangePassword").asBoolean()).isTrue();
    }

    @Test
    void editsLegacyUserWhenAuthenticationAndBusinessIdsDiffer() throws Exception {
        String token = login();
        JsonNode departments = json(mockMvc.perform(get("/api/admin/organization-departments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String departmentId = departments.get(0).get("id").asText();

        JsonNode updated = json(mockMvc.perform(put("/api/admin/users/u_admin")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", "系统管理员",
                                "roleIds", java.util.List.of("admin"),
                                "departmentKeys", java.util.List.of(departmentId),
                                "primaryDepartmentId", departmentId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(updated.get("id").asText()).isEqualTo("u_admin");
        assertThat(updated.get("departmentKeys")).extracting(JsonNode::asText).containsExactly(departmentId);
        assertThat(workbenchRepository.loadUsers()).containsKey("u_admin");
        assertThat(workbenchRepository.loadUsers().get("u_admin").departmentKeys()).containsExactly(departmentId);
    }

    @Test
    void createsAndEditsUserWithMultipleRolesDepartmentsAndPrimaryDepartment() throws Exception {
        String token = login();
        JsonNode departments = json(mockMvc.perform(get("/api/admin/organization-departments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String firstDepartment = departments.get(0).get("id").asText();
        String secondDepartment = departments.get(1).get("id").asText();

        JsonNode created = json(mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account":"integration_user","name":"集成测试用户","roleIds":["member"]}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(created.get("temporaryPassword").asText()).isNotBlank();
        assertThat(created.get("primaryDepartmentId").asText()).isEqualTo(firstDepartment);

        String userId = created.get("id").asText();
        JsonNode updated = json(mockMvc.perform(put("/api/admin/users/{userId}", userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", "编辑后的用户",
                                "roleIds", java.util.List.of("member", "auditor"),
                                "departmentKeys", java.util.List.of(firstDepartment, secondDepartment),
                                "primaryDepartmentId", secondDepartment))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(updated.get("name").asText()).isEqualTo("编辑后的用户");
        assertThat(updated.get("roleIds")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("member", "auditor");
        assertThat(updated.get("departmentKeys")).extracting(JsonNode::asText)
                .containsExactly(secondDepartment, firstDepartment);
        assertThat(updated.get("primaryDepartmentId").asText()).isEqualTo(secondDepartment);

        JsonNode legacyCreated = json(mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "account", "legacy_department_user",
                                "name", "旧请求用户",
                                "roleIds", java.util.List.of("member"),
                                "departmentKeys", java.util.List.of(firstDepartment)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(legacyCreated.get("primaryDepartmentId").asText()).isEqualTo(firstDepartment);

        mockMvc.perform(post("/api/admin/organization-departments/{departmentId}/status", secondDepartment)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        JsonNode preservedDisabled = json(mockMvc.perform(put("/api/admin/users/{userId}", userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", "禁用部门仍可保留",
                                "roleIds", java.util.List.of("member", "auditor"),
                                "departmentKeys", java.util.List.of(secondDepartment, firstDepartment),
                                "primaryDepartmentId", secondDepartment))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(preservedDisabled.get("primaryDepartmentId").asText()).isEqualTo(secondDepartment);

        JsonNode page = json(mockMvc.perform(get("/api/admin/users?page=1&pageSize=1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(3);

        JsonNode byAccount = json(mockMvc.perform(get("/api/admin/users?page=1&pageSize=1&search=INTEGRATION_USER")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(byAccount.get("total").asLong()).isEqualTo(1);
        assertThat(byAccount.get("items").get(0).get("id").asText()).isEqualTo(userId);

        JsonNode byName = json(mockMvc.perform(get("/api/admin/users?page=1&pageSize=1&search=禁用部门")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(byName.get("total").asLong()).isEqualTo(1);
        assertThat(byName.get("items").get(0).get("id").asText()).isEqualTo(userId);

        JsonNode literalWildcard = json(mockMvc.perform(get("/api/admin/users?page=1&pageSize=10&search=%25")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(literalWildcard.get("total").asLong()).isZero();

        JsonNode databasePage = json(mockMvc.perform(get("/api/admin/users?page=1&pageSize=100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode persisted = java.util.stream.StreamSupport.stream(databasePage.get("items").spliterator(), false)
                .filter(item -> userId.equals(item.get("id").asText()))
                .findFirst().orElseThrow();
        assertThat(persisted.get("name").asText()).isEqualTo("禁用部门仍可保留");
        assertThat(persisted.get("roleIds")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("member", "auditor");

        JsonNode legacyUsers = json(mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(legacyUsers.isArray()).isTrue();

        JsonNode pagedLogs = json(mockMvc.perform(get("/api/admin/operation-logs?page=1&pageSize=2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(pagedLogs.get("items").size()).isLessThanOrEqualTo(2);
        assertThat(pagedLogs.get("total").asLong()).isGreaterThanOrEqualTo(pagedLogs.get("items").size());
    }

    @Test
    void logicallyDeletesUserAndRejectsFutureLogin() throws Exception {
        String token = login();
        JsonNode created = json(mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account":"deleted_user","name":"待删除用户","roleIds":["member"]}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        mockMvc.perform(delete("/api/admin/users/{userId}", created.get("id").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode users = json(mockMvc.perform(get("/api/admin/users?page=1&pageSize=100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(users.get("items")).noneMatch(item -> "deleted_user".equals(item.get("account").asText()));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "account", "deleted_user",
                                "password", created.get("temporaryPassword").asText()))))
                .andExpect(status().isBadRequest());

        JsonNode reused = json(mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account":"deleted_user","name":"新用户","roleIds":["member"]}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(reused.get("id").asText()).isNotEqualTo(created.get("id").asText());
        assertThat(workbenchRepository.loadUsers()).containsKey(reused.get("id").asText())
                .doesNotContainKey(created.get("id").asText());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "account", "deleted_user",
                                "password", reused.get("temporaryPassword").asText()))))
                .andExpect(status().isOk());
    }

    @Test
    void logicallyDeletesLeafDepartmentAndExcludesItFromTree() throws Exception {
        String token = login();
        JsonNode created = json(mockMvc.perform(post("/api/admin/organization-departments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"待删除部门\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        mockMvc.perform(delete("/api/admin/organization-departments/{departmentId}", created.get("id").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode departments = json(mockMvc.perform(get("/api/admin/organization-departments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(departments).noneMatch(item -> "待删除部门".equals(item.get("name").asText()));
    }

    private String login() throws Exception {
        JsonNode login = json(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        return login.get("accessToken").asText();
    }

    private JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }
}
