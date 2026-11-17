package com.yiqi.aitoolbench.toolsession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ToolSessionRepository {
    private final JdbcTemplate jdbc;

    public ToolSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
                create table if not exists tool_session_ticket (
                  id varchar(120) primary key, token_hash varchar(64) not null unique,
                  user_id varchar(80) not null, account varchar(120) not null,
                  display_name varchar(120) not null, session_version int not null,
                  permission_codes text not null, tool_key varchar(120) not null,
                  project_id varchar(120) not null, source_session_id varchar(120) not null,
                  expires_at varchar(40) not null, consumed_at varchar(40), created_at varchar(40) not null)
                """);
        jdbc.execute("""
                create table if not exists tool_session (
                  id varchar(120) primary key, token_hash varchar(64) not null unique,
                  user_id varchar(80) not null, account varchar(120) not null,
                  display_name varchar(120) not null, session_version int not null,
                  permission_codes text not null, tool_key varchar(120) not null,
                  project_id varchar(120) not null, source_session_id varchar(120) not null,
                  expires_at varchar(40) not null, revoked_at varchar(40), created_at varchar(40) not null)
                """);
    }

    public void insertTicket(StoredTicket value) {
        jdbc.update("""
                insert into tool_session_ticket
                (id,token_hash,user_id,account,display_name,session_version,permission_codes,tool_key,project_id,
                 source_session_id,expires_at,consumed_at,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,null,?)
                """, value.id(), value.tokenHash(), value.userId(), value.account(), value.displayName(),
                value.sessionVersion(), String.join("\n", value.permissions()), value.toolKey(), value.projectId(),
                value.sourceSessionId(), value.expiresAt().toString(), value.createdAt().toString());
    }

    public Optional<StoredTicket> findTicketById(String id) {
        return queryTicket("id", id);
    }

    public Optional<StoredTicket> findTicketByHash(String hash) {
        return queryTicket("token_hash", hash);
    }

    private Optional<StoredTicket> queryTicket(String column, String value) {
        return jdbc.query("""
                select id,token_hash,user_id,account,display_name,session_version,permission_codes,tool_key,project_id,
                       source_session_id,expires_at,consumed_at,created_at
                from tool_session_ticket where %s=?
                """.formatted(column), (rs, row) -> new StoredTicket(
                rs.getString("id"), rs.getString("token_hash"), rs.getString("user_id"), rs.getString("account"),
                rs.getString("display_name"), rs.getInt("session_version"), permissions(rs.getString("permission_codes")),
                rs.getString("tool_key"), rs.getString("project_id"), rs.getString("source_session_id"),
                Instant.parse(rs.getString("expires_at")), instant(rs.getString("consumed_at")),
                Instant.parse(rs.getString("created_at"))), value).stream().findFirst();
    }

    public boolean consume(String id, Instant now) {
        // 原子条件更新是一次性票据的不变量；主键定位通常为 O(log n)。
        return jdbc.update("update tool_session_ticket set consumed_at=? where id=? and consumed_at is null",
                now.toString(), id) == 1;
    }

    public void insertSession(String hash, StoredTicket ticket, Instant expiresAt, Instant now) {
        jdbc.update("""
                insert into tool_session
                (id,token_hash,user_id,account,display_name,session_version,permission_codes,tool_key,project_id,
                 source_session_id,expires_at,revoked_at,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,null,?)
                """, "tls_" + UUID.randomUUID().toString().replace("-", ""), hash, ticket.userId(), ticket.account(),
                ticket.displayName(), ticket.sessionVersion(), String.join("\n", ticket.permissions()), ticket.toolKey(),
                ticket.projectId(), ticket.sourceSessionId(), expiresAt.toString(), now.toString());
    }

    public int countSessions() {
        return jdbc.queryForObject("select count(*) from tool_session", Integer.class);
    }

    public Optional<String> findActiveSourceSessionId(
            String rawToken, String userId, int sessionVersion, Instant now) {
        return jdbc.query("""
                select s.id from sys_session s join sys_user u on u.id=s.user_id
                where s.session_token=? and s.user_id=? and s.user_session_version=?
                  and u.enabled=true and u.session_version=s.user_session_version
                  and s.revoked_at is null and s.expires_at>?
                """, (rs, row) -> rs.getString("id"), rawToken, userId, sessionVersion, now.toString())
                .stream().findFirst();
    }

    public boolean sourceSessionActive(
            String sourceSessionId, String userId, int sessionVersion, Instant now) {
        Integer count = jdbc.queryForObject("""
                select count(*) from sys_session s join sys_user u on u.id=s.user_id
                where s.id=? and s.user_id=? and s.user_session_version=?
                  and u.enabled=true and u.session_version=s.user_session_version
                  and s.revoked_at is null and s.expires_at>?
                """, Integer.class, sourceSessionId, userId, sessionVersion, now.toString());
        return count != null && count == 1;
    }

    public boolean projectRunnable(String projectId, String userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from project_board p
                where p.id=? and p.status='进行中' and exists (
                    select 1 from project_member m
                    where m.project_id=p.id and m.member_user_id=?)
                """, Integer.class, projectId, userId);
        return count != null && count == 1;
    }

    public Optional<StoredSession> findSessionByHash(String hash) {
        return jdbc.query("""
                select token_hash,user_id,account,display_name,session_version,permission_codes,tool_key,project_id,
                       source_session_id,expires_at,revoked_at
                from tool_session where token_hash=?
                """, (rs, row) -> new StoredSession(rs.getString("token_hash"), rs.getString("user_id"),
                rs.getString("account"), rs.getString("display_name"), rs.getInt("session_version"),
                permissions(rs.getString("permission_codes")), rs.getString("tool_key"), rs.getString("project_id"),
                rs.getString("source_session_id"), Instant.parse(rs.getString("expires_at")),
                instant(rs.getString("revoked_at"))), hash).stream().findFirst();
    }

    private static List<String> permissions(String value) {
        return value == null || value.isBlank() ? List.of() : value.lines().toList();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    public record StoredTicket(String id, String tokenHash, String userId, String account, String displayName,
            int sessionVersion, List<String> permissions, String toolKey, String projectId, String sourceSessionId,
            Instant expiresAt, Instant consumedAt, Instant createdAt) {}

    public record StoredSession(String tokenHash, String userId, String account, String displayName,
            int sessionVersion, List<String> permissions, String toolKey, String projectId, String sourceSessionId,
            Instant expiresAt, Instant revokedAt) {}
}
