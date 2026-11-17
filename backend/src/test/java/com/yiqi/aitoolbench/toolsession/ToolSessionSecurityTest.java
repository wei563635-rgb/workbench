package com.yiqi.aitoolbench.toolsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.security.ForbiddenException;
import com.yiqi.aitoolbench.security.UnauthorizedException;
import com.yiqi.aitoolbench.tool.ToolRepository;
import com.yiqi.aitoolbench.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ToolSessionSecurityTest {
    private static final Instant NOW = Instant.parse("2026-07-15T02:00:00Z");
    private JdbcTemplate jdbc;
    private ToolSessionRepository repository;
    private ToolRepository tools;
    private UserRepository users;
    private ToolSessionService service;
    private AuthContext user;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:tool-security-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table sys_user (id varchar(80) primary key, enabled boolean, session_version int)");
        jdbc.execute("create table sys_session (id varchar(120) primary key, user_id varchar(80), session_token varchar(255), user_session_version int, expires_at varchar(40), revoked_at varchar(40))");
        jdbc.execute("create table project_board (id varchar(120) primary key, owner varchar(120), status varchar(40))");
        jdbc.execute("create table project_member (project_id varchar(120), member_name varchar(120), member_user_id varchar(80))");
        jdbc.update("insert into sys_user values ('user_1',true,3)");
        jdbc.update("insert into sys_session values ('source_1','user_1','session_source',3,?,null)", NOW.plusSeconds(3600).toString());
        jdbc.update("insert into project_board values ('proj_1','成员一','进行中')");
        jdbc.update("insert into project_member values ('proj_1','成员一','user_1')");
        repository = new ToolSessionRepository(jdbc);
        tools = mock(ToolRepository.class);
        users = mock(UserRepository.class);
        service = new ToolSessionService(repository, tools, users, Clock.fixed(NOW, ZoneOffset.UTC),
                new SecureRandom(), Duration.ofHours(8));
        user = new AuthContext("session_source", "user_1", "member", "成员一", false, 3,
                List.of("workbench:access"));
        when(tools.hasToolAccess("user_1", "promo-image")).thenReturn(true);
        when(tools.isEnabled("promo-image")).thenReturn(true);
        when(users.listDataScopes("user_1")).thenReturn(List.of("SELF"));
        when(users.findById("user_1")).thenReturn(java.util.Optional.of(new UserRepository.SysUser(
                "user_1", "member", "成员一", "unused", true, false, 3, 1, 0, null)));
        when(users.listPermissionCodes("user_1"))
                .thenReturn(List.of("workbench:access", "project:read", "admin:user:write"));
    }

    @Test
    void rejectsCrossToolWithoutConsumingTicket() {
        var issued = service.issue(user, "promo-image", "proj_1");
        assertThatThrownBy(() -> service.exchange(issued.ticket(), "other", NOW.plusSeconds(1)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("票据与工具不匹配");
        assertThat(service.exchange(issued.ticket(), "promo-image", NOW.plusSeconds(2)).toolKey())
                .isEqualTo("promo-image");
    }

    @Test
    void rejectsArchivedProjectDisabledToolAndRevokedSourceSession() {
        var archived = service.issue(user, "promo-image", "proj_1");
        jdbc.update("update project_board set status='归档'");
        assertThatThrownBy(() -> service.exchange(archived.ticket(), "promo-image", NOW.plusSeconds(1)))
                .hasMessage("票据无效或已过期");

        jdbc.update("update project_board set status='进行中'");
        var disabled = service.issue(user, "promo-image", "proj_1");
        when(tools.isEnabled("promo-image")).thenReturn(false);
        assertThatThrownBy(() -> service.exchange(disabled.ticket(), "promo-image", NOW.plusSeconds(1)))
                .hasMessage("票据无效或已过期");

        when(tools.isEnabled("promo-image")).thenReturn(true);
        var revoked = service.issue(user, "promo-image", "proj_1");
        jdbc.update("update sys_session set revoked_at=?", NOW.toString());
        assertThatThrownBy(() -> service.exchange(revoked.ticket(), "promo-image", NOW.plusSeconds(1)))
                .hasMessage("票据无效或已过期");
    }

    @Test
    void issueImmediatelyRejectsArchivedProjectAndDisabledTool() {
        jdbc.update("update project_board set status='归档'");
        assertThatThrownBy(() -> service.issue(user, "promo-image", "proj_1"))
                .hasMessage("项目不可用或无权访问");

        jdbc.update("update project_board set status='进行中'");
        when(tools.isEnabled("promo-image")).thenReturn(false);
        assertThatThrownBy(() -> service.issue(user, "promo-image", "proj_1"))
                .hasMessage("工具不可用或无权访问");
    }

    @Test
    void allScopeUserStillCannotRunAsNonMember() {
        jdbc.update("update project_board set owner='其他人'");
        jdbc.update("delete from project_member");
        when(users.listDataScopes("user_1")).thenReturn(List.of("ALL"));

        assertThatThrownBy(() -> service.issue(user, "promo-image", "proj_1"))
                .hasMessage("项目不可用或无权访问");
    }

    @Test
    void renameDoesNotRevokeStableUserIdProjectAccessBeforeExchange() {
        var issued = service.issue(user, "promo-image", "proj_1");
        when(users.findById("user_1")).thenReturn(java.util.Optional.of(new UserRepository.SysUser(
                "user_1", "member", "新姓名", "unused", true, false, 3, 1, 0, null)));

        assertThat(service.exchange(issued.ticket(), "promo-image", NOW.plusSeconds(1)).projectId())
                .isEqualTo("proj_1");
    }

    @Test
    void renameDoesNotRevokeStableUserIdProjectAccessBeforeIntrospection() {
        var session = service.exchange(service.issue(user, "promo-image", "proj_1").ticket(),
                "promo-image", NOW.plusSeconds(1));
        when(users.findById("user_1")).thenReturn(java.util.Optional.of(new UserRepository.SysUser(
                "user_1", "member", "新姓名", "unused", true, false, 3, 1, 0, null)));

        assertThat(service.introspect(session.sessionToken(), NOW.plusSeconds(2)).displayName())
                .isEqualTo("新姓名");
    }

    @Test
    void concurrentExchangeHasExactlyOneWinner() throws Exception {
        var issued = service.issue(user, "promo-image", "proj_1");
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> task = () -> {
                try { service.exchange(issued.ticket(), "promo-image", NOW.plusSeconds(1)); return true; }
                catch (UnauthorizedException ex) { return false; }
            };
            var futures = pool.invokeAll(List.of(task, task));
            assertThat(List.of(futures.get(0).get(), futures.get(1).get())).containsExactlyInAnyOrder(true, false);
            assertThat(repository.countSessions()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void introspectionRetainsScopeAndRemovesAdminPermissions() {
        var session = service.exchange(service.issue(user, "promo-image", "proj_1").ticket(),
                "promo-image", NOW.plusSeconds(1));
        var context = service.introspect(session.sessionToken(), NOW.plusSeconds(2));
        assertThat(context.toolKey()).isEqualTo("promo-image");
        assertThat(context.projectId()).isEqualTo("proj_1");
        assertThat(context.permissions()).contains("workbench:access").noneMatch(p -> p.startsWith("admin:"));

        jdbc.update("update sys_session set revoked_at=?", NOW.plusSeconds(3).toString());
        assertThatThrownBy(() -> service.introspect(session.sessionToken(), NOW.plusSeconds(4)))
                .hasMessage("工具会话无效或已过期");
    }

    @Test
    void detailPageContextReturnsActiveRolesAndRejectsOtherSessions() {
        var other = service.exchange(service.issue(user, "promo-image", "proj_1").ticket(),
                "promo-image", NOW.plusSeconds(1));
        assertThatThrownBy(() -> service.detailPageContext(other.sessionToken(), NOW.plusSeconds(2)))
                .isInstanceOf(ForbiddenException.class);

        when(tools.isEnabled("detail-page-sso")).thenReturn(true);
        when(tools.hasToolAccess("user_1", "detail-page-sso")).thenReturn(true);
        when(users.listActiveUserRoleCodes("user_1")).thenReturn(List.of("admin", "member"));
        when(users.listDepartmentKeys("user_1")).thenReturn(List.of("design"));
        var session = service.exchange(service.issue(user, "detail-page-sso", "proj_1").ticket(),
                "detail-page-sso", NOW.plusSeconds(1));
        var context = service.detailPageContext(session.sessionToken(), NOW.plusSeconds(2));
        assertThat(context.userId()).isEqualTo("user_1");
        assertThat(context.projectId()).isEqualTo("proj_1");
        assertThat(context.departmentKeys()).containsExactly("design");
        assertThat(context.roleCodes()).containsExactly("admin", "member");

        jdbc.update("update sys_session set revoked_at=?", NOW.plusSeconds(3).toString());
        assertThatThrownBy(() -> service.detailPageContext(session.sessionToken(), NOW.plusSeconds(4)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void toolSessionCannotIssueAnotherTicket() {
        var scoped = new AuthContext("tool_session_existing", "user_1", "member", "成员一", false, 0,
                List.of("workbench:access"), true, "promo-image", "proj_1");
        assertThatThrownBy(() -> service.issue(scoped, "promo-image", "proj_1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工具会话不能签发票据");
    }
}
