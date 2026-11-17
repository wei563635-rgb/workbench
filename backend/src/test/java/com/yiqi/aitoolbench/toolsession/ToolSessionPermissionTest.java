package com.yiqi.aitoolbench.toolsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yiqi.aitoolbench.auth.AuthService;
import com.yiqi.aitoolbench.security.PermissionService;
import com.yiqi.aitoolbench.security.DataScope;
import com.yiqi.aitoolbench.tool.ToolRepository;
import com.yiqi.aitoolbench.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolSessionPermissionTest {
    @Test void mapsToolSessionToNonAdminScopedContext() {
        var auth = mock(AuthService.class);
        var sessions = mock(ToolSessionService.class);
        var now = Instant.parse("2026-07-15T02:00:00Z");
        when(sessions.introspect("tool_session_opaque", now)).thenReturn(new ToolSessionService.ToolSessionContext(
                "u1", "member", "成员", List.of("workbench:access", "admin:user:write"),
                "promo-image", "p1", now.plusSeconds(3600)));
        when(auth.requireContext("service-credential"))
                .thenThrow(new IllegalArgumentException("登录态无效或已过期"));
        var service = new PermissionService(auth, mock(UserRepository.class),
                mock(ToolRepository.class), sessions, Clock.fixed(now, ZoneOffset.UTC));
        var context = service.requireContext("Bearer tool_session_opaque");
        assertThat(context.toolSession()).isTrue();
        assertThat(context.toolKey()).isEqualTo("promo-image");
        assertThat(context.projectId()).isEqualTo("p1");
        assertThat(context.permissions()).contains("workbench:access").noneMatch(p -> p.startsWith("admin:"));
        assertThat(service.hasToolAccess(context, "promo-image")).isTrue();
        assertThat(service.hasToolAccess(context, "other")).isFalse();
        assertThat(service.hasProjectScope(context, "p1")).isTrue();
        assertThat(service.hasProjectScope(context, "p2")).isFalse();
        assertThat(service.effectiveDataScope(context)).isEqualTo(DataScope.SELF);
        assertThatThrownBy(() -> service.requireContext("Bearer service-credential"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
