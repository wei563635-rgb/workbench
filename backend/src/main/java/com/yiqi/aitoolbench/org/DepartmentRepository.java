package com.yiqi.aitoolbench.org;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DepartmentRepository {
    private final JdbcTemplate jdbc;

    public DepartmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createSchema();
        seedData();
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists sys_department (
                    id varchar(80) primary key,
                    name varchar(120) not null,
                    parent_id varchar(80),
                    enabled boolean not null,
                    sort_order int not null,
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null
                )
                """);
        jdbc.execute("""
                create table if not exists sys_user_department (
                    user_id varchar(80) not null,
                    department_id varchar(80) not null,
                    primary_department boolean not null,
                    data_scope varchar(40) not null,
                    sort_order int not null,
                    created_at varchar(40) not null,
                    primary key (user_id, department_id)
                )
                """);
    }

    private void seedData() {
        var now = Instant.now().toString();
        seedDepartment("market", "市场部", 10, now);
        seedDepartment("design", "设计部", 20, now);
        seedDepartment("operation", "运营部", 30, now);
        seedDepartment("hr", "人事部", 40, now);
    }

    private void seedDepartment(String id, String name, int sortOrder, String now) {
        if (!exists("select count(*) from sys_department where id=?", id)) {
            jdbc.update("""
                    insert into sys_department (id, name, parent_id, enabled, sort_order, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, id, name, null, true, sortOrder, now, now);
        }
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }
}
