package com.yiqi.aitoolbench;

import com.yiqi.aitoolbench.auth.AuthService;
import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.ai.AiModelConfig;
import com.yiqi.aitoolbench.ai.ProviderOptionsPolicy;
import com.yiqi.aitoolbench.admin.OrganizationHierarchy;
import com.yiqi.aitoolbench.admin.PageResponse;
import com.yiqi.aitoolbench.admin.UserDepartmentSelection;
import com.yiqi.aitoolbench.project.ProjectRepository;
import com.yiqi.aitoolbench.project.ProjectRepository.ProjectTask;
import com.yiqi.aitoolbench.project.ProjectAccessPolicy;
import com.yiqi.aitoolbench.run.RunImageCounter;
import com.yiqi.aitoolbench.security.DataScope;
import com.yiqi.aitoolbench.security.ForbiddenException;
import com.yiqi.aitoolbench.security.PermissionCatalog;
import com.yiqi.aitoolbench.security.PermissionService;
import com.yiqi.aitoolbench.security.SecretCrypto;
import com.yiqi.aitoolbench.tool.ToolRepository;
import com.yiqi.aitoolbench.tool.ToolAvailabilityPolicy;
import com.yiqi.aitoolbench.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@CrossOrigin(origins = "*")
public class ApiController {
    private final LogRepository logRepository;
    private final WorkbenchRepository workbenchRepository;
    private final SessionStore sessionStore;
    private final AiGatewayService aiGatewayService;
    private final StorageService storageService;
    private final AuthService authService;
    private final PermissionService permissionService;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ToolRepository toolRepository;
    private final BrandPageRepository brandPageRepository;
    private final Map<String, ToolRun> runs = new ConcurrentHashMap<>();
    private final Map<String, UserAccount> users = new LinkedHashMap<>();
    private final Map<String, AiModelConfig> modelConfigs = new LinkedHashMap<>();
    private final SecretCrypto secretCrypto;
    private final List<DeploymentRequest> deploymentRequests = new ArrayList<>();
    private final List<OrganizationDepartment> organizationDepartments = new ArrayList<>();
    private final List<Department> departmentSeed = List.of(
            new Department("ai-market", "AI 市场部", "◎", false),
            new Department("ai-design", "AI 设计部", "✎", false),
            new Department("ai-operation", "AI 运营部", "↗", false),
            new Department("ai-hr", "AI 人事部", "☷", false),
            new Department("ai-content", "AI 内容部", "▣", true));
    private final List<ToolConfig> toolSeed;
    private final Map<String, ToolConfig> toolConfigs = new LinkedHashMap<>();
    private final List<ProjectBoard> projects = new CopyOnWriteArrayList<>();
    private static final String TOOL_SCOPE_PREFIX = "tool_scope_";
    private final List<ProjectResult> projectResults = new CopyOnWriteArrayList<>();

    public ApiController(LogRepository logRepository, WorkbenchRepository workbenchRepository, SessionStore sessionStore, AiGatewayService aiGatewayService, StorageService storageService, AuthService authService, PermissionService permissionService, ProjectRepository projectRepository, UserRepository userRepository, ToolRepository toolRepository, BrandPageRepository brandPageRepository, SecretCrypto secretCrypto,
                         @Value("${app.detail-page.entry-url:https://detailpage.sharpseek.cn/}") String detailPageEntryUrl) {
        this.logRepository = logRepository;
        this.workbenchRepository = workbenchRepository;
        this.sessionStore = sessionStore;
        this.aiGatewayService = aiGatewayService;
        this.storageService = storageService;
        this.authService = authService;
        this.permissionService = permissionService;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.toolRepository = toolRepository;
        this.brandPageRepository = brandPageRepository;
        this.secretCrypto = secretCrypto;
        this.toolSeed = List.of(
                tool("market-intelligence", "市场情报", "同源数据生成 A/B 两类看板", "ai-market", ToolAvailabilityPolicy.defaultStatus("market-intelligence"), "blue", true),
                tool("consumer-insight", "消费者洞察", "目标人群需求痛点", "ai-market", ToolAvailabilityPolicy.defaultStatus("consumer-insight"), "purple", true),
                tool("product-dev", "选品与开发", "数据驱动选品决策", "ai-market", ToolAvailabilityPolicy.defaultStatus("product-dev"), "green", true),
                tool("competitor-analysis", "竞店分析", "竞争对手拆解分析", "ai-market", ToolAvailabilityPolicy.defaultStatus("competitor-analysis"), "orange", true),
                tool("model-render", "建模与渲染", "一键渲染高级场景图", "ai-design", ToolAvailabilityPolicy.defaultStatus("model-render"), "red", true),
                tool("promo-image", "推广图制作", "主图与推广素材生成", "ai-design", ToolAvailabilityPolicy.defaultStatus("promo-image"), "cyan", true),
                tool("detail-page", "详情页制作", "详情页视觉生成", "ai-design", ToolAvailabilityPolicy.defaultStatus("detail-page"), "blue", true, detailPageEntryUrl),
                tool(ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY, "详情页制作（新版）", "工作台登录与统一 AI 中转", "ai-design", ToolAvailabilityPolicy.defaultStatus(ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY), "blue", true, detailPageEntryUrl),
                tool("package-design", "包装设计", "预留入口，暂未开放", "ai-design", ToolAvailabilityPolicy.defaultStatus("package-design"), "gray", false),
                tool("geo-optimization", "地域优化", "广告投放地域建议", "ai-operation", ToolAvailabilityPolicy.defaultStatus("geo-optimization"), "green", true),
                tool("project-dashboard", "项目管理看板", "汇总工具结果与项目洞察", "ai-operation", ToolAvailabilityPolicy.defaultStatus("project-dashboard"), "blue", true),
                tool("keyword-demand", "关键词需求分析", "后续工具预留入口", "ai-operation", ToolAvailabilityPolicy.defaultStatus("keyword-demand"), "purple", false),
                tool("title-maker", "标题制作", "后续工具预留入口", "ai-operation", ToolAvailabilityPolicy.defaultStatus("title-maker"), "orange", false),
                tool("attendance-data", "考勤数据", "自动分析月度考勤异常", "ai-hr", ToolAvailabilityPolicy.defaultStatus("attendance-data"), "purple", true),
                tool("main-video", "主图视频", "二期预留入口", "ai-content", ToolAvailabilityPolicy.defaultStatus("main-video"), "orange", false),
                tool("ad-material", "投放素材", "二期预留入口", "ai-content", ToolAvailabilityPolicy.defaultStatus("ad-material"), "blue", false),
                tool(ToolAvailabilityPolicy.NEW_CHAT_TOOL_KEY, "new chat", "无需关联项目的 AI 对话与创作", "ai-content", "normal", "purple", false));
        toolSeed.forEach(tool -> toolConfigs.put(tool.key(), tool));
        users.putAll(seedUserAccounts());
        modelConfigs.putAll(seedModelConfigs());
        organizationDepartments.addAll(seedOrganizationDepartments());
        if (workbenchRepository.hasBusinessData()) {
            restoreState();
            ensureDefaultConfiguration();
            normalizeLegacyProjectIdentities();
            saveState();
        } else {
            restoreState();
            ensureDefaultConfiguration();
            normalizeLegacyProjectIdentities();
            saveState();
        }
    }

    @PostMapping("/api/legacy/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return authService.login(request.account(), request.password(), servletRequest.getRemoteAddr(), servletRequest.getHeader("User-Agent"));
    }

    @GetMapping("/api/legacy/auth/me")
    public Map<String, Object> me(@RequestHeader("Authorization") String authorization) {
        return authService.currentUser(tokenFromAuthorization(authorization));
    }

    @PostMapping("/api/legacy/auth/change-password")
    public Map<String, Object> changePassword(@RequestHeader("Authorization") String authorization, @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(tokenFromAuthorization(authorization), request.oldPassword(), request.newPassword());
    }

    @GetMapping("/api/bootstrap")
    public Map<String, Object> bootstrap(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var context = requireContext(authorization);
        var data = new LinkedHashMap<String, Object>();
        data.put("departments", visibleDepartments(context));
        data.put("tools", visibleTools(context));
        if (permissionService.hasPermission(context, "admin:role:read")) {
            // 角色编辑必须能授权当前操作者尚未拥有的工具。
            data.put("roleTools", toolConfigs.values().stream()
                    .filter(tool -> !ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY.equals(tool.key()))
                    .filter(tool -> !ToolAvailabilityPolicy.CLOUD_AI_TOOL_KEY.equals(tool.key())).toList());
        }
        if (permissionService.hasPermission(context, "admin:user:read")) {
            data.put("users", adminUserDirectory());
        }
        if (permissionService.hasPermission(context, "admin:brand-page:write")) {
            data.put("brandPageViewerUsers", brandPageViewerDirectory());
        }
        if (permissionService.hasPermission(context, "project:create")
                || permissionService.hasPermission(context, "project:write")) {
            data.put("projectUsers", projectMemberCandidates());
        }
        data.put("organizationDepartments", permissionService.hasPermission(context, "admin:department:read")
                ? organizationDepartments
                : organizationDepartments.stream().filter(OrganizationDepartment::enabled).toList());
        if (permissionService.hasPermission(context, "admin:role:read")) {
            data.put("roles", adminRoles());
            data.put("permissions", PermissionCatalog.PERMISSIONS);
        }
        var visibleRuns = visibleRuns(context);
        data.put("runRecords", visibleRuns.stream().map(this::bootstrapRunSummary).toList());
        data.put("runProjects", runProjectDirectory(visibleRuns));
        data.put("runUsers", runUserDirectory(visibleRuns));
        data.put("runTokenUsage", runTokenUsage(visibleRuns));
        data.put("runCreditUsage", runCreditUsage(visibleRuns));
        data.put("runPrompts", runPrompts(visibleRuns));
        var visibleProjects = visibleProjects(context);
        data.put("projects", visibleProjects);
        data.put("projectCreditUsage", projectCreditUsage(visibleProjects));
        data.put("projectTasks", visibleProjectTasks(context));
        data.put("projectResults", visibleProjectResults(context).stream().map(this::bootstrapProjectResultSummary).toList());
        data.put("models", models());
        data.put("stats", statsSummary());
        return data;
    }

