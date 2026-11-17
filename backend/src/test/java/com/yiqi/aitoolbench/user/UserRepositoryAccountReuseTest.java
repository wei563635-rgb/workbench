package com.yiqi.aitoolbench.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class UserRepositoryAccountReuseTest {
    @Test
    void deletedAccountCanBeReusedWithoutRestoringTheOldIdentity() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:account-reuse;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        var users = new UserRepository(jdbc);

        var oldId = users.createUser("test001", "旧用户", "old-hash", true);
        assertThatThrownBy(() -> users.createUser("test001", "重复用户", "other-hash", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号已存在");

        users.logicalDeleteUser(oldId, "admin");
        var newId = users.createUser("test001", "新用户", "new-hash", true);

        assertThat(newId).isNotEqualTo(oldId);
        assertThat(users.findByAccount("test001")).get().extracting(UserRepository.SysUser::id).isEqualTo(newId);
        assertThat(users.findById(oldId)).isEmpty();
        assertThat(jdbc.queryForObject("select deleted_at is not null from sys_user where id=?", Boolean.class, oldId)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from sys_user where account=?", Integer.class, "test001")).isEqualTo(2);
    }
}
