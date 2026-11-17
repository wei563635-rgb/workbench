package com.yiqi.aitoolbench.newchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:new-chat-login;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
    "app.tool-sessions.service-credential=isolated-service",
    "app.new-chat.entry-url=https://chat.example/tools/new-chat/"
})
@AutoConfigureMockMvc
class NewChatLoginIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.yiqi.aitoolbench.tool.ToolRepository tools;
    @Autowired com.yiqi.aitoolbench.user.UserRepository users;
    private String login;

    @BeforeEach void setUp() throws Exception {
        jdbc.update("update sys_user set enabled=true where id='u_admin'");
        jdbc.update("update sys_role set enabled=true where code='admin'");
        jdbc.update("update tool_config set status='normal',availability_status='ONLINE' where tool_key='new-chat'");
        tools.replaceRoleToolPermissions("admin", java.util.stream.Stream.concat(
                tools.listRoleToolKeys("admin").stream(), java.util.stream.Stream.of("new-chat")).distinct().toList());
        tools.replaceRoleToolPermissions("member", tools.listRoleToolKeys("member").stream().filter(k -> !k.equals("new-chat")).toList());
        var body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        login = json.readTree(body).get("accessToken").asText();
    }

    private String issue() throws Exception {
        var before = jdbc.queryForObject("select count(*) from project_board", Long.class);
        var body = mvc.perform(post("/api/tool-sessions/new-chat-entry").header("Authorization", "Bearer " + login))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.projectId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertEquals(before, jdbc.queryForObject("select count(*) from project_board", Long.class));
        var url = json.readTree(body).get("entryUrl").asText();
        assertTrue(url.startsWith("https://chat.example/tools/new-chat/platform-entry?ticket="));
        return url.substring(url.indexOf("?ticket=") + 8);
    }
    private String exchange(String ticket) throws Exception {
        var body = mvc.perform(post("/api/tool-sessions/new-chat-exchange")
                .header("Authorization", "Bearer isolated-service").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ticket", ticket))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("toolSession").asText();
    }

    @Test void projectFreeLoginReturnsCompleteRolesAndRevokesWithSourceSession() throws Exception {
        jdbc.update("delete from project_member where member_user_id='u_admin'");
        var token = exchange(issue());
        mvc.perform(post("/api/tool-sessions/new-chat-context").header("Authorization", "Bearer isolated-service")
                .header("X-Tool-Session", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roleCodes", org.hamcrest.Matchers.hasItem("admin")))
                .andExpect(jsonPath("$.toolKey").value("new-chat")).andExpect(jsonPath("$.projectId").doesNotExist());
        jdbc.update("update sys_role set enabled=false where code='admin'");
        mvc.perform(post("/api/tool-sessions/new-chat-context").header("Authorization", "Bearer isolated-service")
                .header("X-Tool-Session", token)).andExpect(status().isForbidden());
        jdbc.update("update sys_role set enabled=true where code='admin'");
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + login)).andExpect(status().isOk());
        mvc.perform(post("/api/tool-sessions/new-chat-context").header("Authorization", "Bearer isolated-service")
                .header("X-Tool-Session", token)).andExpect(status().isUnauthorized());
    }

    @Test void wrongCredentialsWrongToolAndExpiredOrReplayedTicketsAreRejected() throws Exception {
        var ticket = issue();
        mvc.perform(post("/api/tool-sessions/new-chat-exchange").header("Authorization", "Bearer wrong")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ticket", ticket))))
                .andExpect(status().isForbidden());
        var token = exchange(ticket);
        mvc.perform(post("/api/tool-sessions/new-chat-exchange").header("Authorization", "Bearer isolated-service")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ticket", ticket))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tool-sessions/new-chat-context").header("Authorization", "Bearer isolated-service")
                .header("X-Tool-Session", "tool_session_other")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        var expired = issue();
        jdbc.update("update new_chat_login_ticket set expires_at='2000-01-01T00:00:00Z' where consumed_at is null");
        mvc.perform(post("/api/tool-sessions/new-chat-exchange").header("Authorization", "Bearer isolated-service")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ticket", expired))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tool-sessions/new-chat-entry")).andExpect(status().isUnauthorized());
    }

    @Test void disabledUserCannotContinueOrExchangeAnOutstandingTicket() throws Exception {
        var token = exchange(issue());
        var ticket = issue();
        jdbc.update("update sys_user set enabled=false where id='u_admin'");
        mvc.perform(post("/api/tool-sessions/new-chat-context").header("Authorization", "Bearer isolated-service")
                .header("X-Tool-Session", token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tool-sessions/new-chat-exchange").header("Authorization", "Bearer isolated-service")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ticket", ticket))))
                .andExpect(status().isUnauthorized());
    }

    @Test void concurrentExchangeSucceedsOnlyOnceAndStoredCredentialsAreHashed() throws Exception {
        var ticket = issue();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var task = (java.util.concurrent.Callable<Integer>) () -> mvc.perform(post("/api/tool-sessions/new-chat-exchange")
                .header("Authorization", "Bearer isolated-service").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ticket", ticket))))
                .andReturn().getResponse().getStatus();
            var a = pool.submit(task); var b = pool.submit(task);
            var statuses = java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(s -> s == 200).count());
            assertEquals(1, statuses.stream().filter(s -> s == 401).count());
            assertTrue(jdbc.queryForList("select token_hash from new_chat_login_ticket").stream()
                    .allMatch(row -> row.get("token_hash").toString().matches("[0-9a-f]{64}")));
        } finally { pool.shutdownNow(); }
    }

    @Test void onlyAdminIsInitiallyGrantedAndRoleEditorKeepsToolAfterRevocation() throws Exception {
        assertTrue(tools.listRoleToolKeys("admin").contains("new-chat"));
        assertFalse(tools.listRoleToolKeys("member").contains("new-chat"));
        assertFalse(tools.listRoleToolKeys("department_leader").contains("new-chat"));
        tools.replaceRoleToolPermissions("admin", tools.listRoleToolKeys("admin").stream().filter(k -> !k.equals("new-chat")).toList());
        var body = mvc.perform(get("/api/bootstrap").header("Authorization", "Bearer " + login))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var root = json.readTree(body);
        assertTrue(java.util.stream.StreamSupport.stream(root.get("roleTools").spliterator(), false)
                .anyMatch(t -> t.get("key").asText().equals("new-chat")));
        assertFalse(java.util.stream.StreamSupport.stream(root.get("tools").spliterator(), false)
                .anyMatch(t -> t.get("key").asText().equals("new-chat")));
        mvc.perform(post("/api/tool-sessions/new-chat-entry").header("Authorization", "Bearer " + login))
                .andExpect(status().isForbidden());
        // 模拟重启时重新初始化目录，不能恢复管理员主动撤销的授权。
        new com.yiqi.aitoolbench.tool.ToolRepository(jdbc, "https://detail.example/", "");
        assertFalse(tools.listRoleToolKeys("admin").contains("new-chat"));
        assertFalse(tools.listRoleToolKeys("member").contains("new-chat"));
    }

    @Test void memberCanEnterOnlyAfterGrantAndRevocationBlocksOutstandingTicketsAndSessions() throws Exception {
        var account = "chat-" + java.util.UUID.randomUUID();
        var userId = users.createUser(account, "权限测试", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("TestChat123"), false);
        users.addUserRole(userId, "member");
        var body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account", account, "password", "TestChat123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        login = json.readTree(body).get("accessToken").asText();
        mvc.perform(post("/api/tool-sessions/new-chat-entry").header("Authorization", "Bearer " + login))
                .andExpect(status().isForbidden());
        tools.replaceRoleToolPermissions("member", java.util.stream.Stream.concat(
                tools.listRoleToolKeys("member").stream(), java.util.stream.Stream.of("new-chat")).toList());
        var session = exchange(issue());
        var ticket = issue();
        mvc.perform(post("/api/tool-sessions/new-chat-context").header("Authorization", "Bearer isolated-service")
                .header("X-Tool-Session", session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roleCodes", org.hamcrest.Matchers.hasItem("member")));
        var bootstrap = json.readTree(mvc.perform(get("/api/bootstrap").header("Authorization", "Bearer " + login))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFalse(bootstrap.has("roleTools"));
        assertTrue(java.util.stream.StreamSupport.stream(bootstrap.get("tools").spliterator(), false)
                .anyMatch(t -> t.get("key").asText().equals("new-chat")));
        for (var department : bootstrap.get("departments")) for (var tool : department.get("tools"))
            assertNotEquals("new-chat", tool.get("key").asText());
        tools.replaceRoleToolPermissions("member", tools.listRoleToolKeys("member").stream().filter(k -> !k.equals("new-chat")).toList());
        mvc.perform(post("/api/tool-sessions/new-chat-entry").header("Authorization", "Bearer " + login))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tool-sessions/new-chat-exchange").header("Authorization", "Bearer isolated-service")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ticket", ticket))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tool-sessions/new-chat-context").header("Authorization", "Bearer isolated-service")
                .header("X-Tool-Session", session)).andExpect(status().isForbidden());
    }

    @Test void unavailableToolBlocksExistingSessionAndNewEntry() throws Exception {
        var token = exchange(issue());
        jdbc.update("update tool_config set status='maintenance',availability_status='MAINTENANCE' where tool_key='new-chat'");
        mvc.perform(post("/api/tool-sessions/new-chat-entry").header("Authorization", "Bearer " + login))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tool-sessions/new-chat-context").header("Authorization", "Bearer isolated-service")
                .header("X-Tool-Session", token)).andExpect(status().isForbidden());
    }

}
