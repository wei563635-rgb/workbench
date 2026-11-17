package com.yiqi.aitoolbench.newchat;

import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.security.ForbiddenException;
import com.yiqi.aitoolbench.security.UnauthorizedException;
import com.yiqi.aitoolbench.toolsession.ToolSessionRepository;
import com.yiqi.aitoolbench.user.UserRepository;
import com.yiqi.aitoolbench.tool.ToolRepository;
import com.yiqi.aitoolbench.tool.ToolAvailabilityPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 无项目登录身份，不能用作工作台 AI、项目或管理接口的工具凭证。 */
@Service
public class NewChatLoginService {
    private final JdbcTemplate jdbc;
    private final ToolSessionRepository sourceSessions;
    private final UserRepository users;
    private final ToolRepository tools;
    private final SecureRandom random = new SecureRandom();

    public NewChatLoginService(JdbcTemplate jdbc, ToolSessionRepository sourceSessions, UserRepository users, ToolRepository tools) {
        this.jdbc = jdbc;
        this.sourceSessions = sourceSessions;
        this.users = users;
        this.tools = tools;
        jdbc.execute("""
                create table if not exists new_chat_login_ticket (
                  token_hash varchar(64) primary key, user_id varchar(80) not null,
                  source_session_id varchar(120) not null, session_version int not null,
                  expires_at varchar(40) not null, consumed_at varchar(40))
                """);
        jdbc.execute("""
                create table if not exists new_chat_login_session (
                  token_hash varchar(64) primary key, user_id varchar(80) not null,
                  source_session_id varchar(120) not null, session_version int not null,
                  expires_at varchar(40) not null)
                """);
    }

    public Ticket issue(AuthContext actor, Instant now) {
        if (actor.toolSession() || actor.mustChangePassword()) throw new ForbiddenException("请先登录工作台并完成密码设置");
        requireToolAccess(actor.userId());
        var source = sourceSessions.findActiveSourceSessionId(actor.token(), actor.userId(), actor.sessionVersion(), now)
                .orElseThrow(this::invalid);
        var ticket = opaque("new_chat_ticket_");
        var expires = now.plusSeconds(60);
        jdbc.update("insert into new_chat_login_ticket values (?,?,?,?,?,null)",
                hash(ticket), actor.userId(), source, actor.sessionVersion(), expires.toString());
        return new Ticket(ticket, expires);
    }

    @Transactional
    public Exchange exchange(String ticket, Instant now) {
        if (ticket == null || ticket.length() > 256 || !ticket.startsWith("new_chat_ticket_")) throw invalid();
        var stored = jdbc.query("select * from new_chat_login_ticket where token_hash=? and consumed_at is null",
                (rs, row) -> new Stored(rs.getString("user_id"), rs.getString("source_session_id"),
                        rs.getInt("session_version"), Instant.parse(rs.getString("expires_at"))), hash(ticket))
                .stream().findFirst().orElseThrow(this::invalid);
        validate(stored, now);
        // 主键查询与条件更新，票据并发兑换至多成功一次；索引查找 O(log n)。
        if (jdbc.update("update new_chat_login_ticket set consumed_at=? where token_hash=? and consumed_at is null",
                now.toString(), hash(ticket)) != 1) throw invalid();
        var token = opaque("new_chat_session_");
        var expires = now.plus(Duration.ofHours(8));
        jdbc.update("insert into new_chat_login_session values (?,?,?,?,?)",
                hash(token), stored.userId(), stored.sourceSessionId(), stored.version(), expires.toString());
        return new Exchange(token, stored.userId(), "new-chat", expires);
    }

    public Identity context(String token, Instant now) {
        if (token == null || token.length() > 256 || !token.startsWith("new_chat_session_")) throw invalid();
        var stored = jdbc.query("select * from new_chat_login_session where token_hash=?",
                (rs, row) -> new Stored(rs.getString("user_id"), rs.getString("source_session_id"),
                        rs.getInt("session_version"), Instant.parse(rs.getString("expires_at"))), hash(token))
                .stream().findFirst().orElseThrow(this::invalid);
        validate(stored, now);
        var user = users.findById(stored.userId()).filter(UserRepository.SysUser::enabled).orElseThrow(this::invalid);
        return new Identity(true, user.id(), user.account(), user.displayName(), users.listDepartmentKeys(user.id()),
                users.listActiveUserRoleCodes(user.id()), "new-chat", stored.expiresAt());
    }

    private void validate(Stored stored, Instant now) {
        if (!now.isBefore(stored.expiresAt()) || !sourceSessions.sourceSessionActive(
                stored.sourceSessionId(), stored.userId(), stored.version(), now)) throw invalid();
        // 签发、兑换和每次身份读取均查询实时授权，撤权不等待会话到期。
        requireToolAccess(stored.userId());
    }

    private void requireToolAccess(String userId) {
        if (!tools.isEnabled(ToolAvailabilityPolicy.NEW_CHAT_TOOL_KEY)
                || !tools.hasToolAccess(userId, ToolAvailabilityPolicy.NEW_CHAT_TOOL_KEY)) {
            throw new ForbiddenException("无权使用工具: new-chat");
        }
    }

    private String opaque(String prefix) {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("无法生成登录凭证摘要"); }
    }

    private UnauthorizedException invalid() { return new UnauthorizedException("登录凭证无效或已过期，请从工作台重新进入"); }
    private record Stored(String userId, String sourceSessionId, int version, Instant expiresAt) {}
    public record Ticket(String ticket, Instant expiresAt) {}
    public record Exchange(String toolSession, String userId, String toolKey, Instant expiresAt) {}
    public record Identity(boolean active, String userId, String account, String name, List<String> departmentKeys,
            List<String> roleCodes, String toolKey, Instant expiresAt) {}
}
