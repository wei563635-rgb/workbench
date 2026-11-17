package com.yiqi.aitoolbench.ai;

import com.yiqi.aitoolbench.AiGatewayService;
import com.yiqi.aitoolbench.ApiController;
import java.util.Map;

public interface AiProviderAdapter {
    boolean supports(AiModelConfig config);

    AiGatewayService.GatewayResult chat(ApiController.AiGatewayRequest request, AiModelConfig config);

    AiGatewayService.GatewayResult image(ApiController.AiGatewayRequest request, AiModelConfig config);

    Map<String, Object> task(String taskId, AiModelConfig config);
    default Map<String,Object> task(String taskId,AiModelConfig config,String userId){return task(taskId,config);}
}