    private Map<String, Object> bootstrapRunSummary(ToolRun run) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("runId", run.runId());
        summary.put("toolKey", run.toolKey());
        summary.put("userId", run.userId());
        summary.put("projectId", run.projectId());
        summary.put("status", run.status());
        summary.put("eventType", run.eventType());
        summary.put("inputSummary", run.inputSummary());
        summary.put("outputSummary", run.outputSummary());
        summary.put("startedAt", run.startedAt());
        summary.put("finishedAt", run.finishedAt());
        summary.put("hasOutputData", run.outputData() != null && !run.outputData().isEmpty());
        summary.put("hasAttachments", hasRunAttachments(run));
        summary.put("hasImage", RunImageCounter.countImages(run.toolKey(), run.status(), run.outputData()) > 0);
        return summary;
    }

    private boolean hasRunAttachments(ToolRun run) {
        var output = run.outputData();
        if ("consumer-insight".equals(run.toolKey()) && output != null
                && output.get("dashboardId") instanceof String dashboardId && !dashboardId.isBlank()) return true;
        return hasRunAttachmentUrl(output);
    }

    private boolean hasRunAttachmentUrl(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var key : List.of("url", "downloadUrl", "fileUrl", "imageUrl")) {
                if (map.get(key) instanceof String url && !url.isBlank()) {
                    if (url.regionMatches(true, 0, "http://", 0, 7)
                            || url.regionMatches(true, 0, "https://", 0, 8)
                            || url.startsWith("/") && !url.startsWith("//")) return true;
                    break;
                }
            }
            return map.values().stream().anyMatch(this::hasRunAttachmentUrl);
        }
        if (value instanceof List<?> list) return list.stream().anyMatch(this::hasRunAttachmentUrl);
        return false;
    }

    private ProjectResult bootstrapProjectResultSummary(ProjectResult result) {
        var run = runs.get(result.runId());
        var runSummary = new LinkedHashMap<String, Object>();
        if (run != null) {
            runSummary.put("status", run.status());
            runSummary.put("eventType", run.eventType());
            runSummary.put("finishedAt", run.finishedAt());
        }
        return new ProjectResult(result.id(), result.projectId(), result.runId(), result.toolKey(),
                result.submittedBy(), result.outputSummary(), Map.of("run", runSummary), result.status(),
                result.createdAt(), result.removedBy(), result.removedAt(), result.removeReason());
    }

    @GetMapping("/api/departments")
    public List<Map<String, Object>> departments(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return visibleDepartments(requireContext(authorization));
    }

    @GetMapping("/api/admin/organization-departments")
    public List<OrganizationDepartment> organizationDepartments(@RequestHeader(value = "Authorization", required = false) String authorization) {
        permissionService.requirePermission(requireContext(authorization), "admin:department:read");
        return organizationDepartments;
    }

    @PostMapping("/api/admin/organization-departments")
    public OrganizationDepartment createOrganizationDepartment(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody OrganizationDepartmentRequest request) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "admin:department:write");
        var name = request.name() == null ? "" : request.name().trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("部门名称不能为空");
        }
        if (!isBlank(request.parentId()) && organizationDepartments.stream().noneMatch(item -> item.id().equals(request.parentId()))) {
            throw new IllegalArgumentException("上级部门不存在: " + request.parentId());
        }
        var now = Instant.now().toString();
        var department = new OrganizationDepartment("org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), blankToNull(request.parentId()), name, true, nextOrganizationSort(), now, now);
        organizationDepartments.add(department);
        workbenchRepository.saveOrganizationDepartment(department);
        logOperation(context.userId(), "ORG_DEPARTMENT_CREATE", "新增组织部门", Map.of("departmentId", department.id(), "name", department.name()));
        return department;
    }

    @PutMapping("/api/admin/organization-departments/{departmentId}")
    public OrganizationDepartment updateOrganizationDepartment(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String departmentId, @RequestBody OrganizationDepartmentRequest request) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "admin:department:write");
        var current = requireOrganizationDepartment(departmentId);
        var name = request.name() == null ? current.name() : request.name().trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("部门名称不能为空");
        }
        var parentId = blankToNull(request.parentId());
        OrganizationHierarchy.validateParent(departmentId, parentId, organizationParentMap());
        var updated = new OrganizationDepartment(current.id(), parentId, name, current.enabled(), current.sortOrder(), current.createdAt(), Instant.now().toString());
        replaceOrganizationDepartment(updated);
        logOperation(context.userId(), "ORG_DEPARTMENT_UPDATE", "编辑组织部门", Map.of("departmentId", updated.id(), "name", updated.name()));
        return updated;
    }

    @DeleteMapping("/api/admin/organization-departments/{departmentId}")
    public Map<String, Object> deleteOrganizationDepartment(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String departmentId) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "admin:department:write");
        requireOrganizationDepartment(departmentId);
        if (organizationDepartments.stream().anyMatch(item -> departmentId.equals(item.parentId()))) {
            throw new IllegalStateException("部门存在下级部门，请先处理下级部门");
        }
        if (users.values().stream().anyMatch(user -> user.departmentKeys.contains(departmentId))) {
            throw new IllegalStateException("部门仍有关联用户，请先调整用户部门");
        }
        workbenchRepository.logicalDeleteOrganizationDepartment(departmentId, context.userId());
        organizationDepartments.removeIf(item -> departmentId.equals(item.id()));
        logOperation(context.userId(), "ORG_DEPARTMENT_DELETE", "删除组织部门", Map.of("departmentId", departmentId));
        return Map.of("deleted", true, "departmentId", departmentId);
    }

    @PostMapping("/api/admin/organization-departments/{departmentId}/status")
    public OrganizationDepartment updateOrganizationDepartmentStatus(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String departmentId, @RequestBody UpdateDepartmentStatusRequest request) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "admin:department:write");
        var current = requireOrganizationDepartment(departmentId);
        var updated = new OrganizationDepartment(current.id(), current.parentId(), current.name(), request.enabled(), current.sortOrder(), current.createdAt(), Instant.now().toString());
        replaceOrganizationDepartment(updated);
        logOperation(context.userId(), "ORG_DEPARTMENT_STATUS", "修改组织部门状态", Map.of("departmentId", updated.id(), "enabled", updated.enabled()));
        return updated;
    }

    private List<Map<String, Object>> visibleDepartments(AuthContext context) {
        var tools = visibleTools(context).stream()
                .filter(tool -> !ToolAvailabilityPolicy.NEW_CHAT_TOOL_KEY.equals(tool.key())).toList();
        return departmentSeed.stream()
                .map(department -> Map.<String, Object>of(
                        "key", department.key(),
                        "name", department.name(),
                        "icon", department.icon(),
                        "reserved", department.reserved(),
                        "tools", tools.stream()
                                .filter(tool -> tool.departmentKey().equals(department.key()))
                                .toList()))
                .toList();
    }

    @GetMapping("/api/tools")
    public List<ToolConfig> tools(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return visibleTools(requireContext(authorization));
    }

    @PostMapping("/api/tool-runs/start")
    public ToolRun startRun(@RequestHeader(value = "Authorization", required = false) String authorization, @Valid @RequestBody StartRunRequest request) {
        var context = requireContext(authorization);
        var toolKey = effectiveStartToolKey(context, request.toolKey());
        var projectId = effectiveStartProjectId(context, request.projectId());
        permissionService.requireToolAccess(context, toolKey);
        if (isBlank(projectId)) {
            throw new IllegalArgumentException("使用工具前必须选择项目");
        }
        var project = requireActiveProject(projectId);
        if (!ProjectAccessPolicy.canRun(context.userId(), project.status(), project.memberUserIds())) {
            throw new ForbiddenException("只有进行中项目的参与成员才能运行工具");
        }
        var run = new ToolRun(
                "run_" + UUID.randomUUID().toString().replace("-", ""),
                toolKey,
                context.userId(),
                project.id(),
                "running",
                "open",
                request.inputSummary(),
                null,
                Map.of(),
                Instant.now(),
                null);
        runs.put(run.runId(), run);
        logOperation(context.userId(), "TOOL_OPEN", "打开工具", Map.of("runId", run.runId(), "toolKey", run.toolKey(), "projectId", project.id()));
        workbenchRepository.saveRun(run);
        return run;
    }

    @PostMapping("/api/tool-runs/{runId}/events")
    public Map<String, Object> appendEvent(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String runId, @RequestBody Map<String, Object> event) {
        var run = requireAccessibleRun(authorization, runId);
        permissionService.requireToolAccess(requireContext(authorization), run.toolKey());
        return Map.of("runId", runId, "accepted", true, "event", event, "receivedAt", Instant.now());
    }

    @PostMapping("/api/tool-runs/{runId}/finish")
    public synchronized ToolRun finishRun(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String runId, @Valid @RequestBody FinishRunRequest request) {
        var context = requireContext(authorization);
        var existing = requireAccessibleRun(context, runId);
        permissionService.requireToolAccess(context, existing.toolKey());
        var finished = new ToolRun(
                existing.runId(),
                existing.toolKey(),
                existing.userId(),
                existing.projectId(),
                request.status(),
                "run",
                existing.inputSummary(),
                request.outputSummary(),
                request.outputData(),
                existing.startedAt(),
                Instant.now());
        runs.put(runId, finished);
        ProjectResult createdResult = null;
        if ("success".equalsIgnoreCase(request.status())) {
            createdResult = createProjectResult(finished);
            projectRepository.markTaskDoneByTool(finished.projectId(), finished.toolKey(), finished.runId());
        }
        logOperation(context.userId(), "TOOL_RUN_FINISH", "完成工具运行", Map.of("runId", runId, "status", request.status()));
        workbenchRepository.saveRun(finished);
        if (createdResult != null) {
            workbenchRepository.saveProject(requireProject(finished.projectId()));
            workbenchRepository.saveProjectLink(toProjectLink(createdResult));
        }
        return finished;
    }

    @GetMapping("/api/tool-runs")
    public List<ToolRun> listRuns(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return visibleRuns(requireContext(authorization));
    }

    @GetMapping("/api/tool-runs/{runId}")
    public ToolRun getRun(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String runId) {
        return requireAccessibleRun(authorization, runId);
    }

    @PostMapping("/api/files/upload")
    public Map<String, Object> uploadMetadata(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody FileUploadRequest request) throws Exception {
        var run = requireAccessibleRun(authorization, request.runId());
        var context = requireContext(authorization);
        permissionService.requireToolAccess(context, run.toolKey());
        if (request.sourceUrl() != null && !request.sourceUrl().isBlank()) {
            var storedFile = storageService.saveRemote(request.runId(), run.projectId(), context.userId(),
                    request.sourceUrl(), request.fileName());
            logOperation("system", "FILE_REMOTE_TRANSFER", "转存远端文件",
                    Map.of("runId", run.runId(), "fileName", storedFile.fileName(), "size", storedFile.size()));
            return storedFileResponse(run.runId(), storedFile);
        }
        return storageService.metadataOnly(request.runId(), request.fileName(), request.contentType(), request.size());
    }

    @PostMapping(value = "/api/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadMultipart(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestParam String runId, @RequestParam MultipartFile file) throws Exception {
        var run = requireAccessibleRun(authorization, runId);
        var context = requireContext(authorization);
        permissionService.requireToolAccess(context, run.toolKey());
        var storedFile = storageService.save(runId, run.projectId(), context.userId(),
                file.getOriginalFilename(), file.getContentType(), file.getSize(), file.getInputStream());
        logOperation("system", "FILE_UPLOAD", "上传文件", Map.of("runId", runId, "fileName", storedFile.fileName(), "size", file.getSize()));
        return storedFileResponse(runId, storedFile);
    }

    private Map<String, Object> storedFileResponse(String runId, StorageService.StoredFile storedFile) {
        return Map.of(
                "fileId", storedFile.fileId(),
                "runId", runId,
                "fileName", storedFile.fileName(),
                "contentType", storedFile.contentType(),
                "size", storedFile.size(),
                "storagePath", storedFile.storagePath(),
                "downloadUrl", storedFile.downloadUrl());
    }

    @PostMapping("/api/project-links")
    public ProjectResult linkProject(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody ProjectLinkRequest request) {
        var context = requireContext(authorization);
        var run = requireAccessibleRun(context, request.runId());
        permissionService.requireToolAccess(context, run.toolKey());
        if (!request.projectId().equals(run.projectId())) {
            throw new IllegalArgumentException("工具运行结果已绑定项目，不支持后置改绑项目");
        }
        if (!canAccessProject(context, requireActiveProject(request.projectId()))) {
            throw new ForbiddenException("无权访问项目: " + request.projectId());
        }
        var existing = findProjectResult(run.runId());
        if (existing != null) {
            return existing;
        }
        if (!"success".equalsIgnoreCase(run.status())) {
            throw new IllegalArgumentException("只有成功运行结果会自动沉淀到项目");
        }
        var link = createProjectResult(run);
        logOperation("system", "PROJECT_RESULT_COMPAT", "兼容旧接口返回项目结果", Map.of("projectId", request.projectId(), "runId", request.runId(), "resultId", link.id()));
        saveState();
        return link;
    }

    @GetMapping("/api/projects/{projectId}/links")
    public List<ProjectResult> projectLinks(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String projectId) {
        return projectResults(authorization, projectId);
    }

    @GetMapping("/api/projects/{projectId}/tasks")
    public List<ProjectTask> projectTasks(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String projectId) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "project:read");
        var project = requireActiveProject(projectId);
        if (!canAccessProject(context, project)) {
            throw new ForbiddenException("无权访问项目: " + projectId);
        }
        projectRepository.ensureDefaultTasks(projectId, context.userId());
        return projectRepository.listTasks(projectId);
    }

    @PostMapping("/api/projects/{projectId}/tasks")
    public ProjectTask createProjectTask(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String projectId, @RequestBody CreateProjectTaskRequest request) {
        var context = requireContext(authorization);
        var project = requireActiveProject(projectId);
        if (!canManageProject(context, project)) {
            throw new ForbiddenException("无权管理项目任务: " + projectId);
        }
        if (isBlank(request.title())) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        var sortOrder = request.sortOrder() == null ? 100 : request.sortOrder();
        return projectRepository.createTask(projectId, request.title(), request.toolKey(), request.assigneeUserId(), sortOrder);
    }

    @PostMapping("/api/projects/{projectId}/tasks/{taskId}/status")
    public ProjectTask updateProjectTaskStatus(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String projectId, @PathVariable String taskId, @RequestBody UpdateProjectTaskStatusRequest request) {
        var context = requireContext(authorization);
        var project = requireActiveProject(projectId);
        if (!canManageProject(context, project)) {
            throw new ForbiddenException("无权管理项目任务: " + projectId);
        }
        if (!List.of("TODO", "IN_PROGRESS", "DONE", "BLOCKED").contains(request.status())) {
            throw new IllegalArgumentException("任务状态不合法");
        }
        return projectRepository.updateTaskStatus(projectId, taskId, request.status());
    }

    @GetMapping("/api/projects/{projectId}/results")
    public List<ProjectResult> projectResults(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String projectId) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "project:result:read");
        var project = requireActiveProject(projectId);
        if (!canAccessProject(context, project)) {
            throw new ForbiddenException("无权访问项目: " + projectId);
        }
        return completedProjectResults().stream()
                .filter(result -> result.projectId().equals(projectId))
                .toList();
    }

    @DeleteMapping("/api/project-links/{linkId}")
    public Map<String, Object> unlinkProject(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String linkId) {
        return removeProjectResult(authorization, linkId, Map.of("reason", "兼容旧接口移除项目结果"));
    }

    @PostMapping("/api/project-results/{resultId}/remove")
    public Map<String, Object> removeProjectResult(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String resultId, @RequestBody Map<String, Object> request) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "project:result:remove");
        var reason = String.valueOf(request.getOrDefault("reason", "")).trim();
        if (reason.isBlank()) reason = "用户解除项目与生成记录关联";
        var link = projectResults.stream()
                .filter(item -> item.id().equals(resultId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("项目结果不存在: " + resultId));
        if (!"ACTIVE".equals(link.status())) {
            throw new IllegalArgumentException("项目结果已移除: " + resultId);
        }
        if (!canManageProject(context, requireActiveProject(link.projectId()))) {
            throw new ForbiddenException("无权移除项目结果: " + link.projectId());
        }
        var removed = new ProjectResult(link.id(), link.projectId(), link.runId(), link.toolKey(), link.submittedBy(), link.outputSummary(), link.resultSnapshot(), "REMOVED", link.createdAt(), context.userId(), Instant.now(), reason);
        replaceProjectResult(removed);
        projectRepository.detachRun(link.projectId(), link.runId());
        var project = requireProject(link.projectId());
        replaceProject(project.id(), project.withResultCount(Math.max(0, project.resultCount() - 1)));
        logOperation(context.userId(), "PROJECT_RESULT_REMOVE", "解除项目与生成记录关联", Map.of("projectId", link.projectId(), "runId", link.runId(), "resultId", resultId, "reason", reason));
        saveState();
        return Map.of("removed", true, "resultId", resultId);
    }

    @GetMapping("/api/projects")
    public List<ProjectBoard> projects(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return visibleProjects(requireContext(authorization));
    }

    @DeleteMapping("/api/projects/{projectId}")
    public Map<String, Object> deleteProject(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String projectId) {
        var context = requireContext(authorization);
        var project = requireActiveProject(projectId);
        requireUserManagedProject(project);
        if (!canManageProject(context, project)) {
            throw new ForbiddenException("无权删除项目: " + projectId);
        }
        workbenchRepository.logicalDeleteProject(projectId, context.userId());
        replaceProject(projectId, project.withStatus("已删除").withCapabilities(false, false));
        logOperation(context.userId(), "PROJECT_DELETE", "逻辑删除项目", Map.of("projectId", projectId, "name", project.name()));
        return Map.of("deleted", true, "projectId", projectId);
    }

    @PostMapping("/api/projects")
    public ProjectBoard createProject(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody CreateProjectRequest request) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "project:create");
        if (isBlank(request.name())) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        var departmentId = requestedProjectDepartmentId(request);
        requireAssignableProjectDepartment(context, departmentId);
        var ownerUserId = isBlank(request.ownerUserId()) ? context.userId() : request.ownerUserId();
        if (!ownerUserId.equals(context.userId())
                && permissionService.effectiveDataScope(context) != DataScope.ALL) {
            throw new ForbiddenException("只有全平台项目管理员可以指定其他负责人");
        }
        var owner = requireProjectOwner(ownerUserId);
        var memberUserIds = normalizeProjectMemberIds(ownerUserId, request.memberUserIds(), request.members());
        var memberNames = projectMemberNames(memberUserIds);
        var createdAt = Instant.now().toString();
        var details = new LinkedHashMap<>(request.details() == null ? Map.of() : request.details());
        details.put("departmentId", departmentId);
        var project = new ProjectBoard(
                "proj_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                request.name(),
                owner.displayName(),
                "进行中",
                memberNames,
                0,
                "0",
                0,
                request.summary() == null ? "" : request.summary(),
                details,
                List.of(),
                createdAt,
                departmentId,
                ownerUserId,
                memberUserIds,
                context.userId(),
                true,
                true);
        projects.add(project);
        projectRepository.ensureDefaultTasks(project.id(), context.userId());
        logOperation(context.userId(), "PROJECT_CREATE", "创建项目看板", Map.of(
                "projectId", project.id(), "name", project.name(), "departmentId", departmentId));
        saveState();
        return project;
    }

    @PutMapping("/api/projects/{projectId}")
    public ProjectBoard updateProject(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String projectId,
            @RequestBody CreateProjectRequest request) {
        var context = requireContext(authorization);
        var project = requireActiveProject(projectId);
        requireUserManagedProject(project);
        if (!canManageProject(context, project)) {
            throw new ForbiddenException("无权修改项目: " + projectId);
        }
        if (isBlank(request.name())) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        var departmentId = isBlank(requestedProjectDepartmentId(request))
                ? project.departmentId()
                : requestedProjectDepartmentId(request);
        if (!departmentId.equals(project.departmentId())) {
            requireAssignableProjectDepartment(context, departmentId);
        }
        var ownerUserId = isBlank(request.ownerUserId()) ? project.ownerUserId() : request.ownerUserId();
        if (!ownerUserId.equals(project.ownerUserId())
                && permissionService.effectiveDataScope(context) != DataScope.ALL) {
            throw new ForbiddenException("只有全平台项目管理员可以更换负责人");
        }
        var owner = requireProjectOwner(ownerUserId);
        var requestedMemberUserIds = request.memberUserIds() == null
                ? project.memberUserIds()
                : request.memberUserIds();
        var memberUserIds = normalizeProjectMemberIds(ownerUserId, requestedMemberUserIds, request.members());
        var memberNames = projectMemberNames(memberUserIds);
        var details = new LinkedHashMap<>(project.details());
        if (request.details() != null) {
            details.putAll(request.details());
        }
        details.put("departmentId", departmentId);
        var updated = project.withContent(request.name(), owner.displayName(), memberNames,
                request.summary() == null ? "" : request.summary(), details)
                .withAccessMetadata(departmentId, ownerUserId, memberUserIds, project.createdBy())
                .withCapabilities(true, ProjectAccessPolicy.canRun(context.userId(), project.status(), memberUserIds));
        replaceProject(projectId, updated);
        logOperation(context.userId(), "PROJECT_UPDATE", "编辑项目", Map.of("projectId", projectId, "name", updated.name()));
        saveState();
        return updated;
    }

    @PostMapping("/api/projects/{projectId}/status")
    public ProjectBoard updateProjectStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String projectId,
            @RequestBody UpdateProjectStatusRequest request) {
        var context = requireContext(authorization);
        requireUserManagedProject(requireActiveProject(projectId));
        if (!List.of("进行中", "已完成", "归档").contains(request.status())) {
            throw new IllegalArgumentException("项目状态不合法");
        }
        for (var i = 0; i < projects.size(); i++) {
            var current = projects.get(i);
            if (current.id().equals(projectId)) {
                if (!canManageProject(context, current)) {
                    throw new ForbiddenException("无权修改项目状态: " + projectId);
                }
                var updated = current.withStatus(request.status())
                        .withCapabilities(true,
                                ProjectAccessPolicy.canRun(context.userId(), request.status(), current.memberUserIds()));
                replaceProject(projectId, updated);
                logOperation(context.userId(), "PROJECT_STATUS", "修改项目状态",
                        Map.of("projectId", projectId, "status", request.status()));
                saveState();
                return updated;
            }
        }
        throw new IllegalArgumentException("项目不存在: " + projectId);
    }

    @PostMapping(value = "/api/projects/{projectId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectBoard uploadProjectCover(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String projectId,
            @RequestParam MultipartFile file) throws Exception {
        var context = requireContext(authorization);
        var project = requireActiveProject(projectId);
        if (!canManageProject(context, project)) {
            throw new ForbiddenException("无权修改项目: " + projectId);
        }
        if (file.isEmpty() || file.getSize() > 5L * 1024 * 1024) {
            throw new IllegalArgumentException("项目封面不能为空且不能超过 5MB");
        }
        if (!List.of("image/jpeg", "image/png", "image/webp").contains(file.getContentType())) {
            throw new IllegalArgumentException("项目封面只支持 JPG、PNG、WebP");
        }
        var stored = storageService.save("project_" + projectId, projectId, context.userId(),
                file.getOriginalFilename(), file.getContentType(), file.getSize(), file.getInputStream());
        var details = new LinkedHashMap<>(project.details());
        details.put("coverUrl", stored.downloadUrl());
        details.put("coverFileId", stored.fileId());
        var updated = project.withDetails(details);
        replaceProject(projectId, updated);
        logOperation(context.userId(), "PROJECT_COVER_UPLOAD", "上传项目封面", Map.of("projectId", projectId, "fileId", stored.fileId()));
        saveState();
        return updated;
    }

    @DeleteMapping("/api/projects/{projectId}/cover")
    public ProjectBoard deleteProjectCover(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String projectId) {
        var context = requireContext(authorization);
        var project = requireActiveProject(projectId);
        if (!canManageProject(context, project)) {
            throw new ForbiddenException("无权修改项目: " + projectId);
        }
        var details = new LinkedHashMap<>(project.details());
        details.remove("coverUrl");
        details.remove("coverFileId");
        var updated = project.withDetails(details);
        replaceProject(projectId, updated);
        logOperation(context.userId(), "PROJECT_COVER_REMOVE", "移除项目封面", Map.of("projectId", projectId));
        saveState();
        return updated;
    }

    @GetMapping("/api/files/demo-download/{fileId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId) throws Exception {
        var stored = storageService.load(fileId);
        var contentType = stored.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(stored.contentType());
        var disposition = stored.contentType() != null && stored.contentType().startsWith("image/")
                ? ContentDisposition.inline().filename(stored.fileName()).build()
                : ContentDisposition.attachment().filename(stored.fileName()).build();
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(stored.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(Files.newInputStream(stored.path())));
    }

    @GetMapping("/api/brand-pages")
    public List<BrandPageRepository.BrandPage> searchBrandPages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String departmentId,
            @RequestParam(defaultValue = "false") boolean admin) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "brand:page:read");
        if (admin) permissionService.requirePermission(context, "admin:brand-page:write");
        var canManage = permissionService.hasPermission(context, "admin:brand-page:write");
        return brandPageRepository.search(keyword, departmentId).stream()
                .filter(page -> canAccessBrandPage(context, page, canManage))
                .toList();
    }

    @GetMapping("/api/admin/brand-pages")
    public Object adminBrandPages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "brand:page:read");
        var canManage = permissionService.hasPermission(context, "admin:brand-page:write");
        var pages = brandPageRepository.search("").stream()
                .filter(brandPage -> canAccessBrandPage(context, brandPage, canManage))
                .toList();
        return page == null && pageSize == null ? pages : PageResponse.of(pages, page == null ? 1 : page, pageSize == null ? 10 : pageSize);
    }

    @GetMapping("/api/brand-pages/{pageId}/content")
    public ResponseEntity<Resource> brandPageContent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String pageId,
            @RequestParam(defaultValue = "false") boolean admin) throws Exception {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "brand:page:read");
        if (admin) permissionService.requirePermission(context, "admin:brand-page:write");
        var canManage = permissionService.hasPermission(context, "admin:brand-page:write");
        var page = brandPageRepository.findActiveById(pageId)
                .orElseThrow(() -> new IllegalArgumentException("品牌 HTML 页面不存在"));
        if (!canAccessBrandPage(context, page, canManage)) {
            throw new ForbiddenException("无权访问该品牌 HTML 页面");
        }
        var stored = storageService.load(page.fileId());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .contentLength(stored.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, inlineContentDisposition(page.fileName()))
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(Files.newInputStream(stored.path())));
    }

    static String inlineContentDisposition(String fileName) {
        return ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build().toString();
    }

    @PostMapping(value = "/api/admin/brand-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public BrandPageRepository.BrandPage uploadBrandPage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String brandName,
            @RequestParam String departmentId,
            @RequestParam(required = false) List<String> viewerDepartmentIds,
            @RequestParam(required = false) List<String> viewerUserIds,
            @RequestParam MultipartFile file) throws Exception {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "admin:brand-page:write");
        var normalizedBrand = brandName == null ? "" : brandName.trim();
        if (normalizedBrand.isBlank() || normalizedBrand.length() > 160) {
            throw new IllegalArgumentException("品牌名称不能为空且不能超过 160 个字符");
        }
        var department = requireOrganizationDepartment(departmentId);
        if (!department.enabled()) {
            throw new IllegalArgumentException("不能关联已禁用的组织部门");
        }
        var viewers = validateBrandPageViewers(viewerDepartmentIds, viewerUserIds);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("HTML 文件不能为空");
        }
        var fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".html")) {
            throw new IllegalArgumentException("仅支持上传 .html 文件");
        }
        var stored = storageService.saveWithoutSizeLimit("brand_page_" + UUID.randomUUID().toString().replace("-", ""), null,
                context.userId(), fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE, file.getSize(), file.getInputStream());
        var page = brandPageRepository.saveOrReplace(normalizedBrand, department.id(), stored.fileId(),
                stored.fileName(), context.userId(), viewers.departmentIds(), viewers.userIds());
        logOperation(context.userId(), "BRAND_HTML_UPSERT", "上传品牌 HTML 页面",
                Map.of("brand", normalizedBrand, "departmentId", department.id(), "pageId", page.id(),
                        "viewerDepartmentCount", viewers.departmentIds().size(),
                        "viewerUserCount", viewers.userIds().size()));
        return page;
    }

    @PutMapping(value = "/api/admin/brand-pages/{pageId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public BrandPageRepository.BrandPage updateBrandPage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String pageId,
            @RequestParam String brandName,
            @RequestParam String departmentId,
            @RequestParam(required = false) List<String> viewerDepartmentIds,
            @RequestParam(required = false) List<String> viewerUserIds,
            @RequestParam(required = false) MultipartFile file) throws Exception {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "admin:brand-page:write");
        brandPageRepository.findActiveById(pageId)
                .orElseThrow(() -> new IllegalArgumentException("品牌 HTML 页面不存在"));
        var normalizedBrand = brandName == null ? "" : brandName.trim();
        if (normalizedBrand.isBlank() || normalizedBrand.length() > 160) {
            throw new IllegalArgumentException("品牌名称不能为空且不能超过 160 个字符");
        }
        var department = requireOrganizationDepartment(departmentId);
        if (!department.enabled()) {
            throw new IllegalArgumentException("不能关联已禁用的组织部门");
        }
        var viewers = validateBrandPageViewers(viewerDepartmentIds, viewerUserIds);
        String fileId = null;
        String fileName = null;
        if (file != null && !file.isEmpty()) {
            fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
            if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".html")) {
                throw new IllegalArgumentException("仅支持上传 .html 文件");
            }
            var stored = storageService.saveWithoutSizeLimit(
                    "brand_page_" + UUID.randomUUID().toString().replace("-", ""), null,
                    context.userId(), fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    file.getSize(), file.getInputStream());
            fileId = stored.fileId();
            fileName = stored.fileName();
        }
        var page = brandPageRepository.update(pageId, normalizedBrand, department.id(),
                fileId, fileName, context.userId(), viewers.departmentIds(), viewers.userIds());
        logOperation(context.userId(), "BRAND_HTML_UPDATE", "编辑品牌 HTML 页面",
                Map.of("brand", normalizedBrand, "departmentId", department.id(), "pageId", page.id(),
                        "viewerDepartmentCount", viewers.departmentIds().size(),
                        "viewerUserCount", viewers.userIds().size()));
        return page;
    }

    @DeleteMapping("/api/admin/brand-pages/{pageId}")
    @Transactional
    public Map<String, Object> deleteBrandPage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String pageId) {
        var context = requireContext(authorization);
        permissionService.requirePermission(context, "admin:brand-page:write");
        var page = brandPageRepository.findActiveById(pageId)
                .orElseThrow(() -> new IllegalArgumentException("品牌 HTML 页面不存在"));
        brandPageRepository.delete(page.id());
        logOperation(context.userId(), "BRAND_HTML_DELETE", "删除品牌 HTML 页面", Map.of("brand", page.brandName(), "pageId", page.id()));
        return Map.of("deleted", true, "id", page.id());
    }

    @PostMapping("/api/ai/chat/completions")
    public Map<String, Object> chat(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody AiGatewayRequest request) {
        var context = requireContext(authorization);
        var run = requireAccessibleRun(context, request.runId());
        requireRunToolKey(run, request.toolKey());
        permissionService.requireToolAccess(context, run.toolKey());
        var modelConfig = findModelConfigForTool(request.model(), "text", run.toolKey());
        request = new AiGatewayRequest(request.runId(), run.toolKey(), context.userId(), request.model(), request.params());
        var result = aiGatewayService.chat(request, modelConfig);
        var log = saveAiLog(request, run, "chat", result.promptTokens(), result.completionTokens(), result.status(), result.loggedResponse(), modelConfig);
        var response = new LinkedHashMap<>(result.clientBody());
        response.put("id", log.id());
        return response;
    }

    @PostMapping("/api/ai/images/generations")
    public Map<String, Object> images(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody AiGatewayRequest request) {
        var context = requireContext(authorization);
        var run = requireAccessibleRun(context, request.runId());
        requireRunToolKey(run, request.toolKey());
        permissionService.requireToolAccess(context, run.toolKey());
        var modelConfig = findModelConfigForTool(request.model(), "image", run.toolKey());
        request = new AiGatewayRequest(request.runId(), run.toolKey(), context.userId(), request.model(), request.params());
        var result = aiGatewayService.image(request, modelConfig);
        var log = saveAiLog(request, run, "image", result.promptTokens(), result.completionTokens(), result.status(), result.loggedResponse(), modelConfig);
        var response = new LinkedHashMap<>(result.clientBody());
        response.put("platformLogId", log.id());
        if ("success".equalsIgnoreCase(result.status())) {
            extractTaskId(result.clientBody()).ifPresent(taskId -> logRepository.bindAiTask(
                    taskId, run.runId(), run.toolKey(), run.projectId(), modelConfig.modelName()));
        }
        return response;
    }

    @GetMapping("/api/ai/tasks/{taskId}")
    public Map<String, Object> taskDetail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String taskId, @RequestParam(required = false) String modelName) {
        var context = requireContext(authorization);
        var binding = logRepository.findAiTaskBinding(taskId)
                .orElseThrow(() -> new ForbiddenException("无权查询该 AI 任务"));
        var run = requireAccessibleRun(context, binding.runId());
        if (!binding.toolKey().equals(run.toolKey()) || !binding.projectId().equals(run.projectId())) {
            throw new ForbiddenException("无权查询该 AI 任务");
        }
        permissionService.requireToolAccess(context, binding.toolKey());
        var config = findModelConfigForTool(binding.modelName(), "image", binding.toolKey());
        var result = "sub2api".equals(config.provider())?aiGatewayService.task(taskId,config,run.userId()):aiGatewayService.task(taskId, config);
        logOperation("system", "AI_TASK_QUERY", "查询 AI 异步任务", Map.of("taskId", taskId, "modelName", config.modelName(), "status", result.getOrDefault("status", "unknown")));
        return result;
    }

    @GetMapping("/api/ai/models")
    public List<Map<String, Object>> models() {
        return List.of(
                Map.of("name", "gpt-4.1", "type", "text", "enabled", true),
                Map.of("name", "gemini-3.1-flash-lite-image", "type", "image", "enabled", true));
    }

    @GetMapping("/api/admin/ai-call-logs")
    public Object aiCallLogs(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        if (page == null && pageSize == null) {
            return logRepository.listAiLogs();
        }
        int normalizedPage = PageResponse.normalizePage(page == null ? 1 : page);
        int normalizedSize = PageResponse.normalizePageSize(pageSize == null ? 10 : pageSize);
        return PageResponse.page(logRepository.listAiLogs(normalizedPage, normalizedSize), normalizedPage, normalizedSize, logRepository.countAiLogs());
    }

    @GetMapping("/api/public/stats")
    public Map<String, Object> publicStats() {
        return statsSummary();
    }

    @GetMapping("/api/stats/summary")
    public Map<String, Object> statsSummary() {
        var aiStats = logRepository.aggregateAiStats();
        var generatedImageCount = runSnapshot().stream()
                .mapToLong(run -> RunImageCounter.countImages(run.toolKey(), run.status(), run.outputData()))
                .sum();
        var failedRuns = runSnapshot().stream().filter(run -> "failed".equals(run.status())).count();
        var publicTools = toolConfigs.values().stream()
                .filter(tool -> !ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY.equals(tool.key()))
                .filter(tool -> !ToolAvailabilityPolicy.CLOUD_AI_TOOL_KEY.equals(tool.key()))
                .toList();
        var normalTools = publicTools.stream().filter(tool -> "normal".equals(tool.status())).count();
        var departmentsById = new LinkedHashMap<String, OrganizationDepartment>();
        organizationDepartments.forEach(department -> departmentsById.put(department.id(), department));
        var aiUsingDepartments = logRepository.successfulAiDepartmentIds().stream()
                .map(departmentId -> activeRootDepartmentId(departmentId, departmentsById))
                .filter(Objects::nonNull)
                .distinct()
                .count();
        var enabledUsers = userRepository.listUsers().stream().filter(com.yiqi.aitoolbench.user.UserRepository.SysUser::enabled).count();
        var callbackEnabledTools = publicTools.stream().filter(ToolConfig::callbackEnabled).count();
        var pendingDeployments = deploymentRequests.stream().filter(request -> !"approved".equals(request.status())).count();
        var aiGatewayCalls = aiStats.callCount();
        var aiGatewayCoverageRate = aiGatewayCalls == 0 ? 100 : 100;
        var callbackCoverageRate = publicTools.isEmpty() ? 0 : Math.round(callbackEnabledTools * 100.0 / publicTools.size());
        var result = new LinkedHashMap<String, Object>();
        result.put("toolCount", publicTools.size());
        result.put("normalToolCount", normalTools);
        result.put("departmentCount", departmentSeed.size());
        result.put("enabledDepartmentCount", aiUsingDepartments);
        result.put("userCount", userRepository.countUsers());
        result.put("enabledUserCount", enabledUsers);
        result.put("runCount", runs.size());
        result.put("projectCount", projects.stream()
                .filter(project -> !isSystemToolProject(project) && !isDeletedProject(project))
                .count());
        result.put("projectLinkCount", completedProjectResults().size());
        result.put("deploymentCount", deploymentRequests.size());
        result.put("pendingDeploymentCount", pendingDeployments);
        result.put("aiCallCount", aiStats.callCount());
        result.put("totalTokens", aiStats.totalTokens());
        result.put("totalCredits", aiStats.totalCredits());
        result.put("totalCost", aiStats.totalCost());
        result.put("generatedImageCount", generatedImageCount);
        result.put("failedRunCount", failedRuns);
        result.put("aiGatewayCoverageRate", aiGatewayCoverageRate);
        result.put("callbackCoverageRate", callbackCoverageRate);
        return result;
    }

    private String activeRootDepartmentId(String departmentId, Map<String, OrganizationDepartment> departmentsById) {
        var visited = new java.util.HashSet<String>();
        var current = departmentId;
        while (!isBlank(current) && visited.add(current)) {
            var department = departmentsById.get(current);
            if (department == null) return null;
            if (isBlank(department.parentId())) return department.enabled() ? department.id() : null;
            current = department.parentId();
        }
        return null;
    }

    @GetMapping("/api/admin/users")
    public Object adminUsers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String search) {
        if (page == null && pageSize == null) {
            return adminUserViews();
        }
        int normalizedPage = PageResponse.normalizePage(page == null ? 1 : page);
        int normalizedSize = PageResponse.normalizePageSize(pageSize == null ? 10 : pageSize);
        var items = userRepository.listUsers(normalizedPage, normalizedSize, search).stream().map(this::sysUserView).toList();
        return PageResponse.page(items, normalizedPage, normalizedSize, userRepository.countUsers(search));
    }

    private List<Map<String, Object>> adminUserViews() {
        return userRepository.listUsers().stream().map(this::sysUserView).toList();
    }

    private List<Map<String, Object>> adminUserDirectory() {
        return userRepository.listUsers().stream().map(user -> {
            var departmentKeys = userRepository.listDepartmentKeys(user.id());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.id());
            item.put("account", user.account());
            item.put("name", user.displayName());
            item.put("enabled", user.enabled());
            item.put("departmentKeys", departmentKeys);
            item.put("primaryDepartmentId", departmentKeys.isEmpty() ? null : departmentKeys.get(0));
            return item;
        }).toList();
    }

    private List<Map<String, Object>> brandPageViewerDirectory() {
        return userRepository.listUsers().stream().map(user -> {
            var departmentIds = userRepository.listDepartmentKeys(user.id());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.id());
            item.put("account", user.account());
            item.put("name", user.displayName());
            item.put("enabled", user.enabled());
            item.put("departmentIds", departmentIds);
            item.put("primaryDepartmentId", departmentIds.isEmpty() ? null : departmentIds.get(0));
            return item;
        }).toList();
    }

    private List<Map<String, Object>> projectMemberCandidates() {
        return userRepository.listUsers().stream()
                .filter(UserRepository.SysUser::enabled)
                .map(user -> {
                    var departmentIds = userRepository.listDepartmentKeys(user.id());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", user.id());
                    item.put("account", user.account());
                    item.put("name", user.displayName());
                    item.put("departmentIds", departmentIds);
                    item.put("canOwnProject", userRepository.listPermissionCodes(user.id()).contains("project:own"));
                    return item;
                })
                .toList();
    }

    private List<Map<String, Object>> adminRoles() {
        return userRepository.listRoles().stream().map(role -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", role.code());
            result.put("name", role.name());
            result.put("description", role.description());
            result.put("dataScope", role.dataScope());
            result.put("enabled", role.enabled());
            result.put("builtIn", role.builtIn());
            result.put("permissionVersion", role.permissionVersion());
            result.put("permissionCodes", role.permissionCodes());
            result.put("toolKeys", toolRepository.listRoleToolKeys(role.code()));
            return result;
        }).toList();
    }

    @PostMapping("/api/admin/users")
    @Transactional
    public Map<String, Object> createUser(@RequestBody CreateUserRequest request) {
        var account = firstNonBlank(request.account(), request.username());
        var name = firstNonBlank(request.name(), request.displayName());
        var roleIds = normalizeRoleIds(request.roleIds(), request.role());
        var role = firstRole(roleIds);
        if (isBlank(account) || isBlank(name) || isBlank(role)) {
            throw new IllegalArgumentException("账号、姓名、角色不能为空");
        }
        userRepository.requireRoleCodes(roleIds);
        var departmentKeys = normalizeDepartmentKeysForCreate(request.departmentKeys(), request.primaryDepartmentId());
        var created = authService.createUser(account, name, role, request.initialPassword());
        var id = String.valueOf(created.get("id"));
        userRepository.replaceUserRoles(id, roleIds);
        var user = new UserAccount(id, account, name, role, departmentKeys, "managed-by-sys-user", true, true, 0, null);
        logOperation("admin", "USER_CREATE", "新增用户", Map.of("userId", created.get("id"), "account", user.account));
        workbenchRepository.saveUser(UserAccountState.from(user));
        updateUserCacheAfterCommit(user);
        var result = new LinkedHashMap<>(userRepository.findById(id).map(sysUser -> sysUserView(sysUser, user))
                .orElseThrow(() -> new IllegalArgumentException("用户创建失败: " + id)));
        if (created.containsKey("temporaryPassword")) {
            result.put("temporaryPassword", created.get("temporaryPassword"));
        }
        return result;
    }

    @PutMapping("/api/admin/users/{userId}")
    @Transactional
    public Map<String, Object> updateUser(@PathVariable String userId, @RequestBody UpdateUserRequest request) {
        var sysUser = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        var name = request.name() == null ? sysUser.displayName() : request.name().trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("姓名不能为空");
        }
        var roleIds = normalizeRoleIds(request.roleIds(), null);
        if (roleIds.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个角色");
        }
        userRepository.requireRoleCodes(roleIds);
        var current = users.get(userId);
        var departmentKeys = normalizeDepartmentKeysForUpdate(request.departmentKeys(), request.primaryDepartmentId(), current);
        var updated = new UserAccount(
                userId,
                sysUser.account(),
                name,
                roleIds.get(0),
                departmentKeys,
                current == null ? "managed-by-sys-user" : current.password,
                sysUser.enabled(),
                sysUser.mustChangePassword(),
                sysUser.failedAttempts(),
                sysUser.lockedUntil());
        userRepository.updateDisplayName(userId, name);
        userRepository.replaceUserRoles(userId, roleIds);
        workbenchRepository.saveUser(UserAccountState.from(updated));
        logOperation("admin", "USER_UPDATE", "编辑用户", Map.of(
                "userId", userId,
                "roleIds", roleIds,
                "departmentKeys", departmentKeys,
                "primaryDepartmentId", departmentKeys.get(0)));
        updateUserCacheAfterCommit(updated);
        return userRepository.findById(userId).map(item -> sysUserView(item, updated))
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
    }

    @PostMapping("/api/admin/users/{userId}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable String userId) {
        var reset = authService.resetPassword(userId);
        var user = users.get(userId);
        if (user != null) {
            user.password = "managed-by-sys-user";
            user.mustChangePassword = true;
            user.failedAttempts = 0;
            user.lockedUntil = null;
        }
        logOperation("admin", "USER_RESET_PASSWORD", "重置用户密码", Map.of("userId", userId));
        saveState();
        return reset;
    }

    @PostMapping("/api/admin/users/{userId}/status")
    public Map<String, Object> updateUserStatus(@PathVariable String userId, @RequestBody UpdateUserStatusRequest request) {
        var result = authService.updateUserStatus(userId, request.enabled());
        var user = users.get(userId);
        if (user != null) {
            user.enabled = request.enabled();
        }
        logOperation("admin", "USER_STATUS", "修改用户状态", Map.of("userId", userId, "enabled", request.enabled()));
        saveState();
        return result;
    }

    @DeleteMapping("/api/admin/users/{userId}")
    public Map<String, Object> deleteUser(@PathVariable String userId) {
        userRepository.logicalDeleteUser(userId, "admin");
        users.remove(userId);
        logOperation("admin", "USER_DELETE", "删除用户", Map.of("userId", userId));
        return Map.of("deleted", true, "userId", userId);
    }

    @PutMapping("/api/admin/users/{userId}/roles")
    public Map<String, Object> updateUserRoles(@PathVariable String userId, @RequestBody UpdateUserRolesRequest request) {
        userRepository.replaceUserRoles(userId, request.roleIds() == null ? List.of() : request.roleIds());
        logOperation("admin", "USER_ROLES", "修改用户角色", Map.of("userId", userId, "roleIds", request.roleIds() == null ? List.of() : request.roleIds()));
        saveState();
        return userRepository.findById(userId).map(this::sysUserView)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
    }

    @PutMapping("/api/admin/tools/{toolKey}/status")
    public ToolConfig updateToolStatus(@PathVariable String toolKey, @RequestBody UpdateToolStatusRequest request) {
        var current = toolConfigs.get(toolKey);
        if (current == null) {
            throw new IllegalArgumentException("工具不存在: " + toolKey);
        }
        if (!List.of("normal", "maintenance", "unopened", "disabled").contains(request.status())) {
            throw new IllegalArgumentException("工具状态不合法");
        }
        var updated = new ToolConfig(current.key(), current.name(), current.desc(), current.departmentKey(), request.status(), current.theme(), current.callback(), current.callbackEnabled(), current.entryUrl());
        toolConfigs.put(toolKey, updated);
        logOperation("admin", "TOOL_STATUS", "修改工具状态", Map.of("toolKey", toolKey, "status", request.status()));
        saveState();
        return updated;
    }

    @GetMapping("/api/admin/tools")
    public Object adminTools(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        var items = toolConfigs.values().stream()
                .filter(tool -> !ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY.equals(tool.key()))
                .toList();
        return page == null && pageSize == null ? items : PageResponse.of(items, page == null ? 1 : page, pageSize == null ? 10 : pageSize);
    }

    @GetMapping("/api/admin/model-configs")
    public Object adminModelConfigs(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        var items = adminModelConfigViews();
        return page == null && pageSize == null ? items : PageResponse.of(items, page == null ? 1 : page, pageSize == null ? 10 : pageSize);
    }

    private List<Map<String, Object>> adminModelConfigViews() {
        return modelConfigs.values().stream().map(this::modelConfigView).toList();
    }

    @PutMapping("/api/admin/model-configs/{configId}")
    public Map<String, Object> updateModelConfig(@PathVariable String configId, @RequestBody UpdateModelConfigRequest request) {
        var current = modelConfigs.get(configId);
        if (current == null) {
            throw new IllegalArgumentException("模型配置不存在: " + configId);
        }
        ProviderOptionsPolicy.rejectSensitiveKeys(request.providerOptions());
        var updated = new AiModelConfig(
                current.id(),
                isBlank(request.name()) ? current.name() : request.name(),
                isBlank(request.provider()) ? current.provider() : request.provider(),
                isBlank(request.modelType()) ? current.modelType() : request.modelType(),
                updatedModelName(current, request.modelName()),
                updatedApiBaseUrl(current, request.apiBaseUrl()),
                updatedEncryptedApiKey(current, request.apiKey()),
                request.providerOptions() == null ? current.providerOptions() : request.providerOptions(),
                updatedTimeoutSeconds(current, request.timeoutSeconds()),
                updatedRetryCount(current, request.retryCount()),
                request.enabled() == null ? current.enabled() : request.enabled());
        modelConfigs.put(configId, updated);
        logOperation("admin", "MODEL_CONFIG_UPDATE", "修改模型配置", Map.of("configId", configId, "modelName", updated.modelName()));
        saveState();
        return modelConfigView(updated);
    }

    private String updatedModelName(AiModelConfig current, String modelName) {
        if (modelName == null) {
            return current.modelName();
        }
        var trimmed = modelName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        return trimmed;
    }

    private String updatedApiBaseUrl(AiModelConfig current, String apiBaseUrl) {
        if (isBlank(apiBaseUrl)) {
            return current.apiBaseUrl();
        }
        var trimmed = apiBaseUrl.trim();
        try {
            var uri = URI.create(trimmed);
            if (uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            return trimmed;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("API 中转地址必须是有效的 HTTP 或 HTTPS 地址");
        }
    }

    private int updatedTimeoutSeconds(AiModelConfig current, Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return current.timeoutSeconds();
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new IllegalArgumentException("超时时间必须在 1 到 600 秒之间");
        }
        return timeoutSeconds;
    }

    private int updatedRetryCount(AiModelConfig current, Integer retryCount) {
        if (retryCount == null) {
            return current.retryCount();
        }
        if (retryCount < 0 || retryCount > 5) {
            throw new IllegalArgumentException("重试次数必须在 0 到 5 次之间");
        }
        return retryCount;
    }

    private String updatedEncryptedApiKey(AiModelConfig current, String apiKey) {
        if (isBlank(apiKey)) {
            return current.encryptedApiKey();
        }
        if (apiKey.length() > 8192) {
            throw new IllegalArgumentException("Token 不能超过 8192 个字符");
        }
        return secretCrypto.encrypt(apiKey);
    }

    @GetMapping("/api/admin/operation-logs")
    public Object operationLogs(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        if (page == null && pageSize == null) {
            return logRepository.listOperationLogs();
        }
        int normalizedPage = PageResponse.normalizePage(page == null ? 1 : page);
        int normalizedSize = PageResponse.normalizePageSize(pageSize == null ? 10 : pageSize);
        return PageResponse.page(logRepository.listOperationLogs(normalizedPage, normalizedSize), normalizedPage, normalizedSize, logRepository.countOperationLogs());
    }

    @GetMapping(value = "/developer/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> openapi() {
        return Map.of(
                "openapi", "3.0.3",
                "info", Map.of("title", "一起互动 AI 工具台接入接口", "version", "0.1.0"),
                "paths", Map.of(
                        "/api/tool-runs/start", Map.of("post", Map.of("summary", "创建工具运行")),
                        "/api/tool-runs/{runId}/finish", Map.of("post", Map.of("summary", "完成工具运行")),
                        "/api/ai/chat/completions", Map.of("post", Map.of("summary", "统一文本模型调用")),
                        "/api/ai/images/generations", Map.of("post", Map.of("summary", "统一图片模型调用"))));
    }

    @GetMapping(value = "/developer/tool-sdk.js", produces = "application/javascript;charset=UTF-8")
    public String toolSdk() {
        return """
                window.YiqiToolbench = {
                  tokenKey: 'yiqi_ai_toolbench_token',
                  authHeaders() {
                    const token = window.localStorage ? window.localStorage.getItem(this.tokenKey) : '';
                    return token ? { Authorization: `Bearer ${token}` } : {};
                  },
                  async postJson(url, body) {
                    const response = await fetch(url, {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json', ...this.authHeaders() },
                      body: JSON.stringify(body)
                    });
                    if (!response.ok) throw new Error(await response.text());
                    return response.json();
                  },
                  startRun(payload) {
                    return this.postJson('/api/tool-runs/start', payload);
                  },
                  reportEvent(runId, payload) {
                    return this.postJson(`/api/tool-runs/${runId}/events`, payload);
                  },
                  finishRun(runId, payload) {
                    return this.postJson(`/api/tool-runs/${runId}/finish`, payload);
                  },
                  chat(payload) {
                    return this.postJson('/api/ai/chat/completions', payload);
                  },
                  image(payload) {
                    return this.postJson('/api/ai/images/generations', payload);
                  }
                };
                """;
    }

    @GetMapping("/developer/ai-tool-skill/latest")
    public Map<String, Object> latestSkill() {
        return Map.of(
                "name", "ai-tool-integration",
                "version", "0.1.1",
                "entry", "/developer/openapi.json",
                "sdk", "/developer/tool-sdk.js",
                "required", List.of("tool-manifest.json", "/api/tool-runs/start(projectId必填)", "/api/tool-runs/{runId}/finish", "/api/ai/*"));
    }

    @PostMapping("/developer/validate")
    public Map<String, Object> validateManifest(@RequestBody ToolManifest manifest) {
        var errors = new ArrayList<String>();
        validateManifestRules(manifest, errors);
        return Map.of("passed", errors.isEmpty(), "errors", errors);
    }

    @GetMapping("/api/admin/deployments")
    public Object deploymentRequests(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return page == null && pageSize == null
                ? deploymentRequests
                : PageResponse.of(deploymentRequests, page == null ? 1 : page, pageSize == null ? 10 : pageSize);
    }

    @PostMapping("/api/admin/deployments/request")
    public DeploymentRequest requestDeployment(@RequestBody ToolManifest manifest) {
        var errors = new ArrayList<String>();
        validateManifestRules(manifest, errors);
        var request = new DeploymentRequest(
                "deploy_" + UUID.randomUUID().toString().replace("-", ""),
                manifest.toolKey(),
                manifest.name(),
                isBlank(manifest.version()) ? "未声明" : manifest.version(),
                isBlank(manifest.owner()) ? "admin" : manifest.owner(),
                errors.isEmpty() ? "approved" : "rejected",
                errors,
                Instant.now());
        deploymentRequests.add(0, request);
        logOperation(request.requestedBy(), "DEPLOYMENT_GATE", "提交工具部署门禁申请",
                Map.of("toolKey", request.toolKey(), "status", request.status(), "errors", errors));
        saveState();
        return request;
    }

    private void validateManifestRules(ToolManifest manifest, List<String> errors) {
        if (isBlank(manifest.toolKey())) errors.add("toolKey 不能为空");
        if (isBlank(manifest.name())) errors.add("name 不能为空");
        if (isBlank(manifest.departmentKey())) errors.add("departmentKey 不能为空");
        if (isBlank(manifest.entry())) errors.add("entry 不能为空");
        if (!isAllowedIntegrationType(manifest.integrationType())) errors.add("integrationType 必须是 static、iframe 或 external");
        if (!isAllowedToolStatus(manifest.status())) errors.add("status 必须是 normal、maintenance、unopened 或 disabled");
        if ("normal".equals(manifest.status())) {
            if (!manifest.callbackEnabled()) errors.add("正式工具必须 callbackEnabled=true");
            if (!manifest.aiGatewayRequired()) errors.add("正式工具必须 aiGatewayRequired=true");
            if (!containsRequiredApi(manifest.requiredApis(), "/api/tool-runs/start")) errors.add("正式工具必须声明调用 /api/tool-runs/start");
            if (!containsRequiredApi(manifest.requiredApis(), "/api/tool-runs/{runId}/finish")) errors.add("正式工具必须声明调用 /api/tool-runs/{runId}/finish");
            if (!usesAiGateway(manifest.requiredApis())) errors.add("正式工具必须声明调用平台 AI 网关 /api/ai/*");
            if (manifest.directModelEndpoints() != null && !manifest.directModelEndpoints().isEmpty()) errors.add("正式工具不得直接调用外部模型地址，必须统一走平台 AI 网关");
            if ("external".equals(manifest.integrationType())
                    && !ToolAvailabilityPolicy.DETAIL_PAGE_TOOL_KEY.equals(manifest.toolKey())
                    && !ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY.equals(manifest.toolKey())) {
                errors.add("正式部署不接受 external 外链模式，需改造为 static 或 iframe 接入");
            }
        }
    }

    private boolean containsRequiredApi(List<String> apis, String required) {
        return apis != null && apis.stream().anyMatch(api -> required.equals(api));
    }

    private boolean usesAiGateway(List<String> apis) {
        if (apis == null) return false;
        return apis.stream().anyMatch(api -> api.startsWith("/api/ai/") || "/api/ai/*".equals(api));
    }

    private boolean isAllowedIntegrationType(String value) {
        return "static".equals(value) || "iframe".equals(value) || "external".equals(value);
    }

    private boolean isAllowedToolStatus(String value) {
        return "normal".equals(value) || "maintenance".equals(value) || "unopened".equals(value) || "disabled".equals(value);
    }

    private ToolConfig tool(String key, String name, String desc, String departmentKey, String status, String theme, boolean callbackEnabled) {
        return tool(key, name, desc, departmentKey, status, theme, callbackEnabled, "/tools/" + key);
    }

    private ToolConfig tool(String key, String name, String desc, String departmentKey, String status, String theme,
                            boolean callbackEnabled, String entryUrl) {
        return new ToolConfig(key, name, desc, departmentKey, status, theme, callbackEnabled, callbackEnabled, entryUrl);
    }

    private Map<String, UserAccount> seedUserAccounts() {
        var result = new LinkedHashMap<String, UserAccount>();
        result.put("u_001", new UserAccount("u_001", "admin", "张三", "ADMIN", List.of("ai-market", "ai-design", "ai-operation", "ai-hr"), "admin123", true, false, 0, null));
        result.put("u_002", new UserAccount("u_002", "market01", "李四", "MEMBER", List.of("ai-market"), "123456", true, true, 0, null));
        result.put("u_003", new UserAccount("u_003", "ops01", "王五", "LEADER", List.of("ai-operation"), "123456", true, false, 0, null));
        return result;
    }

    private Map<String, AiModelConfig> seedModelConfigs() {
        var result = new LinkedHashMap<String, AiModelConfig>();
        result.put("model_text_default", new AiModelConfig("model_text_default", "文本默认模型", "openai-compatible", "text", "gpt-4.1", "https://api-gateway.internal/v1", "", Map.of(), 60, 1, true));
        result.put("model_image_default", new AiModelConfig("model_image_default", "图片默认模型", "evolink", "image", "gemini-3.1-flash-lite-image", "", "", Map.of(), 120, 0, true));
        result.put("detailpage-relay-text", new AiModelConfig("detailpage-relay-text", "详情页中转文本", "sub2api", "text", "gpt-6-luna", "", "", Map.of(), 240, 0, true));
        result.put("detailpage-relay-image", new AiModelConfig("detailpage-relay-image", "详情页中转图片", "sub2api", "image", "gpt-image-2.5-flare", "", "", Map.of(), 600, 0, true));
        result.put("coze-copywriting", new AiModelConfig("coze-copywriting", "Coze 文案", "coze", "text", "coze-copywriting", "", "", Map.of(), 60, 1, false));
        result.put("doubao-vision-customer", new AiModelConfig("doubao-vision-customer", "豆包视觉", "doubao-openai-compatible", "vision", "doubao-vision-customer", "", "", Map.of(), 60, 1, false));
        result.put("doubao-chat-customer", new AiModelConfig("doubao-chat-customer", "豆包文本", "doubao-openai-compatible", "text", "doubao-chat-customer", "", "", Map.of(), 60, 1, false));
        result.put("evolink-customer-image", new AiModelConfig("evolink-customer-image", "Evolink 生图", "evolink", "image", "evolink-customer-image", "", "", Map.of(), 120, 0, false));
        return result;
    }

    private void ensureDefaultConfiguration() {
        seedUserAccounts().forEach(users::putIfAbsent);
        toolSeed.forEach(tool -> toolConfigs.putIfAbsent(tool.key(), tool));
        seedModelConfigs().forEach(modelConfigs::putIfAbsent);
        if (organizationDepartments.isEmpty()) {
            organizationDepartments.addAll(seedOrganizationDepartments());
        }
    }

    private List<OrganizationDepartment> seedOrganizationDepartments() {
        var now = Instant.now().toString();
        return List.of(
                new OrganizationDepartment("org_default_a", null, "默认部门 A", true, 10, now, now),
                new OrganizationDepartment("org_default_b", null, "默认部门 B", true, 20, now, now));
    }

    private ToolRun seedRun() {
        return new ToolRun(
                "run_20260705_001",
                "market-intelligence",
                "u_001",
                "proj_001",
                "success",
                "run",
                "宠物饮水器市场规模、竞品价格带、用户痛点",
                "大容量、静音、易清洗为核心机会点；建议优先验证一线及新一线城市女性用户。",
                Map.of("recommendations", List.of("主图强调静音马达和可拆洗结构", "价格带先测试 129-159 元")),
                Instant.parse("2026-07-05T02:30:12Z"),
                Instant.parse("2026-07-05T02:30:31Z"));
    }

    private List<ProjectBoard> seedProjects() {
        return List.of(
                new ProjectBoard(
                        "proj_001",
                        "6月宠物饮水器投放优化",
                        "张三",
                        "进行中",
                        List.of("张三", "李四", "王五"),
                        12,
                        "24.5K",
                        8,
                        "市场部结果显示用户关注静音和易清洗；运营部建议预算向一线及新一线城市倾斜。",
                Map.of("productName", "智能宠物饮水器", "departmentId", "ai-market", "brand", "川井", "category", "宠物用品", "salePrice", "129.00", "establishedDate", "2026-06-25", "sellingPoints", "静音马达、四层过滤、可拆洗"),
                        List.of("主图强调静音马达和可拆洗结构", "投放地域优先选择华东和华南", "价格带先测试 129-159 元"),
                        "2026-06-25T00:00:00Z"),
                new ProjectBoard(
                        "proj_002",
                        "5月男士冰丝裤推广",
                        "李四",
                        "已完成",
                        List.of("李四", "赵六"),
                        8,
                        "16.2K",
                        4,
                        "项目已完成，素材点击率高于历史均值。",
                Map.of("productName", "男士冰丝裤", "departmentId", "ai-design", "brand", "川井"),
                        List.of("保留凉感和速干卖点", "复用高点击素材版式"),
                        "2026-05-01T00:00:00Z"));
    }

    private ProjectResult seedProjectLink(ToolRun run) {
        return new ProjectResult(
                "result_seed_001",
                "proj_001",
                run.runId(),
                "市场情报",
                "张三",
                run.outputSummary(),
                Map.of("run", run),
                "ACTIVE",
                Instant.parse("2026-07-05T02:30:31Z"),
                null,
                null,
                null);
    }

    private DeploymentRequest seedDeploymentRequest() {
        return new DeploymentRequest(
                "deploy_seed_001",
                "demo-static-tool",
                "静态工具示例",
                "0.1.0",
                "系统",
                "approved",
                List.of(),
                Instant.parse("2026-07-05T02:31:00Z"));
    }

    private void restoreState() {
        runs.clear();
        runs.putAll(workbenchRepository.loadRuns());
        users.clear();
        workbenchRepository.loadUsers().forEach((id, user) -> users.put(id, user.toAccount()));
        toolConfigs.clear();
        toolConfigs.putAll(workbenchRepository.loadToolConfigs());
        modelConfigs.clear();
        modelConfigs.putAll(workbenchRepository.loadModelConfigs());
        projects.clear();
        projects.addAll(workbenchRepository.loadProjects());
        projectResults.clear();
        projectResults.addAll(workbenchRepository.loadProjectLinks().stream()
                .map(this::fromProjectLink)
                .toList());
        deploymentRequests.clear();
        deploymentRequests.addAll(workbenchRepository.loadDeploymentRequests());
        organizationDepartments.clear();
        organizationDepartments.addAll(workbenchRepository.loadOrganizationDepartments());
    }

    private void saveState() {
        runSnapshot().forEach(workbenchRepository::saveRun);
        toolConfigs.values().forEach(workbenchRepository::saveToolConfig);
        modelConfigs.values().forEach(workbenchRepository::saveModelConfig);
        projects.forEach(workbenchRepository::saveProject);
        projectResults.forEach(result -> {
            if ("REMOVED".equals(result.status())) {
                workbenchRepository.deleteProjectLink(result.id());
            } else {
                workbenchRepository.saveProjectLink(toProjectLink(result));
            }
        });
        deploymentRequests.forEach(workbenchRepository::saveDeploymentRequest);
        organizationDepartments.forEach(workbenchRepository::saveOrganizationDepartment);
    }

    private ToolRun requireRun(String runId) {
        var run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("runId 不存在: " + runId);
        }
        return run;
    }

    private AuthContext requireContext(String authorization) {
        return permissionService.requireContext(authorization);
    }

    private ToolRun requireAccessibleRun(String authorization, String runId) {
        return requireAccessibleRun(requireContext(authorization), runId);
    }

    private ToolRun requireAccessibleRun(AuthContext context, String runId) {
        var run = requireRun(runId);
        if (!canAccessRun(context, run)) {
            throw new ForbiddenException("无权访问运行记录: " + runId);
        }
        return run;
    }

    private void requireRunToolKey(ToolRun run, String requestedToolKey) {
        if (!isBlank(requestedToolKey) && !run.toolKey().equals(requestedToolKey)) {
            throw new ForbiddenException("AI 调用工具标识必须与运行记录一致");
        }
    }

    private String effectiveStartToolKey(AuthContext context, String requestedToolKey) {
        if (!context.toolSession()) {
            if (isBlank(requestedToolKey)) throw new IllegalArgumentException("toolKey 不能为空");
            return requestedToolKey;
        }
        if (!isBlank(requestedToolKey) && !context.toolKey().equals(requestedToolKey)) {
            throw new ForbiddenException("工具会话只能创建票据绑定的工具运行");
        }
        return context.toolKey();
    }

    private String effectiveStartProjectId(AuthContext context, String requestedProjectId) {
        if (!context.toolSession()) return requestedProjectId;
        if (!isBlank(requestedProjectId) && !context.projectId().equals(requestedProjectId)) {
            throw new ForbiddenException("工具会话只能绑定票据中的项目");
        }
        return context.projectId();
    }

    private List<ToolRun> visibleRuns(AuthContext context) {
        return runSnapshot().stream()
                .filter(run -> canAccessRun(context, run))
                .toList();
    }

    private List<ToolRun> runSnapshot() {
        return List.copyOf(runs.values());
    }

    private List<ProjectBoard> visibleProjects(AuthContext context) {
        if (!permissionService.hasPermission(context, "project:read")) {
            return List.of();
        }
        return projects.stream()
                .filter(project -> !isDeletedProject(project))
                .filter(project -> !isSystemToolProject(project))
                .filter(project -> canAccessProject(context, project))
                .map(this::withActualResultCount)
                .map(project -> projectView(context, project))
                .toList();
    }

    private Map<String, Map<String, Object>> runProjectDirectory(List<ToolRun> visibleRuns) {
        var result = new LinkedHashMap<String, Map<String, Object>>();
        visibleRuns.stream().map(ToolRun::projectId).filter(Objects::nonNull).distinct().forEach(projectId -> {
            var project = projects.stream().filter(item -> item.id().equals(projectId)).findFirst().orElse(null);
            if (project != null && !isSystemToolProject(project)) {
                result.put(projectId, Map.of(
                        "id", project.id(),
                        "name", project.name(),
                        "deleted", isDeletedProject(project)));
            }
        });
        return result;
    }

    private Map<String, Map<String, Object>> runUserDirectory(List<ToolRun> visibleRuns) {
        var result = new LinkedHashMap<String, Map<String, Object>>();
        visibleRuns.stream().map(ToolRun::userId).distinct().forEach(userId -> {
            var systemUser = userRepository.findById(userId).orElse(null);
            var legacyUser = users.get(userId);
            if (systemUser == null && legacyUser == null) return;
            var departmentKeys = systemUser == null
                    ? legacyUser.departmentKeys
                    : userRepository.listDepartmentKeys(userId);
            var item = new LinkedHashMap<String, Object>();
            item.put("id", userId);
            item.put("name", systemUser == null ? legacyUser.name : systemUser.displayName());
            item.put("primaryDepartmentId", departmentKeys.isEmpty() ? null : departmentKeys.get(0));
            result.put(userId, item);
        });
        return result;
    }

    private Map<String, Long> runTokenUsage(List<ToolRun> visibleRuns) {
        var visibleRunIds = visibleRuns.stream().map(ToolRun::runId).collect(java.util.stream.Collectors.toSet());
        var result = new LinkedHashMap<String, Long>();
        logRepository.listAiLogs().stream()
                .filter(log -> visibleRunIds.contains(log.runId()))
                .forEach(log -> result.merge(log.runId(), logRepository.tokenUsage(log), Long::sum));
        logRepository.relayTokensForRuns(visibleRunIds).forEach((runId, tokens) -> result.merge(runId, tokens, Long::sum));
        return result;
    }

    private Map<String, BigDecimal> runCreditUsage(List<ToolRun> visibleRuns) {
        var visibleRunIds = visibleRuns.stream().map(ToolRun::runId).collect(java.util.stream.Collectors.toSet());
        var result = new LinkedHashMap<String, BigDecimal>();
        logRepository.listAiLogs().stream()
                .filter(log -> visibleRunIds.contains(log.runId()))
                .forEach(log -> result.merge(log.runId(), logRepository.creditUsage(log), BigDecimal::add));
        return result;
    }

    private Map<String, BigDecimal> projectCreditUsage(List<ProjectBoard> visibleProjects) {
        var result = new LinkedHashMap<String, BigDecimal>();
        for (var project : visibleProjects) {
            var runIds = runSnapshot().stream()
                    .filter(run -> project.id().equals(run.projectId()))
                    .map(ToolRun::runId)
                    .toList();
            result.put(project.id(), logRepository.aggregateAiStatsForRuns(runIds).totalCredits());
        }
        return result;
    }

    private Map<String, String> runPrompts(List<ToolRun> visibleRuns) {
        var visibleRunIds = visibleRuns.stream().map(ToolRun::runId).collect(java.util.stream.Collectors.toSet());
        var result = new LinkedHashMap<String, String>();
        logRepository.listAiLogs().stream()
                .filter(log -> visibleRunIds.contains(log.runId()))
                .forEach(log -> {
                    if (result.containsKey(log.runId())) return;
                    var prompt = firstNonBlank(
                            valueAsText(log.requestParams().get("query")),
                            firstNonBlank(
                                    valueAsText(log.requestParams().get("prompt")),
                                    valueAsText(log.requestParams().get("input"))));
                    if (!isBlank(prompt)) result.put(log.runId(), prompt);
                });
        return result;
    }

    private String valueAsText(Object value) {
        return value instanceof String text ? text : null;
    }

    private List<ProjectResult> visibleProjectResults(AuthContext context) {
        return completedProjectResults().stream()
                .filter(result -> {
                    var project = requireProject(result.projectId());
                    return !isDeletedProject(project) && !isSystemToolProject(project)
                            && canAccessProject(context, project);
                })
                .toList();
    }

    private ProjectBoard withActualResultCount(ProjectBoard project) {
        var actualCount = (int) completedProjectResults().stream()
                .filter(result -> result.projectId().equals(project.id()))
                .count();
        var projectRuns = runSnapshot().stream()
                .filter(run -> project.id().equals(run.projectId()))
                .toList();
        var runIds = projectRuns.stream().map(ToolRun::runId).toList();
        var aiStats = logRepository.aggregateAiStatsForRuns(runIds);
        var generatedImageCount = projectRuns.stream()
                .mapToLong(run -> RunImageCounter.countImages(run.toolKey(), run.status(), run.outputData()))
                .sum();
        return project.withStats(actualCount, String.valueOf(aiStats.totalTokens()), Math.toIntExact(generatedImageCount));
    }

    private List<ProjectTask> visibleProjectTasks(AuthContext context) {
        var visibleProjectIds = visibleProjects(context).stream()
                .map(ProjectBoard::id)
                .toList();
        return projectRepository.listTasks(visibleProjectIds);
    }

    private List<ToolConfig> visibleTools(AuthContext context) {
        return toolConfigs.values().stream()
                .filter(tool -> !ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY.equals(tool.key()))
                .filter(tool -> !ToolAvailabilityPolicy.CLOUD_AI_TOOL_KEY.equals(tool.key()))
                .filter(tool -> !"disabled".equalsIgnoreCase(tool.status()))
                .filter(tool -> permissionService.hasToolAccess(context, tool.key()))
                .toList();
    }

    private boolean canAccessBrandPage(AuthContext context, BrandPageRepository.BrandPage page, boolean admin) {
        return BrandPageAccessPolicy.canAccess(admin, context.userId(),
                userRepository.listDepartmentKeys(context.userId()), page.createdBy(),
                page.viewerDepartmentIds(), page.viewerUserIds());
    }

    private BrandPageViewers validateBrandPageViewers(List<String> viewerDepartmentIds,
                                                       List<String> viewerUserIds) {
        var departmentIds = viewerDepartmentIds == null ? List.<String>of() : viewerDepartmentIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        var userIds = viewerUserIds == null ? List.<String>of() : viewerUserIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (departmentIds.isEmpty() && userIds.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个可查看部门或员工");
        }
        departmentIds.forEach(departmentId -> {
            var department = requireOrganizationDepartment(departmentId);
            if (!department.enabled()) {
                throw new IllegalArgumentException("查看权限不能包含已禁用部门");
            }
        });
        userIds.forEach(userId -> {
            var user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("查看权限员工不存在: " + userId));
            if (!user.enabled()) {
                throw new IllegalArgumentException("查看权限不能包含已禁用员工");
            }
        });
        return new BrandPageViewers(departmentIds, userIds);
    }

    private boolean canAccessRun(AuthContext context, ToolRun run) {
        if (!permissionService.hasProjectScope(context, run.projectId())
                || !permissionService.hasToolAccess(context, run.toolKey())) {
            return false;
        }
        if (permissionService.effectiveDataScope(context) == DataScope.ALL) {
            return true;
        }
        if (run.userId().equals(context.userId())) {
            return true;
        }
        var project = requireProject(run.projectId());
        if (ProjectAccessPolicy.canView(
                DataScope.PROJECT,
                context.userId(),
                List.of(),
                project.departmentId(),
                project.ownerUserId(),
                project.createdBy(),
                project.memberUserIds())) {
            return true;
        }
        return permissionService.effectiveDataScope(context) == DataScope.DEPARTMENT
                && permissionService.hasPermission(context, "run:department:read")
                && userRepository.listDepartmentKeys(context.userId()).contains(project.departmentId());
    }

    private boolean canAccessProject(AuthContext context, ProjectBoard project) {
        if (context.toolSession()) {
            // The bound project was revalidated during introspection; runtime access remains exact-project only.
            return permissionService.hasProjectScope(context, project.id());
        }
        var scope = permissionService.effectiveDataScope(context);
        return ProjectAccessPolicy.canView(
                scope,
                context.userId(),
                userRepository.listDepartmentKeys(context.userId()),
                project.departmentId(),
                project.ownerUserId(),
                project.createdBy(),
                project.memberUserIds());
    }

    private boolean canManageProject(AuthContext context, ProjectBoard project) {
        if (context.toolSession()) {
            return false;
        }
        return ProjectAccessPolicy.canManage(
                permissionService.hasPermission(context, "project:write"),
                permissionService.effectiveDataScope(context),
                context.userId(),
                project.ownerUserId());
    }

    private ProjectBoard projectView(AuthContext context, ProjectBoard project) {
        var ownerName = userRepository.findById(project.ownerUserId())
                .map(UserRepository.SysUser::displayName)
                .orElse(project.owner());
        var memberNames = project.memberUserIds().stream()
                .map(userId -> userRepository.findById(userId)
                        .map(UserRepository.SysUser::displayName)
                        .orElse(userId))
                .toList();
        return project.withPeople(ownerName, memberNames)
                .withCapabilities(
                        canManageProject(context, project),
                        ProjectAccessPolicy.canRun(context.userId(), project.status(), project.memberUserIds()));
    }

    private String requestedProjectDepartmentId(CreateProjectRequest request) {
        if (!isBlank(request.departmentId())) {
            return request.departmentId();
        }
        if (request.details() != null && request.details().get("departmentId") != null) {
            return String.valueOf(request.details().get("departmentId"));
        }
        return null;
    }

    private void requireAssignableProjectDepartment(AuthContext context, String departmentId) {
        if (isBlank(departmentId)) {
            throw new IllegalArgumentException("项目所属部门不能为空");
        }
        var department = requireOrganizationDepartment(departmentId);
        if (!department.enabled()) {
            throw new IllegalArgumentException("不能选择已禁用的项目部门");
        }
        if (permissionService.effectiveDataScope(context) != DataScope.ALL
                && !userRepository.listDepartmentKeys(context.userId()).contains(departmentId)) {
            throw new ForbiddenException("只能在本人所属部门创建或管理项目");
        }
    }

    private UserRepository.SysUser requireProjectOwner(String ownerUserId) {
        var owner = userRepository.findById(ownerUserId)
                .filter(UserRepository.SysUser::enabled)
                .orElseThrow(() -> new IllegalArgumentException("项目负责人不存在或已禁用"));
        if (!userRepository.listPermissionCodes(owner.id()).contains("project:own")) {
            throw new IllegalArgumentException("所选用户没有担任项目负责人的权限");
        }
        return owner;
    }

    private List<String> normalizeProjectMemberIds(
            String ownerUserId,
            List<String> requestedUserIds,
            List<String> legacyMemberNames) {
        var result = new java.util.LinkedHashSet<String>();
        result.add(ownerUserId);
        if (requestedUserIds != null) {
            result.addAll(requestedUserIds.stream().filter(value -> !isBlank(value)).toList());
        } else if (legacyMemberNames != null) {
            legacyMemberNames.forEach(name -> resolveUniqueUserIdByDisplayName(name).ifPresent(result::add));
        }
        if (result.size() > 200) {
            throw new IllegalArgumentException("单个项目成员不能超过 200 人");
        }
        for (var userId : result) {
            userRepository.findById(userId)
                    .filter(UserRepository.SysUser::enabled)
                    .orElseThrow(() -> new IllegalArgumentException("项目成员不存在或已禁用: " + userId));
        }
        return List.copyOf(result);
    }

    private List<String> projectMemberNames(List<String> memberUserIds) {
        return memberUserIds.stream()
                .map(userId -> userRepository.findById(userId)
                        .map(UserRepository.SysUser::displayName)
                        .orElseThrow(() -> new IllegalArgumentException("项目成员不存在: " + userId)))
                .toList();
    }

    private java.util.Optional<String> resolveUniqueUserIdByDisplayName(String displayName) {
        var matches = userRepository.listUsers().stream()
                .filter(user -> user.displayName().equals(displayName))
                .map(UserRepository.SysUser::id)
                .toList();
        return matches.size() == 1 ? java.util.Optional.of(matches.get(0)) : java.util.Optional.empty();
    }

    private void normalizeLegacyProjectIdentities() {
        if (projects.isEmpty()) {
            return;
        }
        var enabledDepartments = organizationDepartments.stream()
                .filter(OrganizationDepartment::enabled)
                .map(OrganizationDepartment::id)
                .toList();
        var fallbackOwnerId = userRepository.findById("u_admin")
                .filter(UserRepository.SysUser::enabled)
                .map(UserRepository.SysUser::id)
                .orElseGet(() -> userRepository.listUsers().stream()
                        .filter(UserRepository.SysUser::enabled)
                        .filter(user -> userRepository.listPermissionCodes(user.id()).contains("project:own"))
                        .map(UserRepository.SysUser::id)
                        .findFirst()
                        .orElse(null));
        if (fallbackOwnerId == null || enabledDepartments.isEmpty()) {
            return;
        }
        for (var index = 0; index < projects.size(); index++) {
            var project = projects.get(index);
            if (isSystemToolProject(project)) {
                var scopedUserId = project.id().substring(TOOL_SCOPE_PREFIX.length());
                var scopedUser = userRepository.findById(scopedUserId).orElse(null);
                if (scopedUser != null) {
                    projects.set(index, normalizeSystemToolProject(project, scopedUser));
                }
                continue;
            }
            var ownerUserId = !isBlank(project.ownerUserId()) && userRepository.findById(project.ownerUserId()).isPresent()
                    ? project.ownerUserId()
                    : resolveUniqueUserIdByDisplayName(project.owner()).orElse(fallbackOwnerId);
            var memberUserIds = new java.util.LinkedHashSet<String>();
            memberUserIds.add(ownerUserId);
            if (project.memberUserIds() != null) {
                project.memberUserIds().stream()
                        .filter(userId -> userRepository.findById(userId).isPresent())
                        .forEach(memberUserIds::add);
            }
            if (project.members() != null) {
                project.members().forEach(name ->
                        resolveUniqueUserIdByDisplayName(name).ifPresent(memberUserIds::add));
            }
            var departmentId = enabledDepartments.contains(project.departmentId())
                    ? project.departmentId()
                    : userRepository.listDepartmentKeys(ownerUserId).stream()
                            .filter(enabledDepartments::contains)
                            .findFirst()
                            .orElse(enabledDepartments.get(0));
            var details = new LinkedHashMap<>(project.details());
            details.put("departmentId", departmentId);
            var normalizedMemberIds = List.copyOf(memberUserIds);
            projects.set(index, project.withDetails(details)
                    .withPeople(userRepository.findById(ownerUserId)
                                    .map(UserRepository.SysUser::displayName)
                                    .orElse(project.owner()),
                            projectMemberNames(normalizedMemberIds))
                    .withAccessMetadata(departmentId, ownerUserId, normalizedMemberIds,
                            isBlank(project.createdBy()) ? ownerUserId : project.createdBy()));
        }
    }

    private UserAccount requireUser(String userId) {
        var user = users.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        return user;
    }

    private ProjectBoard requireProject(String projectId) {
        return projects.stream()
                .filter(project -> project.id().equals(projectId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
    }

    /** Card entry scope: a real project for ticket and run auditing, excluded from project UI. */
    @Transactional
    public synchronized String ensureToolScopeProject(AuthContext context) {
        if (context.toolSession()) throw new ForbiddenException("工具会话不能创建入口项目");
        var projectId = TOOL_SCOPE_PREFIX + context.userId();
        var user = userRepository.findById(context.userId())
                .filter(UserRepository.SysUser::enabled)
                .orElseThrow(() -> new ForbiddenException("账号不可用"));
        var existing = projects.stream().filter(project -> project.id().equals(projectId)).findFirst().orElse(null);
        if (existing != null) {
            if (!isSystemToolProject(existing)) {
                throw new IllegalStateException("工具入口项目冲突");
            }
            var normalized = normalizeSystemToolProject(existing, user);
            if (!normalized.equals(existing)) {
                replaceProject(projectId, normalized);
                workbenchRepository.saveProject(normalized);
            }
            return projectId;
        }
        var departmentId = userRepository.listDepartmentKeys(context.userId()).stream().findFirst()
                .orElseGet(() -> organizationDepartments.stream()
                        .filter(OrganizationDepartment::enabled)
                        .map(OrganizationDepartment::id)
                        .findFirst()
                        .orElse("system-tool-scope"));
        var now = Instant.now().toString();
        var project = new ProjectBoard(projectId, "个人工具运行", user.displayName(), "进行中",
                List.of(user.displayName()), 0, "0", 0, "工具入口运行范围",
                Map.of("systemManagedToolScope", true, "departmentId", departmentId), List.of(), now,
                departmentId, user.id(), List.of(user.id()), user.id(), false, true);
        workbenchRepository.saveProject(project);
        projects.add(project);
        return projectId;
    }

    private boolean isSystemToolProject(ProjectBoard project) {
        return project.id().startsWith(TOOL_SCOPE_PREFIX)
                && Boolean.TRUE.equals(project.details().get("systemManagedToolScope"));
    }

    private ProjectBoard normalizeSystemToolProject(ProjectBoard project, UserRepository.SysUser user) {
        var expectedProjectId = TOOL_SCOPE_PREFIX + user.id();
        if (!project.id().equals(expectedProjectId)) {
            throw new IllegalStateException("工具入口项目标识与用户不匹配");
        }
        var enabledDepartmentIds = organizationDepartments.stream()
                .filter(OrganizationDepartment::enabled)
                .map(OrganizationDepartment::id)
                .toList();
        var userDepartmentIds = userRepository.listDepartmentKeys(user.id()).stream()
                .filter(enabledDepartmentIds::contains)
                .toList();
        var departmentId = userDepartmentIds.contains(project.departmentId())
                ? project.departmentId()
                : userDepartmentIds.stream().findFirst()
                        .orElseGet(() -> enabledDepartmentIds.stream().findFirst().orElse("system-tool-scope"));
        var details = new LinkedHashMap<>(project.details());
        details.put("systemManagedToolScope", true);
        details.put("departmentId", departmentId);
        return new ProjectBoard(project.id(), "个人工具运行", user.displayName(), "进行中",
                List.of(user.displayName()), project.resultCount(), project.tokenUsage(), project.imageCount(),
                "工具入口运行范围", details, project.suggestions(), project.createdAt(), departmentId,
                user.id(), List.of(user.id()), user.id(), false, true);
    }

    private void requireUserManagedProject(ProjectBoard project) {
        if (isSystemToolProject(project)) {
            throw new ForbiddenException("个人工具项目由系统管理");
        }
    }

    private ProjectBoard requireActiveProject(String projectId) {
        var project = requireProject(projectId);
        if (isDeletedProject(project)) {
            throw new IllegalArgumentException("项目已删除: " + projectId);
        }
        return project;
    }

    private boolean isDeletedProject(ProjectBoard project) {
        return "已删除".equals(project.status());
    }

    private ProjectResult attachRunToProject(ProjectBoard project, ToolRun run, String category, String remark) {
        var existing = findProjectResult(run.runId());
        if (existing != null) {
            return existing;
        }
        var result = new ProjectResult(
                "result_" + UUID.randomUUID().toString().replace("-", ""),
                project.id(),
                run.runId(),
                isBlank(category) ? run.toolKey() : category,
                run.userId(),
                run.outputSummary() == null ? run.inputSummary() : run.outputSummary(),
                Map.of("run", run),
                "ACTIVE",
                Instant.now(),
                null,
                null,
                null);
        projectResults.add(0, result);
        replaceProject(project.id(), project.withResultCount(project.resultCount() + 1));
        return result;
    }

    private List<ProjectResult> activeProjectResults() {
        return projectResults.stream()
                .filter(result -> "ACTIVE".equals(result.status()))
                .toList();
    }

    private List<ProjectResult> completedProjectResults() {
        return activeProjectResults().stream()
                .filter(result -> ProjectResultPolicy.isCompleted(runs.get(result.runId())))
                .toList();
    }

    private ProjectResult findProjectResult(String runId) {
        return activeProjectResults().stream()
                .filter(result -> result.runId().equals(runId))
                .findFirst()
                .orElse(null);
    }

    private ProjectResult createProjectResult(ToolRun run) {
        var existing = findProjectResult(run.runId());
        if (existing != null) {
            return existing;
        }
        var project = requireProject(run.projectId());
        var result = new ProjectResult(
                "result_" + UUID.randomUUID().toString().replace("-", ""),
                project.id(),
                run.runId(),
                run.toolKey(),
                run.userId(),
                run.outputSummary() == null ? run.inputSummary() : run.outputSummary(),
                Map.of("run", run),
                "ACTIVE",
                Instant.now(),
                null,
                null,
                null);
        projectResults.add(0, result);
        replaceProject(project.id(), project.withResultCount(project.resultCount() + 1));
        return result;
    }

    private void replaceProjectResult(ProjectResult updated) {
        for (var i = 0; i < projectResults.size(); i++) {
            if (projectResults.get(i).id().equals(updated.id())) {
                projectResults.set(i, updated);
                return;
            }
        }
        throw new IllegalArgumentException("项目结果不存在: " + updated.id());
    }

    private ProjectResult fromProjectLink(ProjectResultLink link) {
        return new ProjectResult(
                link.id(),
                link.projectId(),
                link.runId(),
                link.category(),
                link.submittedBy(),
                link.outputSummary(),
                link.resultSnapshot(),
                "ACTIVE",
                link.linkedAt(),
                null,
                null,
                null);
    }

    private ProjectResultLink toProjectLink(ProjectResult result) {
        return new ProjectResultLink(
                result.id(),
                result.projectId(),
                result.runId(),
                result.toolKey(),
                "",
                result.submittedBy(),
                result.outputSummary(),
                result.resultSnapshot(),
                result.createdAt());
    }

    private AiModelConfig findModelConfig(String modelName, String modelType) {
        var exactMatch = modelConfigs.values().stream()
                .filter(config -> config.enabled())
                .filter(config -> config.modelName().equals(modelName))
                .findFirst();
        return exactMatch.orElseGet(() -> modelConfigs.values().stream()
                .filter(config -> config.enabled())
                .filter(config -> config.modelType().equals(modelType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到可用模型配置: " + modelName)));
    }

    private AiModelConfig findModelConfigForTool(String modelName, String modelType, String toolKey) {
        if (ToolAvailabilityPolicy.CLOUD_AI_TOOL_KEY.equals(toolKey)) {
            var provider = "text".equals(modelType) && "coze-copywriting".equals(modelName) ? "coze"
                    : "image".equals(modelType) && "gpt-image-2.5-flare".equals(modelName) ? "sub2api" : "";
            if (provider.isBlank()) throw new IllegalArgumentException("该工具不允许使用模型: " + modelName);
            return modelConfigs.values().stream()
                    .filter(AiModelConfig::enabled)
                    .filter(config -> provider.equals(config.provider()) && modelType.equals(config.modelType())
                            && modelName.equals(config.modelName()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("缺少已启用的模型配置: " + modelName));
        }
        if (!ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY.equals(toolKey)) {
            return findModelConfig(modelName, modelType);
        }
        boolean approvedDetailPageModel = ("text".equals(modelType) && "gpt-6-luna".equals(modelName))
                || ("image".equals(modelType) && "gpt-image-2.5-flare".equals(modelName));
        if (!approvedDetailPageModel) {
            throw new IllegalArgumentException("详情页仅允许使用已批准的 GPT 中转模型: " + modelName);
        }
        return modelConfigs.values().stream()
                .filter(AiModelConfig::enabled)
                .filter(config -> "sub2api".equals(config.provider()))
                .filter(config -> modelType.equals(config.modelType()))
                .filter(config -> config.modelName().equals(modelName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("详情页缺少已启用的中转模型配置: " + modelName));
    }

    private void replaceProject(String projectId, ProjectBoard updated) {
        for (var i = 0; i < projects.size(); i++) {
            if (projects.get(i).id().equals(projectId)) {
                projects.set(i, updated);
                return;
            }
        }
        throw new IllegalArgumentException("项目不存在: " + projectId);
    }

    private Map<String, Object> userView(UserAccount user) {
        return Map.of(
                "id", user.id,
                "account", user.account,
                "name", user.name,
                "role", user.role,
                "roleIds", userRepository.listUserRoleCodes(user.id),
                "departmentKeys", user.departmentKeys,
                "enabled", user.enabled,
                "mustChangePassword", user.mustChangePassword,
                "locked", user.lockedUntil != null && Instant.now().isBefore(user.lockedUntil));
    }

    private Map<String, Object> sysUserView(UserRepository.SysUser user) {
        return sysUserView(user, users.get(user.id()));
    }

    private Map<String, Object> sysUserView(UserRepository.SysUser user, UserAccount legacyUser) {
        var roleIds = userRepository.listUserRoleCodes(user.id());
        var departmentKeys = userRepository.listDepartmentKeys(user.id());
        var result = new LinkedHashMap<String, Object>();
        result.put("id", user.id());
        result.put("account", user.account());
        result.put("name", user.displayName());
        result.put("role", roleIds.isEmpty() ? "" : roleIds.get(0));
        result.put("roleIds", roleIds);
        result.put("departmentKeys", departmentKeys);
        result.put("primaryDepartmentId", departmentKeys.isEmpty() ? null : departmentKeys.get(0));
        result.put("enabled", user.enabled());
        result.put("mustChangePassword", user.mustChangePassword());
        result.put("locked", user.lockedUntil() != null && Instant.now().isBefore(user.lockedUntil()));
        return result;
    }

    private Map<String, Object> modelConfigView(AiModelConfig config) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", config.id());
        result.put("name", config.name());
        result.put("provider", config.provider());
        result.put("modelType", config.modelType());
        result.put("modelName", config.modelName());
        result.put("apiBaseUrl", config.apiBaseUrl());
        result.put("apiKeyMasked", maskKey(secretCrypto.decrypt(config.encryptedApiKey())));
        result.put("providerOptions", ProviderOptionsPolicy.sanitized(config.providerOptions()));
        result.put("timeoutSeconds", config.timeoutSeconds());
        result.put("retryCount", config.retryCount());
        result.put("enabled", config.enabled());
        return result;
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 6) {
            return "******";
        }
        return key.substring(0, Math.min(3, key.length())) + "-****" + key.substring(key.length() - 4);
    }

    private void logOperation(String operator, String action, String summary, Map<String, Object> detail) {
        logRepository.insertOperationLog(new OperationLog(
                "op_" + UUID.randomUUID().toString().replace("-", ""),
                operator,
                action,
                summary,
                detail == null ? Map.of() : redact(detail),
                Instant.now()));
    }

    private AiCallLog saveAiLog(AiGatewayRequest request, ToolRun run, String modelType, int promptTokens,
            int completionTokens, String status, Map<String, Object> response, AiModelConfig modelConfig) {
        var billingUnit = "image".equals(modelType) || "coze".equalsIgnoreCase(modelConfig.provider())
                ? "credits" : "tokens";
        var loggedResponse = new LinkedHashMap<String, Object>();
        if (response != null) loggedResponse.putAll(response);
        var platformMetadata = new LinkedHashMap<String,Object>();
        if (response != null && response.get("_platform") instanceof Map<?,?> metadata)
            metadata.forEach((key,value) -> platformMetadata.put(String.valueOf(key),value));
        platformMetadata.put("provider",modelConfig.provider());
        platformMetadata.put("billingUnit",billingUnit);
        loggedResponse.put("_platform",platformMetadata);
        var estimatedCost = "tokens".equals(billingUnit) && !"sub2api".equals(modelConfig.provider())
                ? BigDecimal.valueOf((promptTokens + completionTokens) * 0.00002)
                : BigDecimal.ZERO;
        var log = new AiCallLog(
                "log_" + UUID.randomUUID().toString().replace("-", ""),
                run.runId(),
                run.toolKey(),
                run.userId(),
                request.model(),
                modelType,
                "sub2api".equals(modelConfig.provider()) ? Map.of("auditSource","relay") : redact(request.params()),
                loggedResponse,
                promptTokens,
                completionTokens,
                promptTokens + completionTokens,
                estimatedCost,
                status,
                Instant.now());
        var facts = new LinkedHashMap<String,Object>();
        facts.put("legacyLogId",log.id());facts.put("projectId",run.projectId());facts.put("provider",modelConfig.provider());facts.put("model",modelConfig.modelName());facts.put("modelType",modelType);
        com.yiqi.aitoolbench.usage.AiUsageCaptureFilter.annotate(facts);
        // Full call content belongs to the encrypted usage journal, not a second plaintext audit.
        var metering = new LinkedHashMap<String,Object>();
        metering.put("_platform",platformMetadata);
        if(loggedResponse.get("usage")!=null)metering.put("usage",loggedResponse.get("usage"));
        if(loggedResponse.get("completionResponse") instanceof Map<?,?> completion && completion.get("data") instanceof Map<?,?> data && data.get("usage")!=null)
            metering.put("completionResponse",Map.of("data",Map.of("usage",data.get("usage"))));
        long imageCount=0;
        for(String key:List.of("images","data","results"))if(loggedResponse.get(key) instanceof java.util.Collection<?> items){imageCount=items.size();break;}
        platformMetadata.put("imageCount",imageCount);
        logRepository.insertAiLog(new AiCallLog(log.id(),log.runId(),log.toolKey(),log.userId(),log.modelName(),log.modelType(),
                Map.of("auditSource","usage-records"),com.yiqi.aitoolbench.usage.UsagePayload.cleanMap(metering),log.promptTokens(),log.completionTokens(),log.totalTokens(),log.costEstimate(),log.status(),log.createdAt()));
        return log;
    }

    private java.util.Optional<String> extractTaskId(Map<String, Object> response) {
        if (response == null) {
            return java.util.Optional.empty();
        }
        for (var key : List.of("taskId", "task_id", "id")) {
            var value = response.get(key);
            if (value != null && !value.toString().isBlank()) {
                return java.util.Optional.of(value.toString());
            }
        }
        return java.util.Optional.empty();
    }

    private Map<String, Object> redact(Map<String, Object> value) {
        if (value == null) return Map.of();
        var redacted = new LinkedHashMap<String, Object>();
        value.forEach((key, raw) -> {
            var lower = key.toLowerCase();
            if (lower.contains("apikey") || lower.contains("authorization") || lower.contains("password") || lower.contains("token") || lower.contains("cookie")) {
                redacted.put(key, "***REDACTED***");
            } else {
                redacted.put(key, raw);
            }
        });
        return redacted;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private String firstRole(List<String> roleIds) {
        return roleIds == null || roleIds.isEmpty() ? null : roleIds.get(0);
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private int nextOrganizationSort() {
        return organizationDepartments.stream().mapToInt(OrganizationDepartment::sortOrder).max().orElse(0) + 10;
    }

    private Map<String, String> organizationParentMap() {
        var result = new LinkedHashMap<String, String>();
        organizationDepartments.forEach(department -> result.put(department.id(), department.parentId()));
        return result;
    }

    private List<String> normalizeDepartmentKeysForCreate(List<String> departmentKeys, String primaryDepartmentId) {
        Set<String> enabledIds = organizationDepartments.stream()
                .filter(OrganizationDepartment::enabled)
                .map(OrganizationDepartment::id)
                .collect(java.util.stream.Collectors.toSet());
        if ((departmentKeys == null || departmentKeys.isEmpty()) && isBlank(primaryDepartmentId)) {
            var defaultDepartment = organizationDepartments.stream()
                    .filter(OrganizationDepartment::enabled)
                    .sorted(java.util.Comparator.comparingInt(OrganizationDepartment::sortOrder))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("没有可分配的启用部门"));
            return List.of(defaultDepartment.id());
        }
        var resolvedPrimary = isBlank(primaryDepartmentId) && departmentKeys != null && !departmentKeys.isEmpty()
                ? departmentKeys.get(0)
                : primaryDepartmentId;
        return UserDepartmentSelection.normalize(departmentKeys, resolvedPrimary, enabledIds);
    }

    private List<String> normalizeDepartmentKeysForUpdate(List<String> departmentKeys, String primaryDepartmentId, UserAccount current) {
        Set<String> assignableIds = organizationDepartments.stream()
                .filter(OrganizationDepartment::enabled)
                .map(OrganizationDepartment::id)
                .collect(java.util.stream.Collectors.toSet());
        if (current != null) {
            assignableIds.addAll(current.departmentKeys);
        }
        return UserDepartmentSelection.normalize(departmentKeys, primaryDepartmentId, assignableIds);
    }

    private void updateUserCacheAfterCommit(UserAccount user) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                users.put(user.id, user);
            }
        });
    }

    private List<String> normalizeRoleIds(List<String> roleIds, String fallbackRole) {
        var result = new ArrayList<String>();
        if (roleIds != null) {
            roleIds.stream().filter(id -> !isBlank(id)).distinct().forEach(result::add);
        }
        if (result.isEmpty() && !isBlank(fallbackRole)) {
            result.add(fallbackRole);
        }
        return List.copyOf(result);
    }

    private OrganizationDepartment requireOrganizationDepartment(String departmentId) {
        return organizationDepartments.stream()
                .filter(item -> item.id().equals(departmentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("组织部门不存在: " + departmentId));
    }

    private void replaceOrganizationDepartment(OrganizationDepartment updated) {
        for (var i = 0; i < organizationDepartments.size(); i++) {
            if (organizationDepartments.get(i).id().equals(updated.id())) {
                organizationDepartments.set(i, updated);
                workbenchRepository.saveOrganizationDepartment(updated);
                return;
            }
        }
        throw new IllegalArgumentException("组织部门不存在: " + updated.id());
    }

    private String tokenFromAuthorization(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length())
                : null;
    }

    private record BrandPageViewers(List<String> departmentIds, List<String> userIds) {}

    public record LoginRequest(String account, String password) {}
    public record ChangePasswordRequest(@NotBlank String userId, @NotBlank String oldPassword, @NotBlank String newPassword) {}
    public record CreateUserRequest(String account, String username, String name, String displayName, String role, List<String> roleIds, List<String> departmentKeys, String primaryDepartmentId, String initialPassword) {}
    public record UpdateUserRequest(String name, List<String> roleIds, List<String> departmentKeys, String primaryDepartmentId) {}
    public record UpdateUserStatusRequest(boolean enabled) {}
    public record UpdateUserRolesRequest(List<String> roleIds) {}
    public record OrganizationDepartmentRequest(String name, String parentId) {}
    public record UpdateDepartmentStatusRequest(boolean enabled) {}
    public record UpdateToolStatusRequest(String status) {}
    public record CreateProjectRequest(
            String name,
            String departmentId,
            String ownerUserId,
            List<String> memberUserIds,
            String owner,
            List<String> members,
            String summary,
            Map<String, Object> details) {}
    public record UpdateProjectStatusRequest(String status) {}
    public record CreateProjectTaskRequest(String title, String toolKey, String assigneeUserId, Integer sortOrder) {}
    public record UpdateProjectTaskStatusRequest(String status) {}
    public record UpdateModelConfigRequest(String name, String provider, String modelType, String modelName, String apiBaseUrl, String apiKey, Map<String, Object> providerOptions, Integer timeoutSeconds, Integer retryCount, Boolean enabled) {}
    public record Department(String key, String name, String icon, boolean reserved) {}
    public record OrganizationDepartment(String id, String parentId, String name, boolean enabled, int sortOrder, String createdAt, String updatedAt) {}
    public record ToolConfig(String key, String name, String desc, String departmentKey, String status, String theme,
                             boolean callback, boolean callbackEnabled, String entryUrl) {}
    public record StartRunRequest(String toolKey, String userId, String projectId, String inputSummary) {}
    public record FinishRunRequest(@NotBlank String status, String outputSummary, Map<String, Object> outputData) {}
    public record FileUploadRequest(@NotBlank String runId, @NotBlank String fileName,
                                    String contentType, long size, String sourceUrl) {}
    public record ProjectLinkRequest(@NotBlank String projectId, @NotBlank String runId, String remark, String category) {}
    public record AiGatewayRequest(@NotBlank String runId, String toolKey, String userId, @NotBlank String model, Map<String, Object> params) {}
    public record ToolRun(String runId, String toolKey, String userId, String projectId, String status, String eventType, String inputSummary, String outputSummary, Map<String, Object> outputData, Instant startedAt, Instant finishedAt) {}
    public record AiCallLog(String id, String runId, String toolKey, String userId, String modelName, String modelType, Map<String, Object> requestParams, Map<String, Object> responseBody, int promptTokens, int completionTokens, int totalTokens, BigDecimal costEstimate, String status, Instant createdAt) {}
    public record ProjectBoard(
            String id,
            String name,
            String owner,
            String status,
            List<String> members,
            int resultCount,
            String tokenUsage,
            int imageCount,
            String summary,
            Map<String, Object> details,
            List<String> suggestions,
            String createdAt,
            String departmentId,
            String ownerUserId,
            List<String> memberUserIds,
            String createdBy,
            boolean canManage,
            boolean canRun) {
        public ProjectBoard(
                String id, String name, String owner, String status, List<String> members, int resultCount,
                String tokenUsage, int imageCount, String summary, Map<String, Object> details,
                List<String> suggestions, String createdAt) {
            this(id, name, owner, status, members, resultCount, tokenUsage, imageCount, summary, details,
                    suggestions, createdAt, projectDepartment(details), null, List.of(), null, false, false);
        }

        public ProjectBoard withStatus(String value) {
            return new ProjectBoard(id, name, owner, value, members, resultCount, tokenUsage, imageCount,
                    summary, details, suggestions, createdAt, departmentId, ownerUserId, memberUserIds,
                    createdBy, canManage, canRun);
        }

        public ProjectBoard withResultCount(int value) {
            return new ProjectBoard(id, name, owner, status, members, value, tokenUsage, imageCount, summary,
                    details, suggestions, createdAt, departmentId, ownerUserId, memberUserIds, createdBy,
                    canManage, canRun);
        }

        public ProjectBoard withStats(int results, String tokens, int images) {
            return new ProjectBoard(id, name, owner, status, members, results, tokens, images, summary, details,
                    suggestions, createdAt, departmentId, ownerUserId, memberUserIds, createdBy, canManage, canRun);
        }

        public ProjectBoard withDetails(Map<String, Object> value) {
            return new ProjectBoard(id, name, owner, status, members, resultCount, tokenUsage, imageCount,
                    summary, value, suggestions, createdAt, departmentId, ownerUserId, memberUserIds,
                    createdBy, canManage, canRun);
        }

        public ProjectBoard withContent(String valueName, String valueOwner, List<String> valueMembers,
                                        String valueSummary, Map<String, Object> valueDetails) {
            return new ProjectBoard(id, valueName, valueOwner, status, valueMembers, resultCount,
                    tokenUsage, imageCount, valueSummary, valueDetails, suggestions, createdAt, departmentId,
                    ownerUserId, memberUserIds, createdBy, canManage, canRun);
        }

        public ProjectBoard withAccessMetadata(
                String valueDepartmentId,
                String valueOwnerUserId,
                List<String> valueMemberUserIds,
                String valueCreatedBy) {
            return new ProjectBoard(id, name, owner, status, members, resultCount, tokenUsage, imageCount,
                    summary, details, suggestions, createdAt, valueDepartmentId, valueOwnerUserId,
                    List.copyOf(valueMemberUserIds), valueCreatedBy, canManage, canRun);
        }

        public ProjectBoard withPeople(String valueOwner, List<String> valueMembers) {
            return new ProjectBoard(id, name, valueOwner, status, List.copyOf(valueMembers), resultCount,
                    tokenUsage, imageCount, summary, details, suggestions, createdAt, departmentId, ownerUserId,
                    memberUserIds, createdBy, canManage, canRun);
        }

        public ProjectBoard withCapabilities(boolean valueCanManage, boolean valueCanRun) {
            return new ProjectBoard(id, name, owner, status, members, resultCount, tokenUsage, imageCount,
                    summary, details, suggestions, createdAt, departmentId, ownerUserId, memberUserIds,
                    createdBy, valueCanManage, valueCanRun);
        }

        private static String projectDepartment(Map<String, Object> details) {
            if (details == null || details.get("departmentId") == null) {
                return null;
            }
            return String.valueOf(details.get("departmentId"));
        }
    }
    public record ProjectResult(String id, String projectId, String runId, String toolKey, String submittedBy, String outputSummary, Map<String, Object> resultSnapshot, String status, Instant createdAt, String removedBy, Instant removedAt, String removeReason) {}
    public record ProjectResultLink(String id, String projectId, String runId, String category, String remark, String submittedBy, String outputSummary, Map<String, Object> resultSnapshot, Instant linkedAt) {}
    public record ToolManifest(String toolKey, String name, String departmentKey, String entry, String integrationType, String status, boolean callbackEnabled, boolean aiGatewayRequired, List<String> requiredApis, List<String> directModelEndpoints, String version, String owner) {}
    public record DeploymentRequest(String id, String toolKey, String name, String version, String requestedBy, String status, List<String> errors, Instant createdAt) {}
    public record OperationLog(String id, String operator, String action, String summary, Map<String, Object> detail, Instant createdAt) {}
    public record UserAccountState(String id, String account, String name, String role, List<String> departmentKeys, String password, boolean enabled, boolean mustChangePassword, int failedAttempts, Instant lockedUntil) {
        private static UserAccountState from(UserAccount user) {
            return new UserAccountState(user.id, user.account, user.name, user.role, user.departmentKeys, user.password, user.enabled, user.mustChangePassword, user.failedAttempts, user.lockedUntil);
        }

        private UserAccount toAccount() {
            return new UserAccount(id, account, name, role, departmentKeys, password, enabled, mustChangePassword, failedAttempts, lockedUntil);
        }
    }

    private static final class UserAccount {
        private final String id;
        private final String account;
        private final String name;
        private final String role;
        private final List<String> departmentKeys;
        private String password;
        private boolean enabled;
        private boolean mustChangePassword;
        private int failedAttempts;
        private Instant lockedUntil;

        private UserAccount(String id, String account, String name, String role, List<String> departmentKeys, String password, boolean enabled, boolean mustChangePassword, int failedAttempts, Instant lockedUntil) {
            this.id = id;
            this.account = account;
            this.name = name;
            this.role = role;
            this.departmentKeys = departmentKeys == null ? List.of() : List.copyOf(departmentKeys);
            this.password = password;
            this.enabled = enabled;
            this.mustChangePassword = mustChangePassword;
            this.failedAttempts = failedAttempts;
            this.lockedUntil = lockedUntil;
        }
    }
}
