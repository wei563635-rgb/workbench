package com.yiqi.aitoolbench.relay;

import com.yiqi.aitoolbench.ApiController.OperationLog;
import com.yiqi.aitoolbench.LogRepository;
import com.yiqi.aitoolbench.security.ForbiddenException;
import com.yiqi.aitoolbench.security.PermissionService;
import com.yiqi.aitoolbench.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class RelayKeyController {
    private final RelayIdentityService identities;
    private final PermissionService permissions;
    private final UserRepository users;
    private final LogRepository logs;

    public RelayKeyController(RelayIdentityService identities, PermissionService permissions,
            UserRepository users, LogRepository logs) {
        this.identities = identities;
        this.permissions = permissions;
        this.users = users;
        this.logs = logs;
    }

    @RequestMapping(path = "/api/relay/me/key", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE})
    public Object personalKeyUnavailable() {
        throw new ForbiddenException("客户端 Key 由管理员统一管理");
    }

    @GetMapping("/api/admin/users/{userId}/relay-client-key")
    public Object status(@PathVariable String userId, HttpServletRequest request, HttpServletResponse response) {
        authorizedActor(request, "admin:user:read");
        response.setHeader("Cache-Control", "no-store");
        var user = existingUser(userId);
        var status = new java.util.LinkedHashMap<>(identities.status(user.id()));
        status.put("userEnabled", user.enabled());
        return status;
    }

    @PostMapping("/api/admin/users/{userId}/relay-client-key")
    public Object rotate(@PathVariable String userId, HttpServletRequest request, HttpServletResponse response) {
        var actorId = authorizedActor(request, "admin:user:write");
        var user = existingUser(userId);
        if (!user.enabled()) throw new IllegalStateException("用户已禁用，不能生成客户端 Key");
        response.setHeader("Cache-Control", "no-store");
        var result = identities.rotateClient(user.id());
        audit(actorId, user.id(), "RELAY_CLIENT_KEY_ROTATE");
        return result;
    }

    @DeleteMapping("/api/admin/users/{userId}/relay-client-key")
    public Object revoke(@PathVariable String userId, HttpServletRequest request) {
        var actorId = authorizedActor(request, "admin:user:write");
        var user = existingUser(userId);
        identities.revokeClient(user.id());
        audit(actorId, user.id(), "RELAY_CLIENT_KEY_REVOKE");
        return Map.of("revoked", true);
    }

    private String authorizedActor(HttpServletRequest request, String permission) {
        var context = permissions.requireContext(request.getHeader("Authorization"));
        permissions.requirePermission(context, permission);
        return context.userId();
    }

    private UserRepository.SysUser existingUser(String userId) {
        return users.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    private void audit(String actorId, String targetUserId, String action) {
        logs.insertOperationLog(new OperationLog("op_" + UUID.randomUUID(), actorId, action,
                "管理员中转客户端 Key 操作", Map.of("targetUserId", targetUserId), Instant.now()));
    }
}
