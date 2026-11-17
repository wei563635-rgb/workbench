package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.auth.AuthContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:hidden-tool-project;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class HiddenToolProjectIntegrationTest {
    @Autowired private ApiController api;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void personalProjectIsRunnableAndAbsentFromProjectUi() throws Exception {
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var token = json.readTree(login).get("accessToken").asText();
        var context = new AuthContext(token, "u_admin", "admin", "系统管理员", false, 1, List.of());
        var before = json.readTree(mvc.perform(get("/api/public/stats"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("projectCount").asLong();

        var projectId = api.ensureToolScopeProject(context);
        assertThat(api.ensureToolScopeProject(context)).isEqualTo(projectId);
        assertThat(jdbc.queryForObject("select count(*) from project_member where project_id=? and member_user_id=?",
                Integer.class, projectId, "u_admin")).isEqualTo(1);
        assertThat(json.readTree(mvc.perform(get("/api/projects").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).toString())
                .doesNotContain(projectId);
        var after = json.readTree(mvc.perform(get("/api/public/stats"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("projectCount").asLong();
        assertThat(after).isEqualTo(before);

        mvc.perform(post("/api/tool-runs/start").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("toolKey", "detail-page", "projectId", projectId))))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        var projects = (List<ApiController.ProjectBoard>) ReflectionTestUtils.getField(api, "projects");
        assertThat(projects).isNotNull();
        var index = java.util.stream.IntStream.range(0, projects.size())
                .filter(i -> projects.get(i).id().equals(projectId))
                .findFirst()
                .orElseThrow();
        var corrupted = projects.get(index)
                .withContent("个人工具运行", "错误负责人", List.of("错误负责人"),
                        "工具入口运行范围", projects.get(index).details())
                .withAccessMetadata(projects.get(index).departmentId(), "u_wrong", List.of("u_wrong"), "u_admin");
        projects.set(index, corrupted);
        jdbc.update("update project_board set owner=?, owner_user_id=? where id=?", "错误负责人", "u_wrong", projectId);

        assertThat(api.ensureToolScopeProject(context)).isEqualTo(projectId);
        assertThat(jdbc.queryForObject("select owner_user_id from project_board where id=?", String.class, projectId))
                .isEqualTo("u_admin");
        assertThat(jdbc.queryForObject("select count(*) from project_member where project_id=? and member_user_id=?",
                Integer.class, projectId, "u_admin")).isEqualTo(1);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/projects/{projectId}", projectId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }
}
