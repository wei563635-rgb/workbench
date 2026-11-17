package com.yiqi.aitoolbench.org;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OrganizationDirectoryServiceTest {
    @Test
    void includesCurrentHierarchyAndMembershipsWithoutDeletedRecords() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:directory-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table organization_department (id varchar(80), parent_id varchar(80), name varchar(120), enabled boolean, sort_order int, deleted_at varchar(40))");
        jdbc.execute("create table sys_user (id varchar(80), account varchar(120), display_name varchar(120), enabled boolean, deleted_at varchar(40))");
        jdbc.execute("create table sys_user_department (user_id varchar(80), department_id varchar(80), primary_department boolean, sort_order int)");
        jdbc.execute("create table sys_role (code varchar(80), enabled boolean, deleted_at varchar(40))");
        jdbc.execute("create table sys_user_role (user_id varchar(80), role_code varchar(80))");
        jdbc.update("insert into organization_department values ('root',null,'总部门',true,10,null)");
        jdbc.update("insert into organization_department values ('child','root','下级部门',false,20,null)");
        jdbc.update("insert into organization_department values ('blocked_grandchild','child','停用链下级',true,22,null)");
        jdbc.update("insert into organization_department values ('active_child','root','可用下级',true,25,null)");
        jdbc.update("insert into organization_department values ('removed','root','已删除',false,30,'2026-10-22')");
        jdbc.update("insert into sys_user values ('u1','alice','阿丽',true,null)");
        jdbc.update("insert into sys_user values ('u2','bob','阿波',false,null)");
        jdbc.update("insert into sys_user values ('u3','gone','已删除用户',false,'2026-10-22')");
        jdbc.update("insert into sys_user values ('u4','admin','管理员',true,null)");
        jdbc.update("insert into sys_user_department values ('u1','root',true,0)");
        jdbc.update("insert into sys_user_department values ('u1','child',false,1)");
        jdbc.update("insert into sys_user_department values ('u2','child',true,0)");
        jdbc.update("insert into sys_user_department values ('u3','root',true,0)");
        jdbc.update("insert into sys_user_department values ('u1','removed',false,2)");
        jdbc.update("insert into sys_role values ('department_leader',true,null)");
        jdbc.update("insert into sys_role values ('admin',true,null)");
        jdbc.update("insert into sys_user_role values ('u1','department_leader')");
        jdbc.update("insert into sys_user_role values ('u2','department_leader')");
        jdbc.update("insert into sys_user_role values ('u4','admin')");

        var directory = new OrganizationDirectoryService(jdbc).snapshot();

        assertThat(directory.departments()).extracting(OrganizationDirectoryService.Department::id)
                .containsExactly("root", "child", "blocked_grandchild", "active_child");
        assertThat(directory.departments().get(1).parentId()).isEqualTo("root");
        assertThat(directory.departments().get(1).enabled()).isFalse();
        assertThat(directory.users()).extracting(OrganizationDirectoryService.User::userId)
                .containsExactly("u1", "u2", "u4");
        assertThat(directory.users().get(1).enabled()).isFalse();
        assertThat(directory.memberships()).extracting(OrganizationDirectoryService.Membership::departmentId)
                .containsExactly("root", "child", "child");
        assertThat(directory.memberships().get(0).primary()).isTrue();
        assertThat(directory.managerScopes()).extracting(OrganizationDirectoryService.ManagerScope::userId)
                .containsExactly("u1", "u4");
        assertThat(directory.managerScopes().get(0).managedDepartmentIds())
                .containsExactly("root", "active_child");
        assertThat(directory.managerScopes().get(1).scope()).isEqualTo("ALL");
        assertThat(directory.managerScopes().get(1).managedDepartmentIds())
                .containsExactly("root", "active_child");
    }
}
