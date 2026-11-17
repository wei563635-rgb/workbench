package com.yiqi.aitoolbench.usage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.sub2api.Sub2ApiClient;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** One index for calls, separate from quota facts. Imports are bounded and idempotent. */
@Service
public class AiUsageSync {
    private final AiUsageRepository records;private final Sub2ApiClient client;private final ObjectMapper json;
    public AiUsageSync(AiUsageRepository records,Sub2ApiClient client,ObjectMapper json){this.records=records;this.client=client;this.json=json;
        records.jdbc().execute("create table if not exists ai_usage_legacy_seen(kind varchar(20) not null,id varchar(120) not null,primary key(kind,id))");
        records.jdbc().execute("create table if not exists ai_usage_task(task_id varchar(180) primary key,record_id varchar(180) not null,user_id varchar(80) not null)");
    }
    @Scheduled(initialDelay=5000,fixedDelay=60000)
    public synchronized void synchronize(){
        try{
            importLegacy();
            if(Boolean.TRUE.equals(client.configurationStatus().get("configured"))) {
                var capabilities=client.workbenchGet("/capabilities");
                if(capabilities.path("callRecords").asBoolean()) {
                    String previous=Objects.toString(records.syncState().get("last_sync"),"");
                    Instant until=Instant.now();Instant from=previous.isBlank()?until.minus(Duration.ofDays(180)):Instant.parse(previous).minus(Duration.ofHours(2));
                    String cursor="0";
                    for(int p=0;p<10000;p++) {
                        var response=client.workbenchGet("/calls?from="+from+"&until="+until+"&after="+cursor);
                        for(var node:response.path("items")) {
                            var row=new LinkedHashMap<>(client.asMap(node));String event=Objects.toString(row.get("eventId"));
                            row.put("id",row.get("logicalId")!=null?row.get("logicalId"):"relay_"+event);row.put("requestId",event);row.put("origin","relay");
                            if(row.get("sourceUsageId")!=null)records.jdbc().update("delete from ai_usage_record where id=?","legacy_relay_"+row.get("sourceUsageId"));
                            records.save(row,null);
                        }
                        if(!response.path("hasMore").asBoolean())break;
                        String next=response.path("nextCursor").asText();if(next.equals(cursor)||p==9999)throw new IllegalStateException("调用记录同步未完成");cursor=next;
                    }
                    records.jdbc().update("update ai_usage_sync set last_sync=?,last_error=null where id=1",until.toString());
                } else records.jdbc().update("update ai_usage_sync set last_error=? where id=1","中转版本尚未提供完整调用记录，Key 调用暂仅显示已有计量数据");
            }
            records.expirePayloads();
        }catch(RuntimeException e){records.jdbc().update("update ai_usage_sync set last_error=? where id=1","调用记录同步失败，请检查中转接口和数据库状态");}
    }
    void importLegacy(){
        var jdbc=records.jdbc();
        var logs=jdbc.queryForList("select l.id from ai_call_log l where not exists(select 1 from ai_usage_legacy_seen s where s.kind='log' and s.id=l.id) order by l.created_at desc,l.id desc limit 500");
        for(var key:logs){String id=Objects.toString(key.get("id"));var raw=jdbc.queryForMap("select * from ai_call_log where id=?",id);
            if(jdbc.queryForObject("select count(*) from ai_usage_record where legacy_log_id=?",Integer.class,id)==0){
                var response=map(raw.get("response_body"));var metadata=map(response.get("_platform"));
                // Relay facts/audits are imported separately; wrapper logs must never double count them.
                if(!"relay".equals(metadata.get("billingSource"))) {
                    var row=new LinkedHashMap<String,Object>();row.put("id","legacy_log_"+id);row.put("legacyLogId",id);row.put("channel","workbench");row.put("origin","legacy");
                    copy(raw,row,"user_id","userId","tool_key","toolKey","run_id","runId","model_name","model","created_at","occurredAt","status","status","prompt_tokens","inputTokens","completion_tokens","outputTokens");
                    row.put("provider",metadata.get("provider"));row.put("complete",false);
                    records.save(row,Map.of("request",map(raw.get("request_params")),"response",response,"coverage","历史日志，完整性无法核实"));
                }
            }
            jdbc.update("insert into ai_usage_legacy_seen values('log',?)",id);
        }
        var usage=jdbc.queryForList("select r.* from relay_usage r where not exists(select 1 from ai_usage_legacy_seen s where s.kind='relay' and s.id=r.source_id) order by r.occurred_at desc,r.source_id desc limit 500");
        for(var raw:usage){String id=Objects.toString(raw.get("source_id"));
            if(jdbc.queryForObject("select count(*) from ai_usage_record where source_usage_id=?",Integer.class,id)==0){
                var row=new LinkedHashMap<String,Object>();row.put("id","legacy_relay_"+id);row.put("sourceUsageId",id);row.put("origin","legacy");row.put("channel","internal".equals(raw.get("channel"))?"workbench":"key");row.put("complete",false);row.put("status","recorded");
                copy(raw,row,"user_id","userId","department_id","departmentId","tool_key","toolKey","run_id","runId","project_id","projectId","model","model","occurred_at","occurredAt","duration_ms","durationMs","input_tokens","inputTokens","output_tokens","outputTokens","cache_tokens","cacheTokens","image_count","imageCount");
                records.save(row,null);
            }
            jdbc.update("insert into ai_usage_legacy_seen values('relay',?)",id);
        }
    }
    public Object remotePayload(Map<String,Object> row){
        try {
            if("relay".equals(row.get("origin")))return client.asMap(client.workbenchGet("/calls/"+row.get("requestId")+"/audit"));
            if(row.get("sourceUsageId")!=null)return client.asMap(client.workbenchGet("/usage/"+row.get("sourceUsageId")+"/audit"));
        }catch(RuntimeException e){
            Object local=records.payload(row);
            if(local instanceof Map<?,?> m && !Boolean.FALSE.equals(m.get("available"))){var fallback=json.convertValue(m,new TypeReference<Map<String,Object>>(){});fallback.put("complete",false);fallback.put("reason","中转审计暂不可读取，当前为工作台保存的输入与结果");return fallback;}
            return Map.of("available",false,"reason","中转正文未保存、已过期或暂不可读取");
        }
        return records.payload(row);
    }
    private Map<String,Object> map(Object value){if(value instanceof Map<?,?> m)return json.convertValue(m,new TypeReference<>(){});try{return value==null?Map.of():json.readValue(value.toString(),new TypeReference<>(){});}catch(Exception e){return Map.of();}}
    private static void copy(Map<String,Object> from,Map<String,Object> to,String... pairs){for(int i=0;i<pairs.length;i+=2)to.put(pairs[i+1],from.get(pairs[i]));}
    public void bindTask(String taskId,String recordId,String userId){if(taskId==null||taskId.length()>180||userId==null)return;if(records.jdbc().queryForObject("select count(*) from ai_usage_task where task_id=?",Integer.class,taskId)==0)records.jdbc().update("insert into ai_usage_task values(?,?,?)",taskId,recordId,userId);}
    public void updateTask(String taskId,String userId,Map<String,Object> response){
        var ids=records.jdbc().queryForList("select record_id from ai_usage_task where task_id=? and user_id=?",String.class,taskId,userId);if(ids.isEmpty())return;
        var row=records.find(ids.getFirst());if(row==null)return;
        String status=Objects.toString(response.get("status"),"unknown").toLowerCase(Locale.ROOT);row.put("status",switch(status){case "completed","succeeded","success","done"->"success";case "failed","error"->"failed";case "cancelled","canceled"->"cancelled";case "pending","running","processing","queued"->"running";default->"unknown";});
        Object previous=records.payload(row);var payload=previous instanceof Map<?,?> m?new LinkedHashMap<>(json.convertValue(m,new TypeReference<Map<String,Object>>(){})):new LinkedHashMap<String,Object>();payload.put("taskResult",response);
        if(response.get("results") instanceof Collection<?> list)row.put("imageCount",list.size());records.save(row,payload);
    }
}
