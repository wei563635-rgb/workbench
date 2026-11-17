package com.yiqi.aitoolbench.toolsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.security.UnauthorizedException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ToolSessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-15T02:00:00Z");

    private JdbcTemplate jdbc;
    private ToolSessionRepository repository;
    private ToolSessionService service;
    private AuthContext user;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tool-session-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        repository = new ToolSessionRepository(jdbc);
        service = new ToolSessionService(repository, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(), Duration.ofHours(8));
        user = new AuthContext("session_source", "user_1", "member", "成员一", false, 3,
                List.of("workbench:access"));
    }

    @Test
    void storesOnlySha256DigestForOpaqueTicket() {
        var issued = service.issue(user, "promo-image", "proj_1");

        assertThat(issued.ticket()).startsWith("tool_ticket_");
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        var stored = repository.findTicketById(issued.id()).orElseThrow();
        assertThat(stored.tokenHash()).hasSize(64).doesNotContain(issued.ticket());
        assertThat(jdbc.queryForObject("select token_hash from tool_session_ticket where id=?", String.class, issued.id()))
                .doesNotContain(issued.ticket());
    }

    @Test
    void ticketExpiresAtTheSixtySecondBoundary() {
        var beforeBoundary = service.issue(user, "promo-image", "proj_1");
        assertThat(service.exchange(beforeBoundary.ticket(), "promo-image", NOW.plusSeconds(59)).sessionToken())
                .startsWith("tool_session_");

        var atBoundary = service.issue(user, "promo-image", "proj_1");
        assertThatThrownBy(() -> service.exchange(atBoundary.ticket(), "promo-image", NOW.plusSeconds(60)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("票据无效或已过期");
    }

    @Test
    void consumesTicketOnlyOnce() {
        var issued = service.issue(user, "promo-image", "proj_1");

        service.exchange(issued.ticket(), "promo-image", NOW.plusSeconds(1));

        assertThatThrownBy(() -> service.exchange(issued.ticket(), "promo-image", NOW.plusSeconds(2)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("票据无效或已过期");
        assertThat(repository.countSessions()).isEqualTo(1);
    }
}
