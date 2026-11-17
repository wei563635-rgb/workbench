package com.yiqi.aitoolbench.toolsession;

import com.yiqi.aitoolbench.ApiController;
import com.yiqi.aitoolbench.org.OrganizationDirectoryService;
import com.yiqi.aitoolbench.security.PermissionService;
import com.yiqi.aitoolbench.security.ForbiddenException;
import com.yiqi.aitoolbench.tool.ToolAvailabilityPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.util.UriUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tool-sessions")
public class ToolSessionController {
    private final ToolSessionService service;
    private final PermissionService permissionService;
    private final byte[] serviceCredential;
    private final ApiController apiController;
    private final OrganizationDirectoryService organizationDirectoryService;

    public ToolSessionController(ToolSessionService service, PermissionService permissionService,
            @Value("${app.tool-sessions.service-credential:}") String serviceCredential,
            ApiController apiController, OrganizationDirectoryService organizationDirectoryService) {
        this.service = service;
        this.permissionService = permissionService;
        this.serviceCredential = serviceCredential.getBytes(StandardCharsets.UTF_8);
        this.apiController = apiController;
        this.organizationDirectoryService = organizationDirectoryService;
    }

    @PostMapping("/tickets")
    public TicketResponse issue(@RequestHeader("Authorization") String authorization,
            @RequestBody TicketRequest request) {
        var context = permissionService.requireContext(authorization);
        var projectId = request.projectId();
        if (projectId == null || projectId.isBlank()) {
            if (!"promo-image".equals(request.toolKey())
                    && !ToolAvailabilityPolicy.CLOUD_AI_TOOL_KEY.equals(request.toolKey())) throw new IllegalArgumentException("请选择项目");
            permissionService.requireToolAccess(context, request.toolKey());
            projectId = apiController.ensureToolScopeProject(context);
        }
        var issued = service.issue(context, request.toolKey(), projectId);
        var entryUrl = "/tools/" + UriUtils.encodePathSegment(request.toolKey(), StandardCharsets.UTF_8)
                + "/platform-entry?ticket=" + UriUtils.encodeQueryParam(issued.ticket(), StandardCharsets.UTF_8);
        return new TicketResponse(entryUrl, issued.expiresAt());
    }

    @PostMapping("/detail-page-entry")
    public DetailPageEntryResponse detailPageEntry(@RequestHeader("Authorization") String authorization,
            @RequestBody DetailPageEntryRequest request) {
        var context = permissionService.requireContext(authorization);
        if (context.toolSession()) throw new ForbiddenException("工具会话不能打开详情页入口");
        permissionService.requireToolAccess(context, ToolAvailabilityPolicy.DETAIL_PAGE_TOOL_KEY);
        var projectId = request.projectId();
        if (projectId == null || projectId.isBlank()) {
            projectId = apiController.ensureToolScopeProject(context);
        }
        var issued = service.issue(context, ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY, projectId);
        var entryUrl = "/tools/" + ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY
                + "/platform-entry?ticket=" + UriUtils.encodeQueryParam(issued.ticket(), StandardCharsets.UTF_8);
        return new DetailPageEntryResponse("workbench", entryUrl, projectId);
    }

    @PostMapping("/exchange")
    public ExchangeResponse exchange(@RequestHeader("Authorization") String authorization,
            @RequestBody ExchangeRequest request) {
        requireService(authorization);
        var value = service.exchange(request.ticket(), request.toolKey(), Instant.now());
        return new ExchangeResponse(value.sessionToken(), value.userId(), value.toolKey(), value.projectId(), value.expiresAt());
    }

    @PostMapping("/introspect")
    public IntrospectionResponse introspect(@RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Tool-Session") String toolSession) {
        requireService(authorization);
        var value = service.introspect(toolSession, Instant.now());
        return new IntrospectionResponse(true, value.userId(), value.account(), value.displayName(), value.role(),
                value.departmentKeys(), value.toolKey(), value.projectId(), value.expiresAt());
    }

    @PostMapping("/detail-page-context")
    public DetailPageContextResponse detailPageContext(@RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Tool-Session") String toolSession) {
        requireService(authorization);
        var value = service.detailPageContext(toolSession, Instant.now());
        return new DetailPageContextResponse(true, value.userId(), value.account(), value.displayName(),
                value.departmentKeys(), value.roleCodes(), value.toolKey(), value.projectId(), value.expiresAt());
    }

    @PostMapping("/detail-page-directory")
    public OrganizationDirectoryService.Directory detailPageDirectory(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireService(authorization);
        return organizationDirectoryService.snapshot();
    }

    private void requireService(String authorization) {
        var candidate = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (serviceCredential.length == 0 || !MessageDigest.isEqual(
                serviceCredential, candidate.getBytes(StandardCharsets.UTF_8))) {
            throw new ForbiddenException("服务身份无效");
        }
    }

    public record TicketRequest(String toolKey, String projectId) {}
    public record DetailPageEntryRequest(String projectId) {}
    public record DetailPageEntryResponse(String mode, String entryUrl, String projectId) {}
    public record ExchangeRequest(String ticket, String toolKey) {}
    public record TicketResponse(String entryUrl, Instant expiresAt) {}
    public record ExchangeResponse(String toolSession, String userId, String toolKey, String projectId,
            Instant expiresAt) {}
    public record IntrospectionResponse(boolean active, String userId, String account, String name, String role,
            List<String> departmentKeys, String toolKey, String projectId, Instant expiresAt) {}
    public record DetailPageContextResponse(boolean active, String userId, String account, String name,
            List<String> departmentKeys, List<String> roleCodes, String toolKey, String projectId,
            Instant expiresAt) {}
}
