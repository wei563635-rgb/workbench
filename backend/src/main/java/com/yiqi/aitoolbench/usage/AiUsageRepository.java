package com.yiqi.aitoolbench.usage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.security.SecretCrypto;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiUsageRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecretCrypto crypto;
    public static final int BODY_LIMIT = 64 * 1024 * 1024;
    public AiUsageRepository(JdbcTemplate jdbc, ObjectMapper json, SecretCrypto crypto) {
        this.jdbc=jdbc; this.json=json; this.crypto=crypto;
        jdbc.execute("""
            create table if not exists ai_usage_record (
             id varchar(180) primary key, channel varchar(20) not null, origin varchar(30) not null,
             request_id varchar(180), user_id varchar(80), department_id varchar(80), tool_key varchar(100), run_id varchar(120),
             project_id varchar(120), model varchar(200), provider varchar(80), status varchar(30) not null, occurred_at varchar(40) not null,
             updated_at varchar(40) not null, duration_ms bigint, input_tokens bigint, output_tokens bigint, cache_tokens bigint,
             image_count bigint, http_status int, legacy_log_id varchar(120), source_usage_id varchar(100),
             complete boolean not null, encrypted_payload text)
            """);
        jdbc.execute("create index if not exists idx_ai_usage_time on ai_usage_record(occurred_at,id)");
        jdbc.execute("create index if not exists idx_ai_usage_user on ai_usage_record(user_id,occurred_at)");
        jdbc.execute("create index if not exists idx_ai_usage_channel on ai_usage_record(channel,status,occurred_at)");
        jdbc.execute("create index if not exists idx_ai_usage_legacy on ai_usage_record(legacy_log_id)");
        jdbc.execute("create index if not exists idx_ai_usage_source on ai_usage_record(source_usage_id)");
        jdbc.execute("create index if not exists idx_ai_usage_request on ai_usage_record(request_id)");
        jdbc.execute("create table if not exists ai_usage_sync (id int primary key,last_sync varchar(40),last_error varchar(200),legacy_log_cursor varchar(120),legacy_usage_cursor varchar(100))");
        if (jdbc.queryForObject("select count(*) from ai_usage_sync",Integer.class)==0) jdbc.update("insert into ai_usage_sync(id) values(1)");
    }
    public synchronized void save(Map<String,Object> row, Object payload) {
        row=new LinkedHashMap<>(row);
        row.put("occurredAt",stamp(Instant.parse(Objects.toString(row.get("occurredAt"),Instant.now().toString()))));
        String id=text(row,"id"); if(id.isBlank() || id.length()>180)throw new IllegalArgumentException("调用 ID 无效");
        String encrypted=null;
        boolean complete=Boolean.TRUE.equals(row.get("complete"));
        if(payload!=null)try {
            String body=json.writeValueAsString(UsagePayload.clean(payload));
            if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=BODY_LIMIT) encrypted=crypto.encrypt(body);
            else complete=false;
        } catch (Exception ex) { complete=false; }
        if(row.get("legacyLogId")!=null)jdbc.update("delete from ai_usage_record where id=? and id<>?","legacy_log_"+row.get("legacyLogId"),id);
        var current=find(id);
        if(current!=null)row.put("occurredAt",current.get("occurredAt"));
        if(current!=null && "relay".equals(row.get("origin"))) {
            for(String k:List.of("userId","departmentId","toolKey","runId","projectId","model"))if(row.get(k)==null)row.put(k,current.get(k));
            // Native facts win for metering, while workbench payload remains a useful fallback.
            if(encrypted==null){encrypted=(String)current.get("encryptedPayload");}
            if(row.get("legacyLogId")==null) row.put("legacyLogId", current.get("legacyLogId"));
        }
        if(current!=null && "relay".equals(current.get("origin")) && "workbench".equals(row.get("origin"))) {
            for(String k:List.of("inputTokens","outputTokens","cacheTokens","imageCount","sourceUsageId","requestId"))row.put(k,current.get(k));
            row.put("origin","relay");
        }
        var values=new Object[]{row.getOrDefault("channel","workbench"),row.getOrDefault("origin","workbench"),row.get("requestId"),row.get("userId"),row.get("departmentId"),row.get("toolKey"),row.get("runId"),row.get("projectId"),row.get("model"),row.get("provider"),row.getOrDefault("status","unknown"),row.getOrDefault("occurredAt",Instant.now().toString()),stamp(Instant.now()),row.get("durationMs"),row.get("inputTokens"),row.get("outputTokens"),row.get("cacheTokens"),row.get("imageCount"),row.get("httpStatus"),row.get("legacyLogId"),row.get("sourceUsageId"),complete,encrypted,id};
        String fields="channel=?,origin=?,request_id=?,user_id=?,department_id=?,tool_key=?,run_id=?,project_id=?,model=?,provider=?,status=?,occurred_at=?,updated_at=?,duration_ms=?,input_tokens=?,output_tokens=?,cache_tokens=?,image_count=?,http_status=?,legacy_log_id=?,source_usage_id=?,complete=?,encrypted_payload=?";
        if(jdbc.update("update ai_usage_record set "+fields+" where id=?",values)==0)
            jdbc.update("insert into ai_usage_record(channel,origin,request_id,user_id,department_id,tool_key,run_id,project_id,model,provider,status,occurred_at,updated_at,duration_ms,input_tokens,output_tokens,cache_tokens,image_count,http_status,legacy_log_id,source_usage_id,complete,encrypted_payload,id) values("+String.join(",",Collections.nCopies(24,"?"))+")",values);
    }
    public Map<String,Object> find(String id){
        var rows=jdbc.query("select * from ai_usage_record where id=?",(r,n)->{
            var m=new LinkedHashMap<String,Object>(); var md=r.getMetaData();
            for(int i=1;i<=md.getColumnCount();i++){String name=md.getColumnLabel(i).toLowerCase(Locale.ROOT);String[] words=name.split("_");String key=words[0];for(int j=1;j<words.length;j++)key+=Character.toUpperCase(words[j].charAt(0))+words[j].substring(1);m.put(key,r.getObject(i));}return m;
        },id);return rows.isEmpty()?null:rows.getFirst();
    }
    public Object payload(Map<String,Object> row) {
        if(Instant.parse(text(row,"occurredAt")).isBefore(Instant.now().minus(Duration.ofDays(180))))return Map.of("available",false,"reason","正文已超过 180 天保留期");
        var encrypted=row.get("encryptedPayload");if(encrypted==null)return Map.of("available",false,"reason","此记录未保存完整正文");
        try{return UsagePayload.clean(json.readValue(crypto.decrypt(encrypted.toString()),new TypeReference<Map<String,Object>>(){}));}
        catch(Exception e){return Map.of("available",false,"reason","正文暂不可读取");}
    }
    public Map<String,Object> list(Map<String,String> filters,int page,int size) {
        var where=new StringBuilder(" from ai_usage_record a left join sys_user u on u.id=a.user_id left join organization_department d on d.id=a.department_id where a.occurred_at>=? and a.occurred_at<?");
        var args=new ArrayList<Object>();
        Instant until=parseTime(filters.get("until"),Instant.now().plusSeconds(1));Instant from=parseTime(filters.get("from"),until.minus(Duration.ofDays(7)));
        if(!from.isBefore(until))throw new IllegalArgumentException("开始时间必须早于结束时间");args.add(stamp(from));args.add(stamp(until));
        for(var entry:Map.of("channel","a.channel","userId","a.user_id","departmentId","a.department_id","model","a.model","toolKey","a.tool_key","status","a.status").entrySet()) {
            String v=filters.get(entry.getKey());if(v!=null&&!v.isBlank()){if(v.length()>200)throw new IllegalArgumentException("筛选值过长");where.append(" and ").append(entry.getValue()).append("=?");args.add(v);}
        }
        String requestId=filters.get("requestId");if(requestId!=null&&!requestId.isBlank()){if(requestId.length()>180)throw new IllegalArgumentException("请求 ID 过长");where.append(" and (a.request_id=? or a.id=?)");args.add(requestId);args.add(requestId);}
        String keyword=filters.get("user");if(keyword!=null&&!keyword.isBlank()){if(keyword.length()>100)throw new IllegalArgumentException("用户筛选值过长");where.append(" and (lower(u.display_name) like ? or lower(u.account) like ?)");String v="%"+keyword.toLowerCase(Locale.ROOT)+"%";args.add(v);args.add(v);}
        long total=jdbc.queryForObject("select count(*)"+where,Long.class,args.toArray());args.add(size);args.add((long)(page-1)*size);
        var rows=jdbc.query("select a.id,a.channel,a.request_id,a.user_id,u.display_name,u.account,a.department_id,d.name,a.tool_key,a.model,a.provider,a.status,a.occurred_at,a.duration_ms,a.input_tokens,a.output_tokens,a.cache_tokens,a.image_count,a.complete,a.origin"+where+" order by a.occurred_at desc,a.id desc limit ? offset ?",(r,n)->{
            var m=new LinkedHashMap<String,Object>();String[] names={"id","channel","requestId","userId","userName","account","departmentId","departmentName","toolKey","model","provider","status","occurredAt","durationMs","inputTokens","outputTokens","cacheTokens","imageCount","complete","origin"};for(int i=0;i<names.length;i++)m.put(names[i],r.getObject(i+1));return m;
        },args.toArray());
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("page",page);result.put("pageSize",size);result.put("total",total);result.put("sync",syncState());result.put("coverage","历史记录仅包含已保存的数据；上线前缺失的失败请求无法恢复。详情页直连 Embedding、Google Vision 不在本页范围内。");return result;
    }
    public Map<String,Object> syncState(){return jdbc.queryForMap("select last_sync,last_error from ai_usage_sync where id=1");}
    public void expirePayloads(){jdbc.update("update ai_usage_record set encrypted_payload=null,complete=false where occurred_at<? and encrypted_payload is not null",stamp(Instant.now().minus(Duration.ofDays(180))));}
    public JdbcTemplate jdbc(){return jdbc;}
    private static String stamp(Instant value){return new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter().format(value);}
    static String text(Map<String,?> row,String key){return Objects.toString(row.get(key),"");}
    private static Instant parseTime(String value,Instant fallback){if(value==null||value.isBlank())return fallback;try{return Instant.parse(value);}catch(Exception e){throw new IllegalArgumentException("时间格式无效");}}
}
