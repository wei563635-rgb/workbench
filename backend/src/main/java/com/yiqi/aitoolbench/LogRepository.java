package com.yiqi.aitoolbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LogRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LogRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        createSchema();
    }

    public List<ApiController.AiCallLog> listAiLogs() {
        return jdbc.query("""
                select id, run_id, tool_key, user_id, model_name, model_type, request_params, response_body,
                       prompt_tokens, completion_tokens, total_tokens, cost_estimate, status, created_at
                from ai_call_log
                order by created_at desc, id desc
                limit 500
                """, this::mapAiCallLog);
    }

    public List<ApiController.AiCallLog> listAiLogs(int page, int pageSize) {
        return jdbc.query("""
                select id, run_id, tool_key, user_id, model_name, model_type, request_params, response_body,
                       prompt_tokens, completion_tokens, total_tokens, cost_estimate, status, created_at
                from ai_call_log
                order by created_at desc, id desc
                limit ? offset ?
                """, this::mapAiCallLog, pageSize, (long) (page - 1) * pageSize);
    }

    public long countAiLogs() {
        Long count = jdbc.queryForObject("select count(*) from ai_call_log", Long.class);
        return count == null ? 0 : count;
    }

    public AiStats aggregateAiStats() {
        var logs = allAiLogs().stream().filter(log -> !isRelay(log)).toList();
        var relay = relayStats(null);
        var generatedImages = logs.stream()
                .filter(log -> "image".equals(log.modelType()) && "success".equals(log.status()))
                .mapToLong(log -> countImages(log.responseBody()))
                .sum();
        return new AiStats(
                logs.size() + relay.callCount(),
                logs.stream().mapToLong(this::tokenUsage).sum() + relay.totalTokens(),
                logs.stream().map(this::creditUsage).reduce(BigDecimal.ZERO, BigDecimal::add),
                logs.stream().filter(log -> !isCreditBilled(log)).map(ApiController.AiCallLog::costEstimate).reduce(BigDecimal.ZERO, BigDecimal::add),
                generatedImages + relay.generatedImageCount());
    }

    /** Successful calls identify departments that actually used AI, including external relay clients. */
    public Set<String> successfulAiDepartmentIds() {
        var departmentIds = new LinkedHashSet<String>();
        jdbc.queryForList("""
                select distinct d.department_id
                from ai_call_log l
                join sys_user_department d on d.user_id=l.user_id and d.primary_department=true
                where l.status='success'
                """, String.class).forEach(departmentIds::add);
        if (relayTableExists()) {
            jdbc.queryForList("""
                    select distinct department_id
                    from relay_usage
                    where status='success' and department_id is not null
                    """, String.class).forEach(departmentIds::add);
        }
        return departmentIds;
    }
    /** Cursor-based aggregation keeps direct-provider usage separate from relay budgets. */
    public Map<String,Object> directStats(Instant from,Instant until,String userId){
        var totals=new long[3];var credits=new BigDecimal[]{BigDecimal.ZERO};
        String filter=userId==null?"":" and user_id=?";
        Object[] args=userId==null?new Object[]{from.toString(),until.toString()}:new Object[]{from.toString(),until.toString(),userId};
        jdbc.query("select * from ai_call_log where created_at>=? and created_at<?"+filter,r->{
            var record=mapAiCallLog(r,0);if(isRelay(record))return;
            totals[0]++;totals[1]+=tokenUsage(record);credits[0]=credits[0].add(creditUsage(record));
            if("image".equals(record.modelType())&&"success".equals(record.status()))totals[2]+=countImages(record.responseBody());
        },args);
        return Map.of("requests",totals[0],"totalTokens",totals[1],"imageCount",totals[2],"credits",credits[0]);
    }

    public AiStats aggregateAiStatsForRuns(Collection<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return new AiStats(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        var logs = allAiLogs().stream().filter(log -> runIds.contains(log.runId()) && !isRelay(log)).toList();
        var relay = relayStats(runIds);
        long images = logs.stream()
                .filter(log -> "image".equals(log.modelType()) && "success".equals(log.status()))
                .mapToLong(log -> countImages(log.responseBody()))
                .sum();
        return new AiStats(
                logs.size() + relay.callCount(),
                logs.stream().mapToLong(this::tokenUsage).sum() + relay.totalTokens(),
                logs.stream().map(this::creditUsage).reduce(BigDecimal.ZERO, BigDecimal::add),
                logs.stream().filter(log -> !isCreditBilled(log)).map(ApiController.AiCallLog::costEstimate).reduce(BigDecimal.ZERO, BigDecimal::add),
                images + relay.generatedImageCount());
    }

    public BigDecimal creditUsage(ApiController.AiCallLog log) {
        if (log == null || !isCreditBilled(log) || !"success".equals(log.status())) {
            return BigDecimal.ZERO;
        }
        var response = log.responseBody();
        if (response == null) return BigDecimal.ZERO;
        var value = creditValue(response);
        if (value == null) return BigDecimal.ZERO;
        try {
            var credits = new BigDecimal(value.toString());
            return credits.signum() < 0 ? BigDecimal.ZERO : credits;
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    public long tokenUsage(ApiController.AiCallLog log) {
        return log == null || isRelay(log) || isCreditBilled(log) ? 0 : Math.max(0, log.totalTokens());
    }

    private boolean isRelay(ApiController.AiCallLog log) {
        return log != null && log.responseBody()!=null && log.responseBody().get("_platform") instanceof Map<?,?> metadata
                && "relay".equals(metadata.get("billingSource"));
    }
    private boolean relayTableExists() {
        try(var connection=jdbc.getDataSource().getConnection();var tables=connection.getMetaData().getTables(null,null,null,new String[]{"TABLE"})) {
            while(tables.next())if("relay_usage".equalsIgnoreCase(tables.getString("TABLE_NAME")))return true;
            return false;
        }catch(SQLException ex){throw new IllegalStateException("无法读取统一用量结构",ex);}
    }
    private AiStats relayStats(Collection<String> runIds) {
        if(!relayTableExists())return new AiStats(0,0,BigDecimal.ZERO,BigDecimal.ZERO,0);
        String filter=runIds==null?"":" where run_id in ("+String.join(",",java.util.Collections.nCopies(runIds.size(),"?"))+")";
        Object[] args=runIds==null?new Object[]{}:runIds.toArray();
        return jdbc.queryForObject("select count(*),coalesce(sum(input_tokens+output_tokens+cache_tokens),0),coalesce(sum(image_count),0) from relay_usage"+filter,
                (r,n)->new AiStats(r.getLong(1),r.getLong(2),BigDecimal.ZERO,BigDecimal.ZERO,r.getLong(3)),args);
    }
    public Map<String,Long> relayTokensForRuns(Collection<String> runIds) {
        var result=new LinkedHashMap<String,Long>();if(runIds.isEmpty()||!relayTableExists())return result;
        String marks=String.join(",",java.util.Collections.nCopies(runIds.size(),"?"));
        jdbc.query("select run_id,sum(input_tokens+output_tokens+cache_tokens) from relay_usage where run_id in ("+marks+") group by run_id",r->{result.put(r.getString(1),r.getLong(2));},runIds.toArray());return result;
    }

    private boolean isCreditBilled(ApiController.AiCallLog log) {
        if (log == null) return false;
        if ("image".equals(log.modelType()) || log.modelName().toLowerCase().startsWith("coze-")) return true;
        var platform = log.responseBody() == null ? null : log.responseBody().get("_platform");
        return platform instanceof Map<?, ?> metadata
                && "credits".equalsIgnoreCase(String.valueOf(metadata.get("billingUnit")));
    }

    private Object creditValue(Map<String, Object> response) {
        var direct = usageCredit(response.get("usage"));
        if (direct != null) return direct;
        if (response.get("completionResponse") instanceof Map<?, ?> completion
                && completion.get("data") instanceof Map<?, ?> data) {
            return usageCredit(data.get("usage"));
        }
        return null;
    }

    private Object usageCredit(Object usageValue) {
        if (!(usageValue instanceof Map<?, ?> usage)) return null;
        for (var key : List.of("credits_reserved", "credits", "credit_count", "point_count")) {
            if (usage.get(key) != null) return usage.get(key);
        }
        return null;
    }

    private long countImages(Map<String, Object> response) {
        if(response.get("_platform") instanceof Map<?,?> metadata && metadata.get("imageCount") instanceof Number count)return Math.max(0,count.longValue());
        for (var key : List.of("images", "data", "results")) {
            if (response.get(key) instanceof Collection<?> items) {
                return items.size();
            }
        }
        return 0;
    }

    public void insertAiLog(ApiController.AiCallLog log) {
        jdbc.update("""
                        insert into ai_call_log
                            (id, run_id, tool_key, user_id, model_name, model_type, request_params, response_body,
                             prompt_tokens, completion_tokens, total_tokens, cost_estimate, status, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                log.id(),
                log.runId(),
                log.toolKey(),
                log.userId(),
                log.modelName(),
                log.modelType(),
                toJson(log.requestParams()),
                toJson(log.responseBody()),
                log.promptTokens(),
                log.completionTokens(),
                log.totalTokens(),
                log.costEstimate(),
                log.status(),
                log.createdAt().toString());
    }

    public void bindAiTask(String taskId, String runId, String toolKey, String projectId, String modelName) {
        var existing = findAiTaskBinding(taskId);
        if (existing.isEmpty()) {
            jdbc.update("""
                    insert into ai_task_binding (task_id,run_id,tool_key,project_id,model_name,created_at)
                    values (?,?,?,?,?,?)
                    """, taskId, runId, toolKey, projectId, modelName, Instant.now().toString());
            return;
        }
        var expected = new AiTaskBinding(taskId, runId, toolKey, projectId, modelName);
        if (!existing.get().equals(expected)) {
            throw new IllegalStateException("AI 任务归属冲突");
        }
    }

    public java.util.Optional<AiTaskBinding> findAiTaskBinding(String taskId) {
        return jdbc.query("""
                select task_id,run_id,tool_key,project_id,model_name
                from ai_task_binding where task_id=?
                """, (rs, rowNum) -> new AiTaskBinding(rs.getString("task_id"), rs.getString("run_id"),
                rs.getString("tool_key"), rs.getString("project_id"), rs.getString("model_name")), taskId)
                .stream().findFirst();
    }

    public List<ApiController.OperationLog> listOperationLogs() {
        return jdbc.query("""
                select id, operator, action, summary, detail, created_at
                from operation_log
                order by created_at desc, id desc
                limit 500
                """, this::mapOperationLog);
    }

    public List<ApiController.OperationLog> listOperationLogs(int page, int pageSize) {
        return jdbc.query("""
                select id, operator, action, summary, detail, created_at
                from operation_log
                order by created_at desc, id desc
                limit ? offset ?
                """, this::mapOperationLog, pageSize, (long) (page - 1) * pageSize);
    }

    public long countOperationLogs() {
        Long count = jdbc.queryForObject("select count(*) from operation_log", Long.class);
        return count == null ? 0 : count;
    }

    public void insertOperationLog(ApiController.OperationLog log) {
        jdbc.update("""
                        insert into operation_log (id, operator, action, summary, detail, created_at)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                log.id(),
                log.operator(),
                log.action(),
                log.summary(),
                toJson(log.detail()),
                log.createdAt().toString());
    }

    public boolean hasLog(String id) {
        Integer count = jdbc.queryForObject("select count(*) from operation_log where id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    public boolean hasAiLog(String id) {
        Integer count = jdbc.queryForObject("select count(*) from ai_call_log where id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists ai_call_log (
                    id varchar(80) primary key,
                    run_id varchar(80) not null,
                    tool_key varchar(120) not null,
                    user_id varchar(80) not null,
                    model_name varchar(120) not null,
                    model_type varchar(40) not null,
                    request_params text not null,
                    response_body text not null,
                    prompt_tokens int not null,
                    completion_tokens int not null,
                    total_tokens int not null,
                    cost_estimate decimal(18,6) not null,
                    status varchar(40) not null,
                    created_at varchar(40) not null
                )
                """);
        jdbc.execute("""
                create table if not exists operation_log (
                    id varchar(80) primary key,
                    operator varchar(120) not null,
                    action varchar(80) not null,
                    summary varchar(500) not null,
                    detail text not null,
                    created_at varchar(40) not null
                )
                """);
        jdbc.execute("""
                create table if not exists ai_task_binding (
                    task_id varchar(160) primary key,
                    run_id varchar(80) not null,
                    tool_key varchar(120) not null,
                    project_id varchar(120) not null,
                    model_name varchar(120) not null,
                    created_at varchar(40) not null
                )
                """);
        jdbc.execute("create index if not exists idx_ai_call_log_created_id on ai_call_log (created_at desc, id desc)");
        jdbc.execute("create index if not exists idx_operation_log_created_id on operation_log (created_at desc, id desc)");
    }

    private ApiController.AiCallLog mapAiCallLog(ResultSet rs, int rowNum) throws SQLException {
        var responseBody = fromJson(rs.getString("response_body"));
        return new ApiController.AiCallLog(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("tool_key"),
                rs.getString("user_id"),
                rs.getString("model_name"),
                rs.getString("model_type"),
                fromJson(rs.getString("request_params")),
                responseBody,
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getInt("total_tokens"),
                rs.getBigDecimal("cost_estimate"),
                rs.getString("status"),
                Instant.parse(rs.getString("created_at")));
    }

    private List<ApiController.AiCallLog> allAiLogs() {
        return jdbc.query("""
                select id, run_id, tool_key, user_id, model_name, model_type, request_params, response_body,
                       prompt_tokens, completion_tokens, total_tokens, cost_estimate, status, created_at
                from ai_call_log
                """, this::mapAiCallLog);
    }

    private ApiController.OperationLog mapOperationLog(ResultSet rs, int rowNum) throws SQLException {
        return new ApiController.OperationLog(
                rs.getString("id"),
                rs.getString("operator"),
                rs.getString("action"),
                rs.getString("summary"),
                fromJson(rs.getString("detail")),
                Instant.parse(rs.getString("created_at")));
    }

    public record AiStats(long callCount, long totalTokens, BigDecimal totalCredits, BigDecimal totalCost, long generatedImageCount) {}
    public record AiTaskBinding(String taskId, String runId, String toolKey, String projectId, String modelName) {}

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalStateException("日志 JSON 序列化失败", ex);
        }
    }

    private Map<String, Object> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            var fallback = new LinkedHashMap<String, Object>();
            fallback.put("raw", value);
            fallback.put("parseError", ex.getMessage());
            return fallback;
        }
    }
}
