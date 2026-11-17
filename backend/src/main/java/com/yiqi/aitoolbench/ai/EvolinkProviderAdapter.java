package com.yiqi.aitoolbench.ai;

import com.yiqi.aitoolbench.AiGatewayService;
import com.yiqi.aitoolbench.ApiController;
import com.yiqi.aitoolbench.security.SecretCrypto;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class EvolinkProviderAdapter extends ProviderHttpSupport {
    @Autowired
    public EvolinkProviderAdapter(SecretCrypto secretCrypto) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), secretCrypto);
    }

    EvolinkProviderAdapter(HttpClient httpClient, SecretCrypto secretCrypto) {
        super(httpClient, secretCrypto);
    }

    @Override
    public boolean supports(AiModelConfig config) {
        return "evolink".equalsIgnoreCase(config.provider());
    }

    @Override
    public AiGatewayService.GatewayResult chat(ApiController.AiGatewayRequest request, AiModelConfig config) {
        throw new IllegalArgumentException("Evolink 适配器不支持对话");
    }

    @Override
    public AiGatewayService.GatewayResult image(ApiController.AiGatewayRequest request, AiModelConfig config) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", option(config, "modelId", config.modelName()));
        payload.putAll(request.params() == null ? Map.of() : request.params());
        var response = requireNoBusinessError(post(config, "/images/generations", payload));
        var body = response.body();
        var taskId = first(body, "id", "task_id", "taskId");
        if (taskId == null) throw providerFailure();
        var client = new LinkedHashMap<>(body);
        if (taskId != null) client.put("id", taskId);
        client.putIfAbsent("model", request.model());
        return new AiGatewayService.GatewayResult(client, body, 0, 0, "success");
    }

    @Override
    public Map<String, Object> task(String taskId, AiModelConfig config) {
        var base = config.apiBaseUrl();
        var marker = "/images/generations";
        if (base.endsWith(marker)) base = base.substring(0, base.length() - marker.length());
        var response = requireNoBusinessError(get(config, endpoint(base, "/tasks/" + taskId)));
        var body = response.body();
        var client = new LinkedHashMap<String, Object>();
        client.put("id", first(body, "id", "task_id", "taskId") == null ? taskId : first(body, "id", "task_id", "taskId"));
        client.put("status", normalizeStatus(body.get("status")));
        client.put("progress", body.getOrDefault("progress", 0));
        client.put("results", extractResults(body));
        client.put("type", "image");
        return client;
    }

    private String first(Map<String, Object> value, String... keys) {
        for (var key : keys) if (value.get(key) != null && !value.get(key).toString().isBlank()) return value.get(key).toString();
        return null;
    }

    private String normalizeStatus(Object value) {
        var status = value == null ? "unknown" : value.toString().toLowerCase();
        return switch (status) {
            case "succeeded", "success", "done" -> "completed";
            case "failed", "error" -> "failed";
            case "queued", "pending" -> "pending";
            default -> status;
        };
    }

    private List<String> extractResults(Map<String, Object> body) {
        var results = new ArrayList<String>();
        var data = mapValue(body.get("data"));
        appendResults(results, body.get("results"));
        if (results.isEmpty()) appendResults(results, body.get("result_data"));
        if (results.isEmpty()) appendResults(results, data.get("images"));
        if (results.isEmpty()) appendResults(results, body.get("images"));
        return List.copyOf(results);
    }

    private void appendResults(List<String> results, Object images) {
        if (images instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof String text) results.add(text);
                else {
                    var image = mapValue(item);
                    if (image.get("url") != null) results.add(image.get("url").toString());
                }
            }
        }
    }
}
