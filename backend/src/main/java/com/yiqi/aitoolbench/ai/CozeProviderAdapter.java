package com.yiqi.aitoolbench.ai;

import com.yiqi.aitoolbench.AiGatewayService;
import com.yiqi.aitoolbench.ApiController;
import com.yiqi.aitoolbench.security.SecretCrypto;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CozeProviderAdapter extends ProviderHttpSupport {
    @Autowired
    public CozeProviderAdapter(SecretCrypto secretCrypto) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), secretCrypto);
    }

    CozeProviderAdapter(HttpClient httpClient, SecretCrypto secretCrypto) {
        super(httpClient, secretCrypto);
    }

    @Override
    public boolean supports(AiModelConfig config) {
        return "coze".equalsIgnoreCase(config.provider());
    }

    @Override
    public AiGatewayService.GatewayResult chat(ApiController.AiGatewayRequest request, AiModelConfig config) {
        var params = request.params() == null ? Map.<String, Object>of() : request.params();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("bot_id", requiredOption(config, "botId"));
        payload.put("user_id", request.userId() == null || request.userId().isBlank()
                ? "platform-tool-user" : request.userId());
        payload.put("stream", false);
        payload.put("auto_save_history", true);
        payload.put("additional_messages", List.of(Map.of(
                "role", "user", "content", requiredParam(params, "query"), "content_type", "text")));
        var conversation = option(params, "conversation_id", "");
        var startPath = "/v3/chat" + (conversation.isBlank() ? "" : "?conversation_id=" + encoded(conversation));
        var started = post(config, startPath, payload);
        requireSuccess(started.body());
        var startData = mapValue(started.body().get("data"));
        var chatId = requiredValue(startData, "id");
        var conversationId = requiredValue(startData, "conversation_id");
        var completionResult = awaitCompletion(config, chatId, conversationId);
        var details = completionResult.data();
        var messages = get(config, cozeEndpoint(config, "/v3/chat/message/list", chatId, conversationId));
        requireSuccess(messages.body());
        var answer = extractAnswer(messages.body().get("data"));
        if (answer.isBlank()) throw providerFailure();
        var usage = mapValue(details.get("usage"));
        var prompt = intValue(usage.get("input_count"));
        var completion = intValue(usage.get("output_count"));
        var total = intValue(usage.get("token_count"));
        if (prompt + completion == 0) completion = total;
        var client = new LinkedHashMap<String, Object>();
        client.put("id", chatId);
        client.put("conversation_id", conversationId);
        client.put("model", request.model());
        client.put("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", answer))));
        client.put("usage", Map.of("prompt_tokens", prompt, "completion_tokens", completion, "total_tokens", prompt + completion));
        var loggedResponse = new LinkedHashMap<String, Object>();
        loggedResponse.put("initialResponse", started.body());
        loggedResponse.put("completionResponse", completionResult.body());
        return new AiGatewayService.GatewayResult(client, loggedResponse, prompt, completion, "success");
    }

    @Override
    public AiGatewayService.GatewayResult image(ApiController.AiGatewayRequest request, AiModelConfig config) {
        throw new IllegalArgumentException("Coze 适配器不支持图片生成");
    }

    @Override
    public Map<String, Object> task(String taskId, AiModelConfig config) {
        throw new IllegalArgumentException("Coze 适配器不支持图片任务查询");
    }

    private CozeCompletionResult awaitCompletion(AiModelConfig config, String chatId, String conversationId) {
        var deadline = System.nanoTime() + Duration.ofSeconds(config.timeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            var response = get(config, cozeEndpoint(config, "/v3/chat/retrieve", chatId, conversationId));
            requireSuccess(response.body());
            var data = mapValue(response.body().get("data"));
            var status = data.getOrDefault("status", "").toString();
            if ("completed".equalsIgnoreCase(status)) return new CozeCompletionResult(response.body(), data);
            if ("failed".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)
                    || "cancelled".equalsIgnoreCase(status)) throw providerFailure();
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw providerFailure();
            }
        }
        throw providerFailure();
    }

    private record CozeCompletionResult(Map<String, Object> body, Map<String, Object> data) {}

    private String cozeEndpoint(AiModelConfig config, String path, String chatId, String conversationId) {
        return endpoint(config.apiBaseUrl(), path) + "?chat_id=" + encoded(chatId)
                + "&conversation_id=" + encoded(conversationId);
    }

    private String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void requireSuccess(Map<String, Object> body) {
        if (intValue(body.get("code")) != 0) throw providerFailure();
    }

    private String requiredParam(Map<String, Object> params, String key) {
        var value = option(params, key, "");
        if (value.isBlank()) throw new IllegalArgumentException("Coze 请求缺少 params." + key);
        return value;
    }

    private String option(Map<String, Object> values, String key, String fallback) {
        var value = values.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private String requiredValue(Map<String, Object> values, String key) {
        var value = option(values, key, "");
        if (value.isBlank()) throw providerFailure();
        return value;
    }

    private String requiredOption(AiModelConfig config, String key) {
        var value = option(config, key, "");
        if (value.isBlank()) throw new IllegalArgumentException("Coze 模型缺少 providerOptions." + key);
        return value;
    }

    private String extractAnswer(Object messages) {
        if (messages instanceof List<?> list) {
            for (var item : list) {
                var message = mapValue(item);
                if ("answer".equals(message.get("type")) && message.get("content") != null) return message.get("content").toString();
            }
        }
        return "";
    }

}
