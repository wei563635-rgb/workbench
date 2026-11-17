package com.yiqi.aitoolbench.toolsession;

import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.tool.ToolRepository;
import com.yiqi.aitoolbench.security.ForbiddenException;
import com.yiqi.aitoolbench.security.UnauthorizedException;
import com.yiqi.aitoolbench.tool.ToolAvailabilityPolicy;
import com.yiqi.aitoolbench.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ToolSessionService {
    private static final Duration TICKET_TTL = Duration.ofSeconds(60);

    private final ToolSessionRepository repository;
    private final ToolRepository toolRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration sessionTtl;

    @Autowired
    public ToolSessionService(
            ToolSessionRepository repository, ToolRepository toolRepository, UserRepository userRepository) {
        this(repository, toolRepository, userRepository, Clock.systemUTC(), new SecureRandom(), Duration.ofHours(8));
    }

    ToolSessionService(ToolSessionRepository repository, Clock clock, SecureRandom random, Duration sessionTtl) {
        this(repository, null, null, clock, random, sessionTtl);
    }

    ToolSessionService(ToolSessionRepository repository, ToolRepository toolRepository, UserRepository userRepository,
            Clock clock, SecureRandom random, Duration sessionTtl) {
        this.repository = repository;
        this.toolRepository = toolRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.random = random;
        this.sessionTtl = sessionTtl;
    }

    public IssuedTicket issue(AuthContext context, String toolKey, String projectId) {
        if (context == null || blank(toolKey) || blank(projectId)) {
            throw new IllegalArgumentException("票据请求无效");
        }
        if (context.toolSession()) {
            throw new IllegalArgumentException("工具会话不能签发票据");
        }
        var now = clock.instant();
        var sourceSessionId = digest(context.token());
        if (secured()) {
            sourceSessionId = repository.findActiveSourceSessionId(
                    context.token(), context.userId(), context.sessionVersion(), now)
                    .orElseThrow(() -> new IllegalArgumentException("登录态无效或已过期"));
            if (!toolRepository.isEnabled(toolKey) || !toolRepository.hasToolAccess(context.userId(), toolKey)) {
                throw new IllegalArgumentException("工具不可用或无权访问");
            }
            if (!projectRunnable(context.userId(), projectId)) {
                throw new IllegalArgumentException("项目不可用或无权访问");
            }
        }
        var raw = opaque("tool_ticket_");
        var id = "tkt_" + UUID.randomUUID().toString().replace("-", "");
        repository.insertTicket(new ToolSessionRepository.StoredTicket(
                id, digest(raw), context.userId(), context.account(), context.displayName(), context.sessionVersion(),
                context.permissions(), toolKey, projectId, sourceSessionId, now.plus(TICKET_TTL), null, now));
        return new IssuedTicket(id, raw, now.plus(TICKET_TTL));
    }

    @Transactional
    public ExchangedSession exchange(String rawTicket, String expectedToolKey, Instant now) {
        if (blank(rawTicket) || !rawTicket.startsWith("tool_ticket_") || blank(expectedToolKey)) {
            throw invalidTicket();
        }
        var ticket = repository.findTicketByHash(digest(rawTicket)).orElseThrow(this::invalidTicket);
        if (!expectedToolKey.equals(ticket.toolKey())) {
            throw new ForbiddenException("票据与工具不匹配");
        }
        if (ticket.consumedAt() != null || !now.isBefore(ticket.expiresAt()) || !ticketValid(ticket, now)
                || !repository.consume(ticket.id(), now)) {
            throw invalidTicket();
        }
        var rawSession = opaque("tool_session_");
        var expiresAt = now.plus(sessionTtl);
        repository.insertSession(digest(rawSession), ticket, expiresAt, now);
        return new ExchangedSession(rawSession, ticket.userId(), ticket.toolKey(), ticket.projectId(), expiresAt);
    }

    public ToolSessionContext introspect(String rawSession, Instant now) {
        if (blank(rawSession) || !rawSession.startsWith("tool_session_")) {
            throw invalidSession();
        }
        var stored = repository.findSessionByHash(digest(rawSession)).orElseThrow(this::invalidSession);
        var current = userRepository.findById(stored.userId())
                .filter(UserRepository.SysUser::enabled)
                .orElseThrow(this::invalidSession);
        if (stored.revokedAt() != null || !now.isBefore(stored.expiresAt())
                || !repository.sourceSessionActive(stored.sourceSessionId(), stored.userId(), stored.sessionVersion(), now)
                || !toolRepository.isEnabled(stored.toolKey())
                || !toolRepository.hasToolAccess(stored.userId(), stored.toolKey())
                || !projectRunnable(current.id(), stored.projectId())) {
            throw invalidSession();
        }
        var permissions = userRepository.listPermissionCodes(current.id()).stream()
                .filter(code -> !code.startsWith("admin:"))
                .toList();
        var roles = userRepository.listUserRoleCodes(current.id());
        var restrictedRole = roles.stream().filter(role -> !"admin".equalsIgnoreCase(role))
                .findFirst().orElse("member");
        return new ToolSessionContext(current.id(), current.account(), current.displayName(), permissions,
                restrictedRole, userRepository.listDepartmentKeys(current.id()),
                stored.toolKey(), stored.projectId(), stored.expiresAt());
    }

    /** 仅向详情页服务端提供当前身份与组织；详情页功能权限仍由详情页自行管理。 */
    public DetailPageContext detailPageContext(String rawSession, Instant now) {
        var scoped = introspect(rawSession, now);
        if (!ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY.equals(scoped.toolKey())) {
            throw new ForbiddenException("工具会话与详情页不匹配");
        }
        var roleCodes = userRepository.listActiveUserRoleCodes(scoped.userId());
        return new DetailPageContext(scoped.userId(), scoped.account(), scoped.displayName(),
                scoped.departmentKeys(), roleCodes, scoped.toolKey(), scoped.projectId(), scoped.expiresAt());
    }

    private boolean ticketValid(ToolSessionRepository.StoredTicket ticket, Instant now) {
        if (!secured()) {
            return true;
        }
        var current = userRepository.findById(ticket.userId()).filter(UserRepository.SysUser::enabled);
        return current.isPresent() && (repository.sourceSessionActive(
                ticket.sourceSessionId(), ticket.userId(), ticket.sessionVersion(), now)
                && toolRepository.isEnabled(ticket.toolKey())
                && toolRepository.hasToolAccess(ticket.userId(), ticket.toolKey())
                && projectRunnable(current.get().id(), ticket.projectId()));
    }

    private boolean projectRunnable(String userId, String projectId) {
        return repository.projectRunnable(projectId, userId);
    }

    private boolean secured() {
        return toolRepository != null && userRepository != null;
    }

    static String digest(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String opaque(String prefix) {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private UnauthorizedException invalidTicket() {
        return new UnauthorizedException("票据无效或已过期");
    }

    private UnauthorizedException invalidSession() {
        return new UnauthorizedException("工具会话无效或已过期");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record IssuedTicket(String id, String ticket, Instant expiresAt) {}

    public record ExchangedSession(
            String sessionToken, String userId, String toolKey, String projectId, Instant expiresAt) {}

    public record ToolSessionContext(String userId, String account, String displayName, List<String> permissions,
            String role, List<String> departmentKeys, String toolKey, String projectId, Instant expiresAt) {
        public ToolSessionContext(String userId, String account, String displayName, List<String> permissions,
                String toolKey, String projectId, Instant expiresAt) {
            this(userId, account, displayName, permissions, "", List.of(), toolKey, projectId, expiresAt);
        }
    }

    public record DetailPageContext(String userId, String account, String displayName, List<String> departmentKeys,
            List<String> roleCodes, String toolKey, String projectId, Instant expiresAt) {}
}
