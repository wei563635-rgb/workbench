package com.yiqi.aitoolbench.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.AiGatewayService;
import com.yiqi.aitoolbench.ApiController;
import com.yiqi.aitoolbench.relay.RelayIdentityService;
import com.yiqi.aitoolbench.security.SecretCrypto;
import com.yiqi.aitoolbench.sub2api.Sub2ApiProperties;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/** 显式 sub2api 模型才使用员工内部 Key，其他供应商适配器保持原行为。 */
@Component
public class Sub2ApiProviderAdapter implements AiProviderAdapter {
    private final RelayIdentityService identities;private final SecretCrypto crypto;private final Sub2ApiProperties properties;private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public Sub2ApiProviderAdapter(RelayIdentityService identities,SecretCrypto crypto,Sub2ApiProperties properties,ObjectMapper json){this.identities=identities;this.crypto=crypto;this.properties=properties;this.json=json;}
    public boolean supports(AiModelConfig config){return "sub2api".equals(config.provider());}
    public AiGatewayService.GatewayResult chat(ApiController.AiGatewayRequest request,AiModelConfig config){return invoke(request,config,"/v1/chat/completions");}
    public AiGatewayService.GatewayResult image(ApiController.AiGatewayRequest request,AiModelConfig config){
        var params=new LinkedHashMap<String,Object>(request.params()==null?Map.of():request.params());
        Object referenceImages=params.remove("image_urls");
        if(referenceImages instanceof List<?> images && !images.isEmpty()){
            var editsImages=new ArrayList<Map<String,String>>();
            for(Object image:images){
                if(!(image instanceof String url)||url.isBlank())throw new IllegalArgumentException("图生图参考图 URL 无效");
                editsImages.add(Map.of("image_url",url));
            }
            params.put("images",editsImages);
            return invoke(new ApiController.AiGatewayRequest(request.runId(),request.toolKey(),request.userId(),request.model(),params),config,"/v1/images/edits");
        }
        return invoke(new ApiController.AiGatewayRequest(request.runId(),request.toolKey(),request.userId(),request.model(),params),config,"/v1/images/generations");
    }
    public Map<String,Object> task(String taskId,AiModelConfig config){throw new IllegalArgumentException("中转异步任务查询需要绑定员工上下文");}
    public Map<String,Object> task(String taskId,AiModelConfig config,String userId){
        if(taskId==null||!taskId.matches("[A-Za-z0-9_-]{1,150}"))throw new IllegalArgumentException("任务 ID 无效");
        String key=crypto.decrypt(identities.internalCipher(userId));
        try{
            var call=HttpRequest.newBuilder(URI.create(properties.baseUrl()+"/v1/images/tasks/"+taskId)).timeout(Duration.ofSeconds(config.timeoutSeconds())).header("Authorization","Bearer "+key).GET().build();
            var response=http.send(call,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw new ProviderGatewayException(response.statusCode(),response.body());
            return json.readValue(response.body(),new TypeReference<>(){});
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new ProviderGatewayException(ex);}catch(java.io.IOException ex){throw new ProviderGatewayException(ex);}
    }
    private AiGatewayService.GatewayResult invoke(ApiController.AiGatewayRequest request,AiModelConfig config,String path){
        String key=crypto.decrypt(identities.internalCipher(request.userId()));
        var body=new LinkedHashMap<String,Object>(request.params()==null?Map.of():request.params());body.put("model",config.modelName());body.put("stream",false);
        try{
            var call=HttpRequest.newBuilder(URI.create(properties.baseUrl()+path)).timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .header("X-Workbench-Call-ID",com.yiqi.aitoolbench.usage.AiUsageCaptureFilter.currentId()).header("X-Workbench-Run-ID",request.runId()).header("X-Workbench-Tool-Key",Objects.toString(request.toolKey(),""))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(call,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw new ProviderGatewayException(response.statusCode(),response.body());
            Map<String,Object> result=json.readValue(response.body(),new TypeReference<>(){});
            if(result.containsKey("error"))throw new ProviderGatewayException(response.statusCode(),response.body());
            var logged=new LinkedHashMap<String,Object>();
            if(result.get("usage")!=null)logged.put("usage",result.get("usage"));
            logged.put("_platform",Map.of("billingSource","relay","requestId",response.headers().firstValue("X-Workbench-Request-ID").orElse("")));
            @SuppressWarnings("unchecked") var usage=result.get("usage") instanceof Map<?,?> m?(Map<String,Object>)m:Map.<String,Object>of();
            return new AiGatewayService.GatewayResult(result,logged,
                usageNumber(usage,"prompt_tokens","input_tokens"),
                usageNumber(usage,"completion_tokens","output_tokens"),"success");
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new ProviderGatewayException(ex);}catch(java.io.IOException ex){throw new ProviderGatewayException(ex);}
    }
    private int number(Object value){return value instanceof Number n?n.intValue():0;}
    private int usageNumber(Map<String,Object> usage,String primary,String fallback){
        return number(usage.get(primary) instanceof Number ? usage.get(primary) : usage.get(fallback));
    }
}
