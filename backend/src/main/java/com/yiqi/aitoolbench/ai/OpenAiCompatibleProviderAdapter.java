package com.yiqi.aitoolbench.ai;

import com.yiqi.aitoolbench.AiGatewayService;
import com.yiqi.aitoolbench.ApiController;
import com.yiqi.aitoolbench.security.SecretCrypto;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleProviderAdapter extends ProviderHttpSupport {
    @Autowired
    public OpenAiCompatibleProviderAdapter(SecretCrypto secretCrypto) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), secretCrypto);
    }

    OpenAiCompatibleProviderAdapter(HttpClient httpClient, SecretCrypto secretCrypto) {
        super(httpClient, secretCrypto);
    }

    @Override
    public boolean supports(AiModelConfig config) {
        return "openai-compatible".equalsIgnoreCase(config.provider())
                || "doubao-openai-compatible".equalsIgnoreCase(config.provider());
    }

    @Override
    public AiGatewayService.GatewayResult chat(ApiController.AiGatewayRequest request, AiModelConfig config) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", option(config, "modelId", config.modelName()));
        payload.putAll(request.params() == null ? Map.of() : request.params());
        var response = requireNoBusinessError(post(config, "/chat/completions", payload));
        var body = response.body();
        var usage = mapValue(body.get("usage"));
        var prompt = intValue(usage.get("prompt_tokens"));
        var completion = intValue(usage.get("completion_tokens"));
        return new AiGatewayService.GatewayResult(body, body, prompt, completion, "success");
    }

    @Override
    public AiGatewayService.GatewayResult image(ApiController.AiGatewayRequest request, AiModelConfig config) {
        throw new IllegalArgumentException("OpenAI 兼容适配器不支持该图片生成链路");
    }

    @Override
    public Map<String, Object> task(String taskId, AiModelConfig config) {
        throw new IllegalArgumentException("OpenAI 兼容适配器不支持图片任务查询");
    }
}
