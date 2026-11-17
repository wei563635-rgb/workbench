package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:project-deletion;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ProjectDeletionIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void deletingProjectKeepsRunAndUnlinkingResultOnlyRemovesAssociation() throws Exception {
        var token = login("admin", "admin123");
        var departmentId = body(get("/api/admin/organization-departments"), token).get(0).get("id").asText();
        assertThat(body(get("/api/public/stats"), null).get("enabledDepartmentCount").asLong())
                .isZero();

        var unlinkProject = createProject(token, departmentId, "解除关联验证项目");
        var unlinkRun = finishRun(token, startRun(token, unlinkProject).get("runId").asText(), "data:image/png;base64,AAAA");
        var unlinkBootstrap = body(get("/api/bootstrap"), token);
        var resultId = findByRunId(unlinkBootstrap.get("projectResults"), unlinkRun).get("id").asText();
        assertThat(unlinkBootstrap.has("projectLinks")).isFalse();
        assertThat(unlinkBootstrap.toString()).doesNotContain("detail-only-marker");
        assertThat(findByRunId(unlinkBootstrap.get("runRecords"), unlinkRun).get("hasAttachments").asBoolean()).isFalse();
        assertThat(body(get("/api/tool-runs/{runId}", unlinkRun), token).get("outputData").get("detail"))
                .isNotNull();

        mvc.perform(delete("/api/project-links/{resultId}", resultId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        var afterUnlink = body(get("/api/bootstrap"), token);
        assertThat(findByRunId(afterUnlink.get("projectResults"), unlinkRun)).isNull();
        assertThat(findByRunId(afterUnlink.get("runRecords"), unlinkRun)).isNotNull();
        assertThat(findByRunId(afterUnlink.get("projectTasks"), unlinkRun)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from tool_run where run_id=?", Integer.class, unlinkRun)).isEqualTo(1);

        var deletedProject = createProject(token, departmentId, "逻辑删除验证项目");
        var deletedRun = finishRun(token, startRun(token, deletedProject).get("runId").asText(), "/api/files/demo-download/file_1");
        assertThat(findByRunId(body(get("/api/bootstrap"), token).get("runRecords"), deletedRun)
                .get("hasAttachments").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from project_result_link where run_id=?", Integer.class, deletedRun)).isEqualTo(1);
        var projectCountBeforeDelete = body(get("/api/public/stats"), null).get("projectCount").asLong();

        mvc.perform(delete("/api/projects/{projectId}", deletedProject)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        var afterDelete = body(get("/api/bootstrap"), token);
        assertThat(body(get("/api/public/stats"), null).get("projectCount").asLong())
                .isEqualTo(projectCountBeforeDelete - 1);
        assertThat(afterDelete.get("stats").get("projectCount").asLong())
                .isEqualTo(projectCountBeforeDelete - 1);
        assertThat(findById(afterDelete.get("projects"), deletedProject)).isNull();
        assertThat(findByRunId(afterDelete.get("runRecords"), deletedRun)).isNotNull();
        assertThat(afterDelete.get("runProjects").get(deletedProject).get("name").asText()).isEqualTo("逻辑删除验证项目");
        assertThat(afterDelete.get("runProjects").get(deletedProject).get("deleted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from tool_run where run_id=?", Integer.class, deletedRun)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from project_result_link where run_id=?", Integer.class, deletedRun)).isEqualTo(1);
    }

    private String createProject(String token, String departmentId, String name) throws Exception {
        return body(post("/api/projects"), token, Map.of(
                "name", name,
                "departmentId", departmentId)).get("id").asText();
    }

    private JsonNode startRun(String token, String projectId) throws Exception {
        return body(post("/api/tool-runs/start"), token, Map.of(
                "toolKey", "market-intelligence",
                "projectId", projectId,
                "inputSummary", "验证输入"));
    }

    private String finishRun(String token, String runId, String attachmentUrl) throws Exception {
        body(post("/api/tool-runs/{runId}/finish", runId), token, Map.of(
                "status", "success",
                "outputSummary", "验证输出",
                "outputData", Map.of("detail", "detail-only-marker", "attachments", List.of(Map.of("url", attachmentUrl)))));
        return runId;
    }

    private String login(String account, String password) throws Exception {
        return body(post("/api/auth/login"), null, Map.of("account", account, "password", password))
                .get("accessToken").asText();
    }

    private JsonNode body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          String token) throws Exception {
        var response = mvc.perform(token == null ? request : request.header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private JsonNode body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          String token, Object payload) throws Exception {
        return body(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), token);
    }

    private JsonNode findByRunId(JsonNode items, String runId) {
        for (var item : items) {
            var value = item.has("runId") ? item.get("runId").asText() : "";
            if (runId.equals(value)) return item;
        }
        return null;
    }

    private JsonNode findById(JsonNode items, String id) {
        for (var item : items) {
            if (id.equals(item.get("id").asText())) return item;
        }
        return null;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
