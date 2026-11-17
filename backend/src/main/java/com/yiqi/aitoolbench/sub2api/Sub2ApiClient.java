package com.yiqi.aitoolbench.sub2api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Sub2ApiClient {
    private static final String API_PREFIX = "/api/v1/admin";
    private static final List<String> ACCOUNT_FIELDS = List.of(
            "id", "name", "notes", "platform", "type", "proxy_id", "concurrency", "load_factor",
            "current_concurrency", "priority", "status", "error_message", "last_used_at", "expires_at",
            "created_at", "updated_at", "schedulable", "rate_limited_at", "rate_limit_reset_at",
            "temp_unschedulable_until", "temp_unschedulable_reason", "overload_until", "session_window_start", "session_window_end", "session_window_status", "quota_limit", "quota_used",
            "quota_daily_limit", "quota_daily_used", "quota_weekly_limit", "quota_weekly_used",
            "credentials_status");
    private static final List<String> PROXY_FIELDS = List.of(
            "id", "name", "protocol", "status", "account_count", "latency_ms", "latency_status",
            "ip_address", "country", "country_code", "region", "city", "quality_status", "quality_score",
            "quality_grade", "quality_summary", "expires_at");

    private final Sub2ApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public Sub2ApiClient(Sub2ApiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    Sub2ApiClient(Sub2ApiProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public Map<String, Object> configurationStatus() {
        return Map.of(
                "configured", properties.configured(),
                "version", "",
                "extensionBaseline", "86f93c2",
                "platforms", List.of("openai", "gemini"));
    }

    public JsonNode workbenchGet(String suffix) {
        if (!suffix.startsWith("/") || suffix.contains("..")) throw new IllegalArgumentException("扩展路径无效");
        return request("GET", "/workbench" + suffix, null, "application/json");
    }

    public JsonNode workbenchPut(String suffix, Object body) {
        if (!"/policy".equals(suffix)) throw new IllegalArgumentException("扩展路径无效");
        return request("PUT", "/workbench" + suffix, objectMapper.valueToTree(body), "application/json");
    }

    public Map<String,Object> asMap(JsonNode node) {
        return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
    }

    public long ensureWorkbenchUser(String externalId, String name, int concurrency, long groupId) {
        String email = externalId + "@workbench.invalid";
        var matches = request("GET", "/users" + queryString(Map.of("search", email,"page_size","100")), null, "application/json");
        for (var user : matches.path("items")) if (email.equals(user.path("email").asText())) return user.path("id").asLong();
        var body=objectMapper.createObjectNode();body.put("email",email);body.put("username",name);
        body.put("password",java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID());
        body.put("role","user");body.put("concurrency",concurrency);body.put("restrict_public_groups",true);
        body.putArray("allowed_groups").add(groupId);
        return request("POST","/users",body,"application/json").path("id").asLong();
    }

    public void validateRelayGroup(long groupId) {
        var group=request("GET","/groups/"+groupId,null,"application/json");
        String platform=group.path("platform").asText();
        if(!List.of("openai","gemini","composite").contains(platform)||!"active".equals(group.path("status").asText())) throw new IllegalArgumentException("中转分组必须为启用的 OpenAI、Google 或合成分组");
    }

    public long createWorkbenchKey(long userId,long groupId,String purpose,String key) {
        var body=objectMapper.createObjectNode();body.put("user_id",userId);body.put("group_id",groupId);body.put("purpose",purpose);body.put("custom_key",key);
        return request("POST","/workbench/keys",body,"application/json").path("id").asLong();
    }

    public void disableWorkbenchKey(long userId,long keyId,String purpose) {
        var body=objectMapper.createObjectNode();body.put("user_id",userId);body.put("key_id",keyId);body.put("purpose",purpose);
        request("DELETE","/workbench/keys",body,"application/json");
    }

    public Map<String, Object> listAccounts(int page, int pageSize, String platform, String status, String search) {
        var items=new ArrayList<Map<String,Object>>();
        for(String source:supportedPlatform(platform)?List.of(platform):List.of("openai","gemini")){
            for(int cursor=1;cursor<=100;cursor++){
                var query=new LinkedHashMap<String,String>();query.put("platform",source);query.put("page",String.valueOf(cursor));query.put("page_size","100");query.put("lite","true");
                if(status!=null&&!status.isBlank())query.put("status",status.trim());if(search!=null&&!search.isBlank())query.put("search",search.trim());
                var data=request("GET","/accounts"+queryString(query),null,"application/json");
                for(var account:data.path("items"))if(source.equals(account.path("platform").asText()))items.add(sanitize(account,ACCOUNT_FIELDS));
                if(cursor>=data.path("pages").asInt(1))break;
                if(cursor==100)throw new IllegalStateException("账号池超过单次查询范围，请按平台筛选");
            }
        }
        items.sort(java.util.Comparator.comparingLong((Map<String,Object> a)->((Number)a.get("id")).longValue()).reversed());
        int start=(int)Math.min((long)(page-1)*pageSize,items.size());
        var summary=new LinkedHashMap<String,Object>();summary.put("total",items.size());
        summary.put("abnormal",items.stream().filter(a->"error".equals(a.get("status"))).count());
        summary.put("cooling",items.stream().filter(this::cooling).count());
        var healthy=items.stream().filter(a->"active".equals(a.get("status"))&&!cooling(a)).toList();
        summary.put("healthy",healthy.size());
        summary.put("window5h",windowAverage(healthy,"codex5hUsedPercent"));
        summary.put("window7d",windowAverage(healthy,"codex7dUsedPercent"));
        summary.put("capacity",items.stream().mapToLong(a->((Number)a.getOrDefault("concurrency",0)).longValue()).sum());
        summary.put("current",items.stream().allMatch(a->a.get("currentConcurrency") instanceof Number)?items.stream().mapToLong(a->((Number)a.get("currentConcurrency")).longValue()).sum():null);
        return Map.of("items",items.subList(start,Math.min(start+pageSize,items.size())),"page",page,"pageSize",pageSize,"total",(long)items.size(),"totalPages",(items.size()+pageSize-1)/pageSize,"summary",summary);
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> accountSummary(){
        return (Map<String,Object>)listAccounts(1,1,null,null,null).get("summary");
    }
    private Map<String,Object> windowAverage(List<Map<String,Object>> healthy,String field){
        double remainingSum=0;int count=0;
        for(var account:healthy){
            if(!"openai".equals(account.get("platform"))||!"oauth".equals(account.get("type")))continue;
            if(!(account.get("usage") instanceof Map<?,?> usage)||!(usage.get(field) instanceof Number value))continue;
            double used=value.doubleValue();
            if(!Double.isFinite(used)||used<0||used>100)continue;
            remainingSum+=100-used;count++;
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("accountCount",count);
        result.put("averageRemainingPercent",count==0?null:remainingSum/count);
        return result;
    }
    private boolean cooling(Map<String,Object> account){
        if(Boolean.FALSE.equals(account.get("schedulable")))return true;
        for(String key:List.of("rateLimitResetAt","tempUnschedulableUntil","overloadUntil"))try{if(account.get(key)!=null&&Instant.parse(account.get(key).toString()).isAfter(Instant.now()))return true;}catch(java.time.format.DateTimeParseException ignored){}
        return false;
    }
    public Map<String,Object> refreshUsage(long id){
        var account=request("GET","/accounts/"+id,null,"application/json");validatePlatform(account.path("platform").asText());
        if(!"openai".equals(account.path("platform").asText()) || !"oauth".equals(account.path("type").asText())) throw new IllegalArgumentException("此账号不适用订阅窗口刷新");
        var body=objectMapper.createObjectNode();body.putArray("account_ids").add(id);body.put("force",true);
        var result=request("POST","/accounts/usage/batch",body,"application/json");
        if(result.path("errors").has(Long.toString(id)))throw new IllegalStateException("上游窗口刷新失败，请稍后重试");
        return sanitize(request("GET","/accounts/"+id,null,"application/json"),ACCOUNT_FIELDS);
    }

    public List<Map<String, Object>> listProxies() {
        var data = request("GET", "/proxies/all?with_count=true", null, "application/json");
        var proxies = new ArrayList<Map<String, Object>>();
        if (data.isArray()) {
            data.forEach(proxy -> proxies.add(sanitize(proxy, PROXY_FIELDS)));
        }
        return proxies;
    }

    public Map<String, Object> startOAuth(OAuthStart request) {
        validatePlatform(request.platform());
        ObjectNode body = objectMapper.createObjectNode();
        putNullable(body, "proxy_id", request.proxyId());
        JsonNode data;
        if ("openai".equals(request.platform())) {
            putText(body, "redirect_uri", request.redirectUri());
            data = request("POST", "/openai/generate-auth-url", body, "application/json");
        } else {
            putText(body, "project_id", request.projectId());
            putText(body, "oauth_type", defaultIfBlank(request.oauthType(), "code_assist"));
            putText(body, "tier_id", request.tierId());
            data = request("POST", "/gemini/oauth/auth-url", body, "application/json");
        }
        var authUrl = data.path("auth_url").asText();
        var state = data.path("state").asText();
        if (state.isBlank()) state = queryParameter(authUrl, "state");
        return Map.of(
                "authUrl", authUrl,
                "sessionId", data.path("session_id").asText(),
                "state", state,
                "platform", request.platform());
    }

    public Map<String, Object> completeOAuth(OAuthComplete input) {
        return completeOAuth(input, 0);
    }

    public Map<String, Object> completeOAuth(OAuthComplete input, long relayGroupId) {
        validatePlatform(input.platform());
        // Validate before exchanging a one-time code; account creation and group binding stay atomic.
        if (relayGroupId > 0) validateRelayGroup(relayGroupId);
        var parsed = parseAuthorizationResult(input.authorizationResult(), input.state());
        if (parsed.code().isBlank() || parsed.state().isBlank() || isBlank(input.sessionId())) {
            throw new IllegalArgumentException("授权结果、state 和 sessionId 不能为空");
        }
        validateAccount(input.name(), input.concurrency(), input.priority());
        var extra = objectMapper.createObjectNode();
        if ("openai".equals(input.platform())) addWindowThresholds(extra, input.threshold5h(), input.threshold7d());
        var exchange = objectMapper.createObjectNode();
        exchange.put("session_id", input.sessionId().trim());
        exchange.put("code", parsed.code()); exchange.put("state", parsed.state());
        putNullable(exchange, "proxy_id", input.proxyId());
        putText(exchange, "redirect_uri", input.redirectUri());
        if ("gemini".equals(input.platform())) {
            putText(exchange, "oauth_type", defaultIfBlank(input.oauthType(), "google_one"));
            putText(exchange, "tier_id", input.tierId());
        }
        var tokens = request("POST", "openai".equals(input.platform()) ? "/openai/exchange-code" : "/gemini/oauth/exchange-code", exchange, "application/json");
        var create = accountBody(input.platform(), "oauth", input.name(), input.proxyId(), input.concurrency(), input.priority(), relayGroupId);
        putText(create, "notes", input.notes());
        create.set("credentials", "openai".equals(input.platform()) ? openAiCredentials(tokens) : geminiCredentials(tokens));
        if ("gemini".equals(input.platform()) && tokens.path("extra").isObject()) create.set("extra", tokens.path("extra"));
        else create.set("extra", extra);
        return sanitize(credentialRequest("POST", "/accounts", create), ACCOUNT_FIELDS);
    }

    public record ApiKeyAccount(String platform, String name, Long proxyId, Integer concurrency,
                                Integer priority, String apiKey, String notes) {}

    public Map<String,Object> createApiKeyAccount(ApiKeyAccount input, long groupId) {
        validatePlatform(input.platform()); validateAccount(input.name(), input.concurrency(), input.priority());
        validateApiKey(input.apiKey());
        if (groupId <= 0) throw new IllegalStateException("请先配置工作台中转分组");
        validateRelayGroup(groupId);
        var body = accountBody(input.platform(), "apikey", input.name(), input.proxyId(), input.concurrency(), input.priority(), groupId);
        putText(body, "notes", input.notes());
        var credentials = objectMapper.createObjectNode();
        credentials.put("api_key", input.apiKey().trim());
        credentials.put("base_url", "openai".equals(input.platform()) ? "https://api.openai.com" : "https://generativelanguage.googleapis.com");
        body.set("credentials", credentials);
        return sanitize(credentialRequest("POST", "/accounts", body), ACCOUNT_FIELDS);
    }

    private ObjectNode accountBody(String platform, String type, String name, Long proxy, Integer concurrency, Integer priority, long group) {
        var body = objectMapper.createObjectNode();
        body.put("platform", platform); body.put("type", type); body.put("name", name.trim());
        if (proxy != null && proxy > 0) body.put("proxy_id", proxy);
        body.put("concurrency", concurrency); body.put("priority", priority == null ? 2 : priority);
        if (group > 0) body.putArray("group_ids").add(group);
        return body;
    }

    private void validateAccount(String name, Integer concurrency, Integer priority) {
        if (name == null || name.isBlank() || name.length() > 100 || concurrency == null || concurrency < 1 || concurrency > 100)
            throw new IllegalArgumentException("请填写账号标识和 1～100 的整数并发");
        if (priority != null && (priority < 0 || priority > 3)) throw new IllegalArgumentException("调度优先级无效");
    }

    private void validateApiKey(String key) {
        if (key == null || key.isBlank() || key.length() > 8192 || key.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("请填写有效的 API Key，不能包含空白字符");
    }

    private void addWindowThresholds(ObjectNode extra, Integer five, Integer seven) {
        int h = five == null ? 90 : five, d = seven == null ? 95 : seven;
        if (h < 1 || h > 100 || d < 1 || d > 100) throw new IllegalArgumentException("窗口已用阈值须为 1～100%");
        if (!"1".equals(workbenchGet("/capabilities").path("contractVersion").asText())) throw new IllegalStateException("窗口阈值需要兼容的中转扩展");
        extra.put("workbench_threshold_5h", h); extra.put("workbench_threshold_7d", d);
    }

    private JsonNode credentialRequest(String method, String path, ObjectNode body) {
        try { return request(method, path, body, "application/json"); }
        catch (Sub2ApiException e) { throw new Sub2ApiException(e.statusCode(), "上游账号保存失败，请检查凭证、代理及分组配置后重试"); }
    }

    private ObjectNode openAiCredentials(JsonNode tokens) {
        var credentials = objectMapper.createObjectNode();
        for (String key : List.of("access_token", "refresh_token", "id_token", "email", "chatgpt_account_id", "chatgpt_user_id", "organization_id", "plan_type", "subscription_expires_at", "client_id", "chatgpt_account_is_fedramp"))
            if (tokens.hasNonNull(key)) credentials.set(key, tokens.get(key));
        if (tokens.path("expires_at").asLong() > 0) credentials.put("expires_at", Instant.ofEpochSecond(tokens.path("expires_at").asLong()).toString());
        return credentials;
    }

    public Map<String, Object> updateStatus(long accountId, boolean enabled) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", enabled ? "active" : "inactive");
        return sanitize(request("PUT", "/accounts/" + accountId, body, "application/json"), ACCOUNT_FIELDS);
    }

    public record AccountEdit(String name,String notes,Long proxyId,Integer concurrency,Integer threshold5h,Integer threshold7d,Integer priority,String apiKey) {
        public AccountEdit(String name,String notes,Long proxyId,Integer concurrency,Integer threshold5h,Integer threshold7d,Integer priority) { this(name,notes,proxyId,concurrency,threshold5h,threshold7d,priority,null); }
    }
    public Map<String,Object> editAccount(long id,AccountEdit input) {
        if(id<=0||input.name()==null||input.name().isBlank()||input.name().length()>100||input.concurrency()==null||input.concurrency()<1||input.concurrency()>100)
            throw new IllegalArgumentException("请填写账号名称和 1～100 的整数并发");
        validateAccount(input.name(), input.concurrency(), input.priority());
        var existing=request("GET","/accounts/"+id,null,"application/json");validatePlatform(existing.path("platform").asText());
        var body=objectMapper.createObjectNode();body.put("name",input.name().trim());body.put("notes",input.notes()==null?"":input.notes());body.put("concurrency",input.concurrency());body.put("proxy_id",input.proxyId()==null?0:input.proxyId());
        if(input.priority()!=null)body.put("priority",normalizedPriority(input.priority()));
        if("openai".equals(existing.path("platform").asText()) && "oauth".equals(existing.path("type").asText())){
            if(!"1".equals(workbenchGet("/capabilities").path("contractVersion").asText()))throw new IllegalStateException("窗口阈值需要兼容的中转扩展");
            for(Integer n:new Integer[]{input.threshold5h(),input.threshold7d()})if(n==null||n<1||n>100)throw new IllegalArgumentException("窗口已用阈值须为 1～100%");
            var extra=existing.path("extra") instanceof ObjectNode node?node.deepCopy():objectMapper.createObjectNode();extra.put("workbench_threshold_5h",input.threshold5h());extra.put("workbench_threshold_7d",input.threshold7d());body.set("extra",extra);
        }
        if (!isBlank(input.apiKey())) {
            if (!"apikey".equals(existing.path("type").asText())) throw new IllegalArgumentException("订阅账号请使用授权刷新，不能写入 API Key");
            validateApiKey(input.apiKey());
            var credentials = existing.path("credentials") instanceof ObjectNode node ? node.deepCopy() : objectMapper.createObjectNode();
            credentials.put("api_key", input.apiKey().trim()); body.set("credentials", credentials);
        }
        return sanitize(credentialRequest("PUT", "/accounts/"+id, body), ACCOUNT_FIELDS);
    }
    public Map<String,Object> recoverAccount(long id) {
        return sanitize(request("POST","/accounts/"+id+"/recover-state",objectMapper.createObjectNode(),"application/json"),ACCOUNT_FIELDS);
    }

    public Map<String, Object> refresh(long accountId) {
        return sanitize(request("POST", "/accounts/" + accountId + "/refresh", objectMapper.createObjectNode(), "application/json"), ACCOUNT_FIELDS);
    }

    public Map<String, Object> test(long accountId) {
        return test(accountId, null);
    }

    public String accountPlatform(long accountId) {
        return request("GET", "/accounts/" + accountId, null, "application/json").path("platform").asText();
    }

    public Map<String, Object> test(long accountId, String model) {
        var body = objectMapper.createObjectNode();
        putText(body, "model_id", model);
        var data = request("POST", "/accounts/" + accountId + "/test", body, "text/event-stream");
        return Map.of("success", true, "message", data.path("message").asText("账号连通性测试已完成"));
    }

    public void delete(long accountId) {
        request("DELETE", "/accounts/" + accountId, null, "application/json");
    }

    private JsonNode request(String method, String path, JsonNode body, String accept) {
        requireConfigured();
        try {
            var builder = HttpRequest.newBuilder(URI.create(properties.baseUrl() + API_PREFIX + path))
                    .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                    .header("x-api-key", properties.adminApiKey())
                    .header("Accept", accept);
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new Sub2ApiException(response.statusCode(), errorMessage(response.body()));
            }
            if ("text/event-stream".equals(accept)) {
                boolean completed = false;
                // Native tests report errors inside data events and can close before completion.
                for (String line : response.body().split("\\R")) {
                    if (line.startsWith("event:") && line.substring(6).trim().equals("error")) {
                        throw new Sub2ApiException(502, "Sub2API 账号连通性测试失败");
                    }
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || payload.equals("[DONE]")) continue;
                    JsonNode event = objectMapper.readTree(payload);
                    if (event.path("type").asText().equals("error")
                            || (event.has("success") && !event.path("success").asBoolean())) {
                        throw new Sub2ApiException(502, "Sub2API 账号连通性测试失败");
                    }
                    if (event.path("type").asText().equals("test_complete")
                            && event.path("success").asBoolean()) completed = true;
                }
                if (!completed) throw new Sub2ApiException(502, "Sub2API 账号连通性测试未完成，请重试");
                ObjectNode result = objectMapper.createObjectNode();
                result.put("message", "账号连通性测试已完成");
                return result;
            }
            var root = response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
            if (root.has("code") && root.path("code").asInt() != 0) {
                throw new Sub2ApiException(response.statusCode(), root.path("message").asText("Sub2API 请求失败"));
            }
            return root.has("data") ? root.path("data") : root;
        } catch (Sub2ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Sub2ApiException(502, "Sub2API 请求被中断");
        } catch (IOException | IllegalArgumentException ex) {
            throw new Sub2ApiException(502, "无法连接 Sub2API：" + ex.getMessage());
        }
    }

    private ObjectNode geminiCredentials(JsonNode tokens) {
        ObjectNode credentials = objectMapper.createObjectNode();
        for (var key : List.of("access_token", "refresh_token", "token_type", "expires_at", "scope",
                "project_id", "oauth_type", "tier_id")) {
            if (tokens.hasNonNull(key)) credentials.set(key, tokens.get(key));
        }
        return credentials;
    }

    private Map<String, Object> sanitize(JsonNode source, List<String> allowedFields) {
        var result = new LinkedHashMap<String, Object>();
        for (var field : allowedFields) {
            if (source.has(field) && !source.get(field).isNull()) {
                result.put(toCamelCase(field), objectMapper.convertValue(source.get(field), Object.class));
            }
        }
        if (source.path("proxy").isObject()) {
            result.put("proxy", sanitize(source.path("proxy"), PROXY_FIELDS));
        }
        if (source.path("extra").isObject()) {
            var usage = new LinkedHashMap<String, Object>();
            for (var field : List.of("codex_5h_used_percent", "codex_5h_reset_at",
                    "codex_7d_used_percent", "codex_7d_reset_at", "codex_usage_updated_at", "workbench_threshold_5h", "workbench_threshold_7d")) {
                if (source.path("extra").hasNonNull(field)) {
                    usage.put(toCamelCase(field), objectMapper.convertValue(source.path("extra").get(field), Object.class));
                }
            }
            if (!usage.isEmpty()) result.put("usage", usage);
        }
        return result;
    }

    private OAuthCode parseAuthorizationResult(String result, String fallbackState) {
        var trimmed = result == null ? "" : result.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return new OAuthCode(queryParameter(trimmed, "code"), queryParameter(trimmed, "state"));
        }
        return new OAuthCode(trimmed, fallbackState == null ? "" : fallbackState.trim());
    }

    private String queryParameter(String value, String name) {
        try {
            var query = URI.create(value).getRawQuery();
            if (query == null) return "";
            for (var part : query.split("&")) {
                var pair = part.split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) {
                    return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // 输入也可能是纯授权码，由调用方继续处理。
        }
        return "";
    }

    private String queryString(Map<String, String> query) {
        return "?" + query.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right).orElse("");
    }

    private String errorMessage(String body) {
        try {
            var root = objectMapper.readTree(body);
            var message = root.path("message").asText();
            return message.isBlank() ? "Sub2API 请求失败" : message;
        } catch (Exception ignored) {
            return "Sub2API 请求失败";
        }
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new Sub2ApiException(503, "Sub2API 尚未配置，请设置服务地址和 Admin API Key");
        }
    }

    private void validatePlatform(String platform) {
        if (!supportedPlatform(platform)) throw new IllegalArgumentException("仅支持 openai 或 gemini");
    }

    private boolean supportedPlatform(String platform) {
        return "openai".equals(platform) || "gemini".equals(platform);
    }

    private int normalizedPriority(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private void putText(ObjectNode node, String field, String value) {
        if (!isBlank(value)) node.put(field, value.trim());
    }

    private void putNullable(ObjectNode node, String field, Long value) {
        if (value != null) node.put(field, value);
    }

    private String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String toCamelCase(String value) {
        var result = new StringBuilder();
        boolean upper = false;
        for (char ch : value.toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else if (upper) {
                result.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    public record OAuthStart(String platform, Long proxyId, String redirectUri, String projectId,
                             String oauthType, String tierId) {}

    public record OAuthComplete(String platform, String sessionId, String state, String authorizationResult,
                                String redirectUri, Long proxyId, String name, Integer concurrency,
                                Integer priority, String oauthType, String tierId, Integer threshold5h, Integer threshold7d, String notes) {
        public OAuthComplete(String platform,String sessionId,String state,String authorizationResult,String redirectUri,Long proxyId,String name,Integer concurrency,Integer priority,String oauthType,String tierId) {
            this(platform,sessionId,state,authorizationResult,redirectUri,proxyId,name,concurrency,priority,oauthType,tierId,90,95,null);
        }
    }

    private record OAuthCode(String code, String state) {}
}
