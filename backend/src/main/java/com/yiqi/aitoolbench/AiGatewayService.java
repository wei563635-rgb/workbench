package com.yiqi.aitoolbench;

import com.yiqi.aitoolbench.ai.AiModelConfig;
import com.yiqi.aitoolbench.ai.AiProviderAdapter;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiGatewayService {
    private final String mode;
    private final List<AiProviderAdapter> adapters;

    public AiGatewayService(@Value("${app.ai.gateway-mode:mock}") String mode, List<AiProviderAdapter> adapters) {
        this.mode = mode;
        this.adapters = List.copyOf(adapters);
    }

    public GatewayResult chat(ApiController.AiGatewayRequest request, AiModelConfig config) {
        if (!"http".equalsIgnoreCase(mode)) {
            var response = Map.<String, Object>of("content", "这是统一 AI 网关的演示返回。");
            var clientBody = Map.<String, Object>of(
                    "id", "mock",
                    "model", request.model(),
                    "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", response.get("content")))),
                    "usage", Map.of("promptTokens", 128, "completionTokens", 256, "totalTokens", 384));
            return new GatewayResult(clientBody, response, 128, 256, "success");
        }
        captureModel(config);
        try{var result=adapter(config).chat(request, config);captureUsage(result);return result;}catch(com.yiqi.aitoolbench.ai.ProviderGatewayException e){captureFailure(e);throw e;}
    }

    public GatewayResult image(ApiController.AiGatewayRequest request, AiModelConfig config) {
        if (!"http".equalsIgnoreCase(mode)) {
            var response = Map.<String, Object>of("images", List.of("/demo/generated-image.png"));
            var clientBody = Map.<String, Object>of("id", "mock", "model", request.model(), "data", List.of(Map.of("url", "/demo/generated-image.png")));
            return new GatewayResult(clientBody, response, 0, 0, "success");
        }
        captureModel(config);
        try{var result=adapter(config).image(request, config);captureUsage(result);return result;}catch(com.yiqi.aitoolbench.ai.ProviderGatewayException e){captureFailure(e);throw e;}
    }

    public Map<String, Object> task(String taskId, AiModelConfig config) {
        if (!"http".equalsIgnoreCase(mode)) {
            return Map.of(
                    "id", taskId,
                    "model", config == null ? "mock-image-model" : config.modelName(),
                    "object", "image.generation.task",
                    "progress", 100,
                    "status", "completed",
                    "results", List.of("/demo/generated-image.png"),
                    "type", "image");
        }
        return adapter(config).task(taskId, config);
    }

    private void captureUsage(GatewayResult result){
        Map<String,Object> response=result.loggedResponse()==null?Map.of():result.loggedResponse();
        Object usage=response.get("usage");
        if(response.get("completionResponse") instanceof Map<?,?> completion && completion.get("data") instanceof Map<?,?> data)usage=data.get("usage");
        com.yiqi.aitoolbench.usage.AiUsageCaptureFilter.annotate(Map.of("actualUsage",usage instanceof Map<?,?>?usage:Map.of(),"supplierResponse",response));
    }
    private void captureFailure(com.yiqi.aitoolbench.ai.ProviderGatewayException e){
        if(e.upstreamStatus()==null)com.yiqi.aitoolbench.usage.AiUsageCaptureFilter.annotate(Map.of("resultStatus","unknown"));
    }
    private void captureModel(AiModelConfig config) {
        if(config!=null)com.yiqi.aitoolbench.usage.AiUsageCaptureFilter.annotate(Map.of("provider",config.provider(),"modelType",config.modelType(),"model",config.modelName()));
    }
    private AiProviderAdapter adapter(AiModelConfig config) {
        if (config == null || !config.enabled()) {
            throw new IllegalArgumentException("模型配置不存在或未启用");
        }
        var matches = adapters.stream().filter(candidate -> candidate.supports(config)).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("模型供应商必须匹配唯一适配器: " + config.provider());
        }
        return matches.get(0);
    }
    public Map<String,Object> task(String taskId,AiModelConfig config,String userId){
        if(!"http".equalsIgnoreCase(mode))return task(taskId,config);
        return adapter(config).task(taskId,config,userId);
    }

    public record GatewayResult(Map<String, Object> clientBody, Map<String, Object> loggedResponse, int promptTokens, int completionTokens, String status) {}
}
