package com.yiqi.aitoolbench.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class UserRepositoryActiveRolesTest {
    @Test
    void onlyEnabledAndNotDeletedRolesAreReturnedToServiceIntegrations() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:active-roles;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        var users = new UserRepository(jdbc);
        users.addUserRole("u_admin", "member");
        assertThat(users.listActiveUserRoleCodes("u_admin")).containsExactly("admin", "member");

        jdbc.update("update sys_role set enabled=false where code='member'");
        assertThat(users.listUserRoleCodes("u_admin")).containsExactly("admin", "member");
        assertThat(users.listActiveUserRoleCodes("u_admin")).containsExactly("admin");

        jdbc.update("update sys_role set enabled=true, deleted_at='2026-10-21T00:00:00Z' where code='member'");
        assertThat(users.listActiveUserRoleCodes("u_admin")).containsExactly("admin");
    }
}
