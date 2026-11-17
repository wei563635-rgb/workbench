package com.yiqi.aitoolbench.toolsession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.ApiController;
import com.yiqi.aitoolbench.LogRepository;
import com.yiqi.aitoolbench.WorkbenchRepository;
import com.yiqi.aitoolbench.file.FileAssetRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tool-api-scope;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "app.storage.local-root=target/test-tool-api-scope",
        "app.tool-sessions.service-credential=test-service-credential"
})
@AutoConfigureMockMvc
class ToolSessionApiScopeIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LogRepository logRepository;
    @Autowired private FileAssetRepository fileAssets;
    @Autowired private ApiController apiController;
    @Autowired private WorkbenchRepository workbenchRepository;
    @MockBean private ToolSessionService toolSessions;
    private String platformToken;
    private String boundProjectId;
    private String otherProjectId;

    @Test
    void detailPageDirectoryAcceptsOnlyTheServiceCredentialThroughTheFullInterceptorChain() throws Exception {
        mvc.perform(post("/api/tool-sessions/detail-page-directory")
                        .header("Authorization", "Bearer test-service-credential"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments").isArray())
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.memberships").isArray())
                .andExpect(jsonPath("$.managerScopes").isArray());
        mvc.perform(post("/api/tool-sessions/detail-page-directory")
                        .header("Authorization", "Bearer wrong"))
                .andExpect(status().isForbidden());
    }

    @BeforeEach
    void setUp() throws Exception {
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        platformToken = objectMapper.readTree(login).get("accessToken").asText();
        boundProjectId = createProject(platformToken, "绑定项目");
        otherProjectId = createProject(platformToken, "其他项目");
        when(toolSessions.introspect(eq("tool_session_scope"), any())).thenReturn(
                new ToolSessionService.ToolSessionContext(
                        "u_admin", "admin", "系统管理员", List.of("workbench:access"),
                        "promo-image", boundProjectId, Instant.parse("2026-07-16T10:00:00Z")));
    }

    @Test
    void boundProjectAndToolAreAllowedButCrossScopeStartIsForbidden() throws Exception {
        mvc.perform(post("/api/tool-runs/start")
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "toolKey", "promo-image", "userId", "spoofed", "projectId", boundProjectId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u_admin"))
                .andExpect(jsonPath("$.projectId").value(boundProjectId));

        mvc.perform(post("/api/tool-runs/start")
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "toolKey", "promo-image", "userId", "u_admin", "projectId", otherProjectId))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/tool-runs/start")
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "toolKey", "consumer-insight", "userId", "u_admin", "projectId", boundProjectId))))
                .andExpect(status().isForbidden());
    }

    @Test
    void asyncImageTaskCanOnlyBePolledByItsBoundToolSession() throws Exception {
        var runBody = mvc.perform(post("/api/tool-runs/start")
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "toolKey", "promo-image", "userId", "spoofed", "projectId", boundProjectId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var runId = objectMapper.readTree(runBody).get("runId").asText();

        mvc.perform(post("/api/ai/images/generations")
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "runId", runId, "model", "untrusted-image-alias", "params", java.util.Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("mock"));
        org.assertj.core.api.Assertions.assertThat(logRepository.findAiTaskBinding("mock").orElseThrow().modelName())
                .isEqualTo("gemini-3.1-flash-lite-image");

        mvc.perform(get("/api/ai/tasks/mock")
                        .header("Authorization", "Bearer tool_session_scope")
                        .param("modelName", "attacker-controlled-model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("mock"))
                .andExpect(jsonPath("$.model").value("gemini-3.1-flash-lite-image"));

        mvc.perform(get("/api/ai/tasks/mock")
                        .header("Authorization", "Bearer " + platformToken)
                        .param("modelName", "attacker-controlled-model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gemini-3.1-flash-lite-image"));

        when(toolSessions.introspect(eq("tool_session_other"), any())).thenReturn(
                new ToolSessionService.ToolSessionContext(
                        "u_admin", "admin", "系统管理员", List.of("workbench:access"),
                        "promo-image", otherProjectId, Instant.parse("2026-07-18T10:00:00Z")));
        mvc.perform(get("/api/ai/tasks/mock")
                        .header("Authorization", "Bearer tool_session_other"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/tasks/unknown")
                        .header("Authorization", "Bearer tool_session_scope"))
                .andExpect(status().isForbidden());

        var otherUserToken = createMemberAndLogin("task_poll_other");
        mvc.perform(get("/api/ai/tasks/mock")
                        .header("Authorization", "Bearer " + otherUserToken)
                        .param("modelName", "gemini-3.1-flash-lite-image"))
                .andExpect(status().isForbidden());
    }

    @Test
    void detailPageCallsRejectUnknownModelsInsteadOfFallingBackToDirectProviders() throws Exception {
        when(toolSessions.introspect(eq("tool_session_detail"), any())).thenReturn(
                new ToolSessionService.ToolSessionContext(
                        "u_admin", "admin", "系统管理员", List.of("workbench:access"),
                        "detail-page-sso", boundProjectId, Instant.parse("2026-07-18T10:00:00Z")));
        var runBody = mvc.perform(post("/api/tool-runs/start")
                        .header("Authorization", "Bearer tool_session_detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "toolKey", "detail-page-sso", "projectId", boundProjectId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var runId = objectMapper.readTree(runBody).get("runId").asText();

        mvc.perform(post("/api/ai/chat/completions")
                        .header("Authorization", "Bearer tool_session_detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "runId", runId, "model", "gpt-4.1", "params", java.util.Map.of()))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/ai/chat/completions")
                        .header("Authorization", "Bearer tool_session_detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "runId", runId, "model", "gpt-6-luna", "params", java.util.Map.of()))))
                .andExpect(status().isOk());
    }

    @Test
    void platformClientCanCompleteRunWithoutSendingAnyIdentityOrScopeFields() throws Exception {
        var runBody = mvc.perform(post("/api/tool-runs/start")
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputSummary\":\"制作商品推广图\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u_admin"))
                .andExpect(jsonPath("$.toolKey").value("promo-image"))
                .andExpect(jsonPath("$.projectId").value(boundProjectId))
                .andReturn().getResponse().getContentAsString();
        var runId = objectMapper.readTree(runBody).get("runId").asText();

        mvc.perform(post("/api/ai/chat/completions")
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "runId", runId,
                                "model", "gpt-4.1",
                                "params", java.util.Map.of("messages", List.of(java.util.Map.of("role", "user", "content", "生成文案")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"));

        mvc.perform(post("/api/tool-runs/{runId}/events", runId)
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"generation_started\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        var png = new MockMultipartFile("file", "input.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        var uploadBody = mvc.perform(multipart("/api/files/upload")
                        .file(png).param("runId", runId)
                        .header("Authorization", "Bearer tool_session_scope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andReturn().getResponse().getContentAsString();
        var asset = fileAssets.findActiveById(objectMapper.readTree(uploadBody).get("fileId").asText()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(asset.runId()).isEqualTo(runId);
        org.assertj.core.api.Assertions.assertThat(asset.projectId()).isEqualTo(boundProjectId);
        org.assertj.core.api.Assertions.assertThat(asset.createdBy()).isEqualTo("u_admin");
        mvc.perform(get("/api/files/demo-download/{fileId}", asset.id()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType("image/png"));

        var opaque = new MockMultipartFile("file", "untrusted.bin", "application/octet-stream",
                "<html><script>alert(1)</script></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var opaqueBody = mvc.perform(multipart("/api/files/upload")
                        .file(opaque).param("runId", runId)
                        .header("Authorization", "Bearer tool_session_scope"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var opaqueId = objectMapper.readTree(opaqueBody).get("fileId").asText();
        mvc.perform(get("/api/files/demo-download/{fileId}", opaqueId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType("application/octet-stream"));

        mvc.perform(post("/api/tool-runs/{runId}/finish", runId)
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"success\",\"outputSummary\":\"已生成\",\"outputData\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void multipleImageRunsCanStartAndFinishConcurrently() throws Exception {
        var count = 8;
        var startGate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(count);
        try {
            var starts = IntStream.range(0, count)
                    .mapToObj(index -> executor.submit(() -> {
                        startGate.await();
                        return apiController.startRun(
                                "Bearer tool_session_scope",
                                new ApiController.StartRunRequest(
                                        "promo-image", null, boundProjectId, "并发图片 " + index));
                    }))
                    .toList();
            startGate.countDown();

            var startedRuns = starts.stream()
                    .map(future -> {
                        try {
                            return future.get(15, TimeUnit.SECONDS);
                        } catch (Exception error) {
                            throw new AssertionError("并发创建运行记录失败", error);
                        }
                    })
                    .toList();
            org.assertj.core.api.Assertions.assertThat(startedRuns)
                    .extracting(ApiController.ToolRun::runId)
                    .doesNotHaveDuplicates()
                    .hasSize(count);
            org.assertj.core.api.Assertions.assertThat(workbenchRepository.loadRuns().keySet())
                    .containsAll(startedRuns.stream().map(ApiController.ToolRun::runId).toList());

            var finishGate = new CountDownLatch(1);
            var finishes = startedRuns.stream()
                    .map(run -> executor.submit(() -> {
                        finishGate.await();
                        return apiController.finishRun(
                                "Bearer tool_session_scope",
                                run.runId(),
                                new ApiController.FinishRunRequest("success", "图片生成完成", Map.of()));
                    }))
                    .toList();
            finishGate.countDown();
            for (var finish : finishes) {
                org.assertj.core.api.Assertions.assertThat(finish.get(15, TimeUnit.SECONDS).status())
                        .isEqualTo("success");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void remoteFileTransferUsesTheBoundRunAndRejectsPrivateSourceAddresses() throws Exception {
        var runBody = mvc.perform(post("/api/tool-runs/start")
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputSummary\":\"转存生成图片\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var runId = objectMapper.readTree(runBody).get("runId").asText();

        mvc.perform(post("/api/files/upload")
                        .header("Authorization", "Bearer tool_session_scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "runId", runId,
                                "fileName", "result.png",
                                "sourceUrl", "http://127.0.0.1/private.png"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("远端地址")));
    }

    private String createProject(String token, String name) throws Exception {
        var body = mvc.perform(post("/api/projects").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", name,
                                "departmentId", "org_default_a",
                                "ownerUserId", "u_admin",
                                "memberUserIds", List.of("u_admin")))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createMemberAndLogin(String account) throws Exception {
        var createdBody = mvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "account", account, "name", "任务越权测试用户", "roleIds", List.of("member")))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var temporaryPassword = objectMapper.readTree(createdBody).get("temporaryPassword").asText();
        var firstLogin = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "account", account, "password", temporaryPassword))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var temporaryToken = objectMapper.readTree(firstLogin).get("accessToken").asText();
        mvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + temporaryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "oldPassword", temporaryPassword, "newPassword", "CrossUser123"))))
                .andExpect(status().isOk());
        var login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "account", account, "password", "CrossUser123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).get("accessToken").asText();
    }
}
