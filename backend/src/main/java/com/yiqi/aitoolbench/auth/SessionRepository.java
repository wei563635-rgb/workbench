package com.yiqi.aitoolbench.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createSchema();
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists sys_session (
                    id varchar(120) primary key,
                    user_id varchar(80) not null,
                    session_token varchar(255) not null unique,
                    user_session_version int not null,
                    ip_address varchar(80),
                    user_agent varchar(500),
                    expires_at varchar(40) not null,
                    revoked_at varchar(40),
                    created_at varchar(40) not null,
                    last_seen_at varchar(40)
                )
                """);
    }

    public String create(String userId, int sessionVersion, String ipAddress, String userAgent) {
        var now = Instant.now();
        var token = "session_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into sys_session (id, user_id, session_token, user_session_version, ip_address, user_agent, expires_at, revoked_at, created_at, last_seen_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "sess_" + UUID.randomUUID().toString().replace("-", ""),
                userId,
                token,
                sessionVersion,
                ipAddress,
                userAgent,
                now.plus(7, ChronoUnit.DAYS).toString(),
                null,
                now.toString(),
                now.toString());
        return token;
    }

    public Optional<SessionRecord> findActive(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                        select id, user_id, session_token, user_session_version, expires_at, revoked_at
                        from sys_session
                        where session_token=?
                        """,
                (rs, rowNum) -> new SessionRecord(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("session_token"),
                        rs.getInt("user_session_version"),
                        Instant.parse(rs.getString("expires_at")),
                        rs.getString("revoked_at") == null ? null : Instant.parse(rs.getString("revoked_at"))),
                token).stream().findFirst().filter(SessionRecord::active);
    }

    public void touch(String token) {
        jdbc.update("update sys_session set last_seen_at=? where session_token=?", Instant.now().toString(), token);
    }

    public void revoke(String token) {
        jdbc.update("update sys_session set revoked_at=? where session_token=? and revoked_at is null", Instant.now().toString(), token);
    }

    public void revokeByUserId(String userId) {
        jdbc.update("update sys_session set revoked_at=? where user_id=? and revoked_at is null", Instant.now().toString(), userId);
    }

    public record SessionRecord(String id, String userId, String token, int userSessionVersion, Instant expiresAt, Instant revokedAt) {
        private boolean active() {
            return revokedAt == null && expiresAt.isAfter(Instant.now());
        }
    }
}
