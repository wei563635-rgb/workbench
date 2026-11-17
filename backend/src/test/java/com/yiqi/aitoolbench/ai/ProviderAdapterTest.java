package com.yiqi.aitoolbench.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yiqi.aitoolbench.ApiController;
import com.yiqi.aitoolbench.AiGatewayService;
import com.yiqi.aitoolbench.security.SecretCrypto;
import com.yiqi.aitoolbench.relay.RelayIdentityService;
import com.yiqi.aitoolbench.sub2api.Sub2ApiProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderAdapterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private final List<CapturedRequest> capturedRequests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private SecretCrypto crypto;
    private String encryptedCredential;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        capturedRequests.clear();
        server.start();
        var masterKey = Base64.getEncoder().encodeToString(new byte[32]);
        crypto = new SecretCrypto("http", masterKey);
        encryptedCredential = crypto.encrypt("decrypted-key");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void cozeTranslatesBotRequestAndNormalizesAnswer() throws Exception {
        respond("/v3/chat", """
                {"code":0,"msg":"","data":{"id":"chat-remote","conversation_id":"conversation-remote","status":"created"}}
                """);
        respond("/v3/chat/retrieve", """
                {"code":0,"msg":"","data":{"id":"chat-remote","conversation_id":"conversation-remote",
                 "status":"completed","usage":{"token_count":12,"input_count":7,"output_count":5}}}
                """);
        respond("/v3/chat/message/list", """
                {"code":0,"msg":"","data":[{"role":"assistant","type":"answer","content":"推广文案","content_type":"text"}]}
                """);
        var adapter = new CozeProviderAdapter(HttpClient.newHttpClient(), crypto);
        var config = config("coze", "text", "coze-copywriting", "", Map.of("botId", "bot-from-options"));
        var request = request("coze-copywriting", Map.of("query", "请写文案", "conversation_id", "conversation-local"));

        var result = adapter.chat(request, config);

        var body = capturedBody(capturedRequests.get(0));
        assertThat(capturedRequests).extracting(CapturedRequest::path)
                .containsExactly("/v3/chat", "/v3/chat/retrieve", "/v3/chat/message/list");
        assertThat(capturedRequests).extracting(CapturedRequest::authorization)
                .containsOnly("Bearer decrypted-key");
        assertThat(body).containsEntry("bot_id", "bot-from-options")
                .containsEntry("user_id", "platform-tool-user")
                .containsEntry("stream", false);
        assertThat(body.get("additional_messages").toString()).contains("请写文案");
        assertThat(result.clientBody()).containsEntry("conversation_id", "conversation-remote");
        assertThat(result.clientBody().get("choices").toString()).contains("推广文案");
        assertThat(result.loggedResponse()).containsKeys("initialResponse", "completionResponse");
        assertThat(result.loggedResponse().get("initialResponse").toString()).contains("chat-remote");
        assertThat(result.loggedResponse().get("completionResponse").toString())
                .contains("token_count=12", "input_count=7", "output_count=5");
        assertThat(result.promptTokens()).isEqualTo(7);
        assertThat(result.completionTokens()).isEqualTo(5);
    }

    @Test
    void doubaoKeepsOpenAiMessagesAndSamplingParameters() throws Exception {
        respond("/chat/completions", """
                {"id":"chat-1","choices":[{"message":{"role":"assistant","content":"完成"}}],
                 "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
                """);
        var adapter = new OpenAiCompatibleProviderAdapter(HttpClient.newHttpClient(), crypto);
        var messages = List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "分析图片"),
                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AA==")))));
        var config = config("doubao-openai-compatible", "vision", "platform-alias", "", Map.of("modelId", "doubao-real-model"));
        var request = request("platform-alias", Map.of("messages", messages, "temperature", 0.35, "top_p", 0.8));

        var result = adapter.chat(request, config);

        var body = capturedBody();
        assertThat(body.get("model")).isEqualTo("doubao-real-model");
        assertThat(body.get("messages")).isEqualTo(messages);
        assertThat(body).containsEntry("temperature", 0.35).containsEntry("top_p", 0.8);
        assertThat(result.clientBody()).containsEntry("id", "chat-1");
        assertThat(result.promptTokens()).isEqualTo(7);
        assertThat(result.completionTokens()).isEqualTo(3);
    }

    @Test
    void evolinkKeepsImageParametersAndNormalizesTaskLifecycle() throws Exception {
        server.createContext("/tasks/task-9", exchange -> captureAndReply(exchange, """
                {"task_id":"task-9","status":"succeeded","progress":100,
                 "result_data":[{"url":"https://supplier/output.png"}],
                 "results":["https://supplier/output.png"]}
                """));
        respond("/images/generations", """
                {"task_id":"task-9","status":"processing"}
                """);
        var adapter = new EvolinkProviderAdapter(HttpClient.newHttpClient(), crypto);
        var config = config("evolink", "image", "platform-image", "", Map.of("modelId", "evolink-real-model"));
        var params = new LinkedHashMap<String, Object>();
        params.put("prompt", "商品海报");
        params.put("image_urls", List.of("https://files/input.png"));
        params.put("size", "1024x1024");
        params.put("quality", "high");
        params.put("n", 2);

        var created = adapter.image(request("platform-image", params), config);
        var createBody = capturedBody();
        var task = adapter.task("task-9", config);

        assertThat(createBody).containsEntry("model", "evolink-real-model")
                .containsEntry("prompt", "商品海报")
                .containsEntry("image_urls", List.of("https://files/input.png"))
                .containsEntry("size", "1024x1024")
                .containsEntry("quality", "high")
                .containsEntry("n", 2);
        assertThat(created.clientBody()).containsEntry("id", "task-9").containsEntry("status", "processing");
        assertThat(task).containsEntry("id", "task-9").containsEntry("status", "completed")
                .containsEntry("results", List.of("https://supplier/output.png"));
    }

    @Test
    void sub2apiUsesEditsForReferenceImagesAndGenerationsForTextOnlyImages() throws Exception {
        respond("/v1/images/edits", "{\"data\":[{\"b64_json\":\"AA==\"}],\"usage\":{\"input_tokens\":17,\"output_tokens\":29,\"total_tokens\":46}}");
        respond("/v1/images/generations", "{\"data\":[{\"b64_json\":\"AA==\"}]}");
        var identities = mock(RelayIdentityService.class);
        when(identities.internalCipher("test-user")).thenReturn(encryptedCredential);
        var properties = new Sub2ApiProperties("http://127.0.0.1:" + server.getAddress().getPort(), "", 5, 5);
        var adapter = new Sub2ApiProviderAdapter(identities, crypto, properties, json);
        var config = config("sub2api", "image", "gpt-image-2.5-flare", "", Map.of());
        var edit = new ApiController.AiGatewayRequest("run-1", "detail-page-sso", "test-user",
                "gpt-image-2.5-flare", Map.of("prompt", "改成蓝色", "image_urls", List.of("https://files/input.png")));
        var generation = new ApiController.AiGatewayRequest("run-2", "detail-page-sso", "test-user",
                "gpt-image-2.5-flare", Map.of("prompt", "画蓝色商品图"));

        var editResult = adapter.image(edit, config);
        adapter.image(generation, config);

        assertThat(capturedRequests).extracting(CapturedRequest::path)
                .containsExactly("/v1/images/edits", "/v1/images/generations");
        assertThat(capturedBody(capturedRequests.getFirst())).containsEntry("model", "gpt-image-2.5-flare")
                .containsEntry("images", List.of(Map.of("image_url", "https://files/input.png")))
                .doesNotContainKey("image_urls");
        assertThat(capturedBody(capturedRequests.get(1))).doesNotContainKey("images");
        assertThat(capturedRequests).extracting(CapturedRequest::authorization).containsOnly("Bearer decrypted-key");
        assertThat(editResult.promptTokens()).isEqualTo(17);
        assertThat(editResult.completionTokens()).isEqualTo(29);
        assertThat(editResult.loggedResponse().get("usage")).isEqualTo(Map.of("input_tokens",17,"output_tokens",29,"total_tokens",46));
    }

    @Test
    void sub2apiPreservesUpstreamImageErrorBodyAndStatus() {
        var upstreamBody = """
                {"error":{"code":"moderation_blocked","message":"output rejected","request_id":"req-123"}}
                """;
        respond("/v1/images/edits", 400, upstreamBody);
        var identities = mock(RelayIdentityService.class);
        when(identities.internalCipher("test-user")).thenReturn(encryptedCredential);
        var properties = new Sub2ApiProperties("http://127.0.0.1:" + server.getAddress().getPort(), "", 5, 5);
        var adapter = new Sub2ApiProviderAdapter(identities, crypto, properties, json);
        var config = config("sub2api", "image", "gpt-image-2.5-flare", "", Map.of());
        var request = new ApiController.AiGatewayRequest("run-1", "cloud-ai", "test-user",
                "gpt-image-2.5-flare", Map.of("prompt", "商品图", "image_urls", List.of("https://files/input.png")));

        assertThatThrownBy(() -> adapter.image(request, config))
                .isInstanceOfSatisfying(ProviderGatewayException.class, error -> {
                    assertThat(error.upstreamStatus()).isEqualTo(400);
                    assertThat(error.upstreamBody()).isEqualTo(upstreamBody);
                });
    }

    @Test
    void sub2apiPreservesErrorBodyEvenWhenUpstreamReturnsHttp200() {
        var upstreamBody = "{\"error\":{\"code\":\"unknown_future_error\",\"message\":\"original text\"}}";
        respond("/v1/images/generations", 200, upstreamBody);
        var identities = mock(RelayIdentityService.class);
        when(identities.internalCipher("test-user")).thenReturn(encryptedCredential);
        var properties = new Sub2ApiProperties("http://127.0.0.1:" + server.getAddress().getPort(), "", 5, 5);
        var adapter = new Sub2ApiProviderAdapter(identities, crypto, properties, json);
        var config = config("sub2api", "image", "gpt-image-2.5-flare", "", Map.of());
        var request = new ApiController.AiGatewayRequest("run-1", "cloud-ai", "test-user",
                "gpt-image-2.5-flare", Map.of("prompt", "商品图"));

        assertThatThrownBy(() -> adapter.image(request, config))
                .isInstanceOfSatisfying(ProviderGatewayException.class, error -> {
                    assertThat(error.upstreamStatus()).isEqualTo(200);
                    assertThat(error.upstreamBody()).isEqualTo(upstreamBody);
                });
    }

    @Test
    void gatewayRejectsAmbiguousProviderAdapterSelection() {
        var adapter = new EvolinkProviderAdapter(HttpClient.newHttpClient(), crypto);
        var gateway = new AiGatewayService("http", List.of(adapter, adapter));
        var config = config("evolink", "image", "platform-image", "", Map.of());

        assertThatThrownBy(() -> gateway.image(request("platform-image", Map.of("prompt", "海报")), config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("唯一适配器");
    }

    @Test
    void providerBusinessAndHttpFailuresArePropagatedWithSanitizedSemantics() {
        respond("/v3/chat", 200, """
                {"code":4101,"msg":"token pat_supplier-secret is invalid"}
                """);
        respond("/bad/chat/completions", 401, """
                {"error":{"message":"credential sk_supplier-secret rejected"}}
                """);
        respond("/evolink-error/images/generations", 200, """
                {"error":{"message":"upstream sk_supplier-secret rejected"}}
                """);
        var coze = new CozeProviderAdapter(HttpClient.newHttpClient(), crypto);
        var openAi = new OpenAiCompatibleProviderAdapter(HttpClient.newHttpClient(), crypto);
        var evolink = new EvolinkProviderAdapter(HttpClient.newHttpClient(), crypto);

        assertSanitizedFailure(() -> coze.chat(
                request("coze-copywriting", Map.of("query", "文案")),
                config("coze", "text", "coze-copywriting", "", Map.of("botId", "bot"))));
        assertSanitizedFailure(() -> openAi.chat(
                request("doubao", Map.of("messages", List.of())),
                config("doubao-openai-compatible", "text", "doubao", "/bad", Map.of())));
        assertSanitizedFailure(() -> evolink.image(
                request("image", Map.of("prompt", "海报")),
                config("evolink", "image", "image", "/evolink-error", Map.of())));
    }

    @Test
    void evolinkRejectsSuccessfulResponseWithoutTaskId() {
        respond("/images/generations", "{\"status\":\"processing\"}");
        var adapter = new EvolinkProviderAdapter(HttpClient.newHttpClient(), crypto);

        assertSanitizedFailure(() -> adapter.image(
                request("image", Map.of("prompt", "海报")),
                config("evolink", "image", "image", "", Map.of())));
    }

    private ApiController.AiGatewayRequest request(String model, Map<String, Object> params) {
        return new ApiController.AiGatewayRequest("run_1", null, null, model, params);
    }

    private AiModelConfig config(String provider, String type, String model, String exactPath, Map<String, Object> options) {
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AiModelConfig("id", model, provider, type, model, base + exactPath,
                encryptedCredential, options, 5, 0, true);
    }

    private void respond(String path, String response) {
        respond(path, 200, response);
    }

    private void respond(String path, int status, String response) {
        server.createContext(path, exchange -> captureAndReply(exchange, status, response));
    }

    private void captureAndReply(HttpExchange exchange, String response) throws IOException {
        captureAndReply(exchange, 200, response);
    }

    private void captureAndReply(HttpExchange exchange, int status, String response) throws IOException {
        captured.set(new CapturedRequest(
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        capturedRequests.add(captured.get());
        var bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Map<String, Object> capturedBody() throws Exception {
        return json.readValue(captured.get().body(), new TypeReference<>() {});
    }

    private Map<String, Object> capturedBody(CapturedRequest request) throws Exception {
        return json.readValue(request.body(), new TypeReference<>() {});
    }

    private void assertSanitizedFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOf(ProviderGatewayException.class)
                .hasMessage("AI 供应商调用失败，请稍后重试")
                .hasMessageNotContaining("supplier-secret")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("credential");
    }

    private record CapturedRequest(String path, String authorization, String body) {}
}
