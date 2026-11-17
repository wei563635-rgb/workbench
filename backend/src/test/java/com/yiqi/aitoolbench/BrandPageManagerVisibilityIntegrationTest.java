package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:brand-page-manager-visibility;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "app.storage.local-root=target/test-brand-page-manager-visibility"
})
@AutoConfigureMockMvc
class BrandPageManagerVisibilityIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void managerSeesAndPreviewsAllBrandPagesFromRegularWorkbenchRoute() throws Exception {
        var adminToken = login("admin", "admin123");
        var departments = request(get("/api/admin/organization-departments"), adminToken);
        var departmentId = departments.get(0).get("id").asText();
        var html = "<html><body>manager-visible</body></html>";
        var viewer = request(post("/api/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of(
                        "account", "brand_page_viewer",
                        "name", "品牌页面查看者",
                        "roleIds", java.util.List.of("member"),
                        "departmentKeys", java.util.List.of(departmentId),
                        "primaryDepartmentId", departmentId))), adminToken);

        var created = json.readTree(mvc.perform(multipart("/api/admin/brand-pages")
                        .file(new MockMultipartFile("file", "manager-visible.html",
                                MediaType.TEXT_HTML_VALUE, html.getBytes(StandardCharsets.UTF_8)))
                        .param("brandName", "管理员普通入口可见")
                        .param("departmentId", departmentId)
                        .param("viewerUserIds", viewer.get("id").asText())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        jdbc.update("update brand_html_page set created_by=? where id=?",
                viewer.get("id").asText(), created.get("id").asText());

        var pages = request(get("/api/brand-pages").param("keyword", "管理员普通入口可见"), adminToken);
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).get("id").asText()).isEqualTo(created.get("id").asText());

        mvc.perform(get("/api/brand-pages/{pageId}/content", created.get("id").asText())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
    }

    private String login(String account, String password) throws Exception {
        var response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("account", account, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response).get("accessToken").asText();
    }

    private JsonNode request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                             String token) throws Exception {
        var response = mvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
