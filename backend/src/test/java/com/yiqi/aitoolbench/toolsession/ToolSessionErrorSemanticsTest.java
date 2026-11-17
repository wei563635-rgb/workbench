package com.yiqi.aitoolbench.toolsession;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.security.ForbiddenException;
import com.yiqi.aitoolbench.security.UnauthorizedException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ToolSessionErrorSemanticsTest {
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void expiredOrReplayedTicketIsUnauthorizedButCrossToolIsForbidden() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tool-errors;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        var repository = new ToolSessionRepository(new JdbcTemplate(dataSource));
        var service = new ToolSessionService(repository, Clock.fixed(NOW, ZoneOffset.UTC),
                new SecureRandom(), Duration.ofHours(8));
        var context = new AuthContext("source", "u1", "member", "成员", false, 1,
                List.of("workbench:access"));

        var crossTool = service.issue(context, "promo-image", "p1");
        assertThatThrownBy(() -> service.exchange(crossTool.ticket(), "other", NOW.plusSeconds(1)))
                .isInstanceOf(ForbiddenException.class);

        var expired = service.issue(context, "promo-image", "p1");
        assertThatThrownBy(() -> service.exchange(expired.ticket(), "promo-image", NOW.plusSeconds(60)))
                .isInstanceOf(UnauthorizedException.class);

        var replayed = service.issue(context, "promo-image", "p1");
        service.exchange(replayed.ticket(), "promo-image", NOW.plusSeconds(1));
        assertThatThrownBy(() -> service.exchange(replayed.ticket(), "promo-image", NOW.plusSeconds(2)))
                .isInstanceOf(UnauthorizedException.class);
    }
}
