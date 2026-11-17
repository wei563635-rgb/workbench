package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cloud-ai-entry;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "app.cloud-ai.entry-url=https://example.test/",
        "app.tool-sessions.service-credential=test-service"
})
@AutoConfigureMockMvc
class CloudAiToolEntryIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void cloudToolIssuesProjectScopedOneTimeTicketWithoutProjectPicker() throws Exception {
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var accessToken = json.readTree(login).get("accessToken").asText();
        var bootstrap = mvc.perform(get("/api/bootstrap").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var tools = json.readTree(bootstrap).get("tools");
        assertThat(tools).anySatisfy(tool -> {
            assertThat(tool.get("key").asText()).isEqualTo("promo-image");
            assertThat(tool.get("entryUrl").asText()).isEqualTo("https://example.test/");
        });
        assertThat(tools).noneSatisfy(tool -> assertThat(tool.get("key").asText()).isEqualTo("cloud-ai"));
        var issued = mvc.perform(post("/api/tool-sessions/tickets")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"toolKey\":\"cloud-ai\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var entry = json.readTree(issued).get("entryUrl").asText();
        assertThat(entry).startsWith("/tools/cloud-ai/platform-entry?ticket=");
        var ticket = entry.substring(entry.indexOf("ticket=") + 7);
        var exchanged = mvc.perform(post("/api/tool-sessions/exchange")
                .header("Authorization", "Bearer test-service")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("ticket", ticket, "toolKey", "cloud-ai"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var value = json.readTree(exchanged);
        assertThat(value.get("toolKey").asText()).isEqualTo("cloud-ai");
        assertThat(value.get("projectId").asText()).isNotBlank();
        mvc.perform(post("/api/tool-sessions/exchange")
                .header("Authorization", "Bearer test-service")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("ticket", ticket, "toolKey", "cloud-ai"))))
                .andExpect(status().is4xxClientError());
    }
}
