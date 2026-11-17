package com.yiqi.aitoolbench.admin;

import com.yiqi.aitoolbench.ApiController.OperationLog;
import com.yiqi.aitoolbench.LogRepository;
import com.yiqi.aitoolbench.security.DataScope;
import com.yiqi.aitoolbench.security.PermissionCatalog;
import com.yiqi.aitoolbench.tool.ToolRepository;
import com.yiqi.aitoolbench.user.UserRepository;
import com.yiqi.aitoolbench.user.UserRepository.RoleRecord;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
public class AdminController {
    private final UserRepository userRepository;
    private final ToolRepository toolRepository;
    private final LogRepository logRepository;

    public AdminController(UserRepository userRepository, ToolRepository toolRepository, LogRepository logRepository) {
        this.userRepository = userRepository;
        this.toolRepository = toolRepository;
        this.logRepository = logRepository;
    }

    @GetMapping("/api/admin/permissions")
    public List<PermissionCatalog.PermissionDefinition> permissions() {
        return PermissionCatalog.PERMISSIONS;
    }

    @GetMapping("/api/admin/roles")
    public Object roles(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        if (page == null && pageSize == null) {
            return userRepository.listRoles().stream().map(this::roleView).toList();
        }
        int normalizedPage = PageResponse.normalizePage(page == null ? 1 : page);
        int normalizedSize = PageResponse.normalizePageSize(pageSize == null ? 10 : pageSize);
        var items = userRepository.listRoles(normalizedPage, normalizedSize).stream().map(this::roleView).toList();
        return PageResponse.page(items, normalizedPage, normalizedSize, userRepository.countRoles());
    }

    @PostMapping("/api/admin/roles")
    public Map<String, Object> createRole(@RequestBody RoleRequest request) {
        validateRole(request);
        toolRepository.validateToolKeys(request.toolKeys());
        var role = userRepository.createRole(
                request.code(),
                request.name(),
                request.description(),
                request.dataScope(),
                request.permissionCodes() == null ? List.of() : request.permissionCodes());
        toolRepository.replaceRoleToolPermissions(role.code(), request.toolKeys() == null ? List.of() : request.toolKeys());
        logOperation("ROLE_CREATE", "新增角色", Map.of("roleCode", role.code()));
        return roleView(userRepository.findRole(role.code()).orElse(role));
    }

    @PutMapping("/api/admin/roles/{roleCode}")
    public Map<String, Object> updateRole(@PathVariable String roleCode, @RequestBody RoleRequest request) {
        validateRoleUpdate(request);
        toolRepository.validateToolKeys(request.toolKeys());
        var role = userRepository.updateRole(
                roleCode,
                request.name(),
                request.description(),
                request.dataScope(),
                request.permissionCodes());
        if (request.toolKeys() != null) {
            toolRepository.replaceRoleToolPermissions(roleCode, request.toolKeys());
        }
        logOperation("ROLE_UPDATE", "修改角色", Map.of("roleCode", roleCode));
        return roleView(userRepository.findRole(roleCode).orElse(role));
    }

    @PostMapping("/api/admin/roles/{roleCode}/status")
    public Map<String, Object> updateRoleStatus(@PathVariable String roleCode, @RequestBody RoleStatusRequest request) {
        var role = userRepository.updateRoleStatus(roleCode, request.enabled());
        logOperation("ROLE_STATUS", "修改角色状态", Map.of("roleCode", roleCode, "enabled", request.enabled()));
        return roleView(role);
    }

    @DeleteMapping("/api/admin/roles/{roleCode}")
    public Map<String, Object> deleteRole(@PathVariable String roleCode) {
        userRepository.logicalDeleteRole(roleCode, "admin");
        logOperation("ROLE_DELETE", "删除角色", Map.of("roleCode", roleCode));
        return Map.of("deleted", true, "roleCode", roleCode);
    }

    private Map<String, Object> roleView(RoleRecord role) {
        var result = new LinkedHashMap<String, Object>();
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
    }

    private void validateRole(RoleRequest request) {
        if (request == null || isBlank(request.code()) || isBlank(request.name()) || isBlank(request.dataScope())) {
            throw new IllegalArgumentException("角色 code、name、dataScope 不能为空");
        }
        if (!request.code().matches("[a-zA-Z0-9_:-]{3,80}")) {
            throw new IllegalArgumentException("角色 code 只允许字母、数字、下划线、冒号和短横线");
        }
        validateDataScope(request.dataScope());
    }

    private void validateRoleUpdate(RoleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.dataScope() != null) {
            validateDataScope(request.dataScope());
        }
    }

    private void validateDataScope(String dataScope) {
        if (DataScope.from(dataScope).name().equals(dataScope)) {
            return;
        }
        throw new IllegalArgumentException("dataScope 必须是 ALL、DEPARTMENT、PROJECT 或 SELF");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void logOperation(String action, String summary, Map<String, Object> detail) {
        logRepository.insertOperationLog(new OperationLog(
                "op_" + UUID.randomUUID().toString().replace("-", ""),
                "admin",
                action,
                summary,
                detail,
                Instant.now()));
    }

    public record RoleRequest(@NotBlank String code, @NotBlank String name, String description, @NotBlank String dataScope, List<String> permissionCodes, List<String> toolKeys) {}
    public record RoleStatusRequest(boolean enabled) {}
}
