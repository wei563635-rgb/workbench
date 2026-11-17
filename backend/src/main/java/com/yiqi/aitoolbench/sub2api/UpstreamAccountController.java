package com.yiqi.aitoolbench.sub2api;

import com.yiqi.aitoolbench.ApiController.OperationLog;
import com.yiqi.aitoolbench.LogRepository;
import com.yiqi.aitoolbench.WorkbenchRepository;
import com.yiqi.aitoolbench.security.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UpstreamAccountController {
    private final Sub2ApiClient client;
    private final PermissionService permissionService;
    private final LogRepository logRepository;
    private final long relayGroupId;
    private final WorkbenchRepository repository;

    public UpstreamAccountController(Sub2ApiClient client, PermissionService permissionService,
            LogRepository logRepository, @Value("${app.relay.group-id:0}") long relayGroupId,
            WorkbenchRepository repository) {
        this.client = client;
        this.permissionService = permissionService;
        this.logRepository = logRepository;
        this.relayGroupId = relayGroupId;
        this.repository = repository;
    }

    @GetMapping("/api/admin/upstream-accounts/configuration")
    public Map<String, Object> configuration() {
        return client.configurationStatus();
    }

    @GetMapping("/api/admin/upstream-accounts")
    public Map<String, Object> accounts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        return client.listAccounts(normalizePage(page), normalizePageSize(pageSize), platform, status, search);
    }

    @GetMapping("/api/admin/upstream-accounts/proxies")
    public Object proxies() {
        return client.listProxies();
    }

    @PostMapping("/api/admin/upstream-accounts")
    public Map<String,Object> createApiKey(@RequestBody Sub2ApiClient.ApiKeyAccount input, HttpServletRequest request) {
        var account = client.createApiKeyAccount(input, relayGroupId);
        log(request, "UPSTREAM_ACCOUNT_CREATE", "新增上游 API 账号", Map.of("accountId", account.getOrDefault("id", ""), "platform", input.platform(), "type", "apikey"));
        return account;
    }

    @PostMapping("/api/admin/upstream-accounts/oauth/start")
    public Map<String, Object> startOAuth(@RequestBody Sub2ApiClient.OAuthStart request) {
        return client.startOAuth(request);
    }

    @PostMapping("/api/admin/upstream-accounts/oauth/complete")
    public Map<String, Object> completeOAuth(@RequestBody Sub2ApiClient.OAuthComplete request,
            HttpServletRequest servletRequest) {
        var account = client.completeOAuth(request, relayGroupId);
        log(servletRequest, "UPSTREAM_ACCOUNT_CREATE", "新增上游订阅账号", Map.of(
                "accountId", account.getOrDefault("id", ""),
                "platform", account.getOrDefault("platform", request.platform())));
        return account;
    }

    @PutMapping("/api/admin/upstream-accounts/{accountId}/status")
    public Map<String, Object> updateStatus(@PathVariable long accountId, @RequestBody StatusRequest request,
            HttpServletRequest servletRequest) {
        var account = client.updateStatus(accountId, request.enabled());
        log(servletRequest, "UPSTREAM_ACCOUNT_STATUS", "修改上游账号状态",
                Map.of("accountId", accountId, "enabled", request.enabled()));
        return account;
    }

    @PostMapping("/api/admin/upstream-accounts/{accountId}/test")
    public Map<String, Object> test(@PathVariable long accountId, @RequestBody(required=false) AccountTest input, HttpServletRequest servletRequest) {
        String platform = client.accountPlatform(accountId);
        String model = input != null && input.model() != null && !input.model().isBlank() ? input.model().trim() : repository.loadModelConfigs().values().stream()
                .filter(c -> c.enabled() && "sub2api".equals(c.provider()) && "text".equals(c.modelType()))
                .map(c -> c.modelName())
                .filter(name -> "openai".equals(platform)
                        ? name.startsWith("gpt-") || name.matches("o[134].*")
                        : "gemini".equals(platform) && name.startsWith("gemini-"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("请先在模型配置中启用该平台的中转文本模型，再测试账号"));
        if (model.length() > 200 || model.chars().anyMatch(Character::isWhitespace)
                || !("openai".equals(platform) ? model.startsWith("gpt-") || model.matches("o[134].*") : "gemini".equals(platform) && model.startsWith("gemini-")))
            throw new IllegalArgumentException("请输入该平台支持的模型名称");
        var result = client.test(accountId, model);
        log(servletRequest, "UPSTREAM_ACCOUNT_TEST", "测试上游账号连通性", Map.of("accountId", accountId));
        return result;
    }

    @PostMapping("/api/admin/upstream-accounts/{accountId}/refresh")
    public Map<String, Object> refresh(@PathVariable long accountId, HttpServletRequest servletRequest) {
        var account = client.refresh(accountId);
        log(servletRequest, "UPSTREAM_ACCOUNT_REFRESH", "刷新上游账号凭据", Map.of("accountId", accountId));
        return account;
    }

    @DeleteMapping("/api/admin/upstream-accounts/{accountId}")
    public Map<String, Object> delete(@PathVariable long accountId, HttpServletRequest servletRequest) {
        client.delete(accountId);
        log(servletRequest, "UPSTREAM_ACCOUNT_DELETE", "删除上游订阅账号", Map.of("accountId", accountId));
        return Map.of("deleted", true, "accountId", accountId);
    }

    private void log(HttpServletRequest request, String action, String summary, Map<String, Object> detail) {
        var context = permissionService.requireContext(request.getHeader("Authorization"));
        logRepository.insertOperationLog(new OperationLog(
                "op_" + UUID.randomUUID().toString().replace("-", ""),
                context.userId(), action, summary, detail, Instant.now()));
    }

    private int normalizePage(int page) {
        return Math.max(1, page);
    }

    private int normalizePageSize(int pageSize) {
        return Math.max(1, Math.min(pageSize, 100));
    }

    public record StatusRequest(boolean enabled) {}
    public record AccountTest(String model) {}

    @PutMapping("/api/admin/upstream-accounts/{accountId}")
    public Map<String,Object> edit(@PathVariable long accountId,@RequestBody Sub2ApiClient.AccountEdit input,HttpServletRequest request){
        var result=client.editAccount(accountId,input);log(request,"UPSTREAM_ACCOUNT_EDIT","修改上游账号及窗口阈值",Map.of("accountId",accountId));return result;
    }
    @PostMapping("/api/admin/upstream-accounts/{accountId}/recover")
    public Map<String,Object> recover(@PathVariable long accountId,HttpServletRequest request){
        var result=client.recoverAccount(accountId);log(request,"UPSTREAM_ACCOUNT_RECOVER","清除上游账号异常状态",Map.of("accountId",accountId));return result;
    }
    @PostMapping("/api/admin/upstream-accounts/{accountId}/usage-refresh")
    public Map<String,Object> refreshUsage(@PathVariable long accountId,HttpServletRequest request){
        var result=client.refreshUsage(accountId);log(request,"UPSTREAM_USAGE_REFRESH","刷新上游用量窗口",Map.of("accountId",accountId));return result;
    }
}
