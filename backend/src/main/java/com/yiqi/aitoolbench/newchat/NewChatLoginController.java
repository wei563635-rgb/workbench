package com.yiqi.aitoolbench.newchat;

import com.yiqi.aitoolbench.security.ForbiddenException;
import com.yiqi.aitoolbench.security.PermissionService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tool-sessions")
public class NewChatLoginController {
    private final NewChatLoginService service;
    private final PermissionService permissions;
    private final byte[] credential;
    private final String entryUrl;

    public NewChatLoginController(NewChatLoginService service, PermissionService permissions,
            @Value("${app.tool-sessions.service-credential:}") String credential,
            @Value("${app.new-chat.entry-url:}") String entryUrl) {
        this.service = service;
        this.permissions = permissions;
        this.credential = credential.getBytes(StandardCharsets.UTF_8);
        this.entryUrl = entryUrl;
    }

    @PostMapping("/new-chat-entry")
    public ResponseEntity<?> entry(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var actor = permissions.requireContext(authorization);
        if (credential.length == 0 || entryUrl.isBlank()) return unavailable();
        URI base;
        try { base = URI.create(entryUrl.endsWith("/") ? entryUrl : entryUrl + "/"); }
        catch (IllegalArgumentException ex) { return unavailable(); }
        boolean loopback = "http".equals(base.getScheme()) && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost() == null ? "" : base.getHost());
        if ((!"https".equals(base.getScheme()) && !loopback) || base.getHost() == null
                || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) return unavailable();
        var ticket = service.issue(actor, Instant.now());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "entryUrl", base.resolve("platform-entry").toString() + "?ticket=" + ticket.ticket(),
                "expiresAt", ticket.expiresAt()));
    }

    @PostMapping("/new-chat-exchange")
    public ResponseEntity<?> exchange(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ExchangeRequest request) {
        requireService(authorization);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.exchange(request.ticket(), Instant.now()));
    }

    @PostMapping("/new-chat-context")
    public ResponseEntity<?> context(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Tool-Session", required = false) String session) {
        requireService(authorization);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.context(session, Instant.now()));
    }

    private void requireService(String authorization) {
        var candidate = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        if (credential.length == 0 || !MessageDigest.isEqual(credential, candidate.getBytes(StandardCharsets.UTF_8)))
            throw new ForbiddenException("服务身份无效");
    }

    private ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("code", "NEW_CHAT_NOT_CONFIGURED", "message", "new chat 入口尚未配置"));
    }
    public record ExchangeRequest(String ticket) {}
}
