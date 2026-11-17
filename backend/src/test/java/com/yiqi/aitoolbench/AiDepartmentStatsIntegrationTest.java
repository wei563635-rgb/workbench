package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ai-department-stats;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AiDepartmentStatsIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void countsOnlyDepartmentsWithSuccessfulAiCallsAndMergesChildrenIntoRoot() throws Exception {
        var roots = jdbc.queryForList("select id from organization_department where parent_id is null order by id", String.class);
        assertThat(roots).hasSizeGreaterThanOrEqualTo(2);
        var first = roots.get(0);
        var second = roots.get(1);
        var userId = jdbc.queryForObject("select id from sys_user where account='admin'", String.class);
        var token = json.readTree(mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("account", "admin", "password", "admin123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        jdbc.update("delete from sys_user_department where user_id=?", userId);
        jdbc.update("insert into sys_user_department values(?,?,?,?,?,?)", userId, first, true, "DEPARTMENT", 0, Instant.now().toString());

        assertThat(departmentCount()).isZero();
        jdbc.update("""
                insert into ai_call_log
                (id,run_id,tool_key,user_id,model_name,model_type,request_params,response_body,prompt_tokens,completion_tokens,total_tokens,cost_estimate,status,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, "ai-department-direct", "run-direct", "promo-image", userId, "test-model", "text", "{}", "{}", 3, 2, 5, 0, "success", Instant.now().toString());
        assertThat(departmentCount()).isEqualTo(1);

        var child = json.readTree(mvc.perform(post("/api/admin/organization-departments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "测试下级部门", "parentId", first))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
        insertRelayUsage("relay-child", child, "success", userId);
        assertThat(departmentCount()).isEqualTo(1);
        insertRelayUsage("relay-failed", second, "failed", userId);
        assertThat(departmentCount()).isEqualTo(1);
        insertRelayUsage("relay-second", second, "success", userId);
        assertThat(departmentCount()).isEqualTo(2);
    }

    private void insertRelayUsage(String id, String departmentId, String status, String userId) {
        var now = Instant.now();
        jdbc.update("""
                insert into relay_usage
                (source_id,user_id,department_id,occurred_at,day_key,month_key,input_tokens,output_tokens,cache_tokens,image_count,duration_ms,status,channel)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, userId, departmentId, now.toString(), "2026-11-06", "2026-11", 3, 2, 0, 0, 10, status, "client");
    }

    private long departmentCount() throws Exception {
        var response = mvc.perform(get("/api/public/stats"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("enabledDepartmentCount").asLong();
    }
}
