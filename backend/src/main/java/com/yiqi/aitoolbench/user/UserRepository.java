package com.yiqi.aitoolbench.user;

import com.yiqi.aitoolbench.security.PermissionCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createSchema();
        seedData();
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists sys_user (
                    id varchar(80) primary key,
                    account varchar(120) not null,
                    display_name varchar(120) not null,
                    password_hash varchar(255) not null,
                    enabled boolean not null,
                    must_change_password boolean not null,
                    session_version int not null,
                    permission_version int not null,
                    failed_attempts int not null,
                    locked_until varchar(40),
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null
                )
                """);
        jdbc.execute("""
                create table if not exists sys_role (
                    code varchar(80) primary key,
                    name varchar(120) not null,
                    description varchar(500),
                    data_scope varchar(40) not null,
                    enabled boolean not null,
                    built_in boolean not null,
                    permission_version int not null,
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null
                )
                """);
        jdbc.execute("""
                create table if not exists sys_permission (
                    code varchar(120) primary key,
                    name varchar(120) not null,
                    module varchar(120) not null,
                    sensitive boolean not null,
                    built_in boolean not null,
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null
                )
                """);
        jdbc.execute("""
                create table if not exists sys_role_permission (
                    role_code varchar(80) not null,
                    permission_code varchar(120) not null,
                    created_at varchar(40) not null,
                    primary key (role_code, permission_code)
                )
                """);
        jdbc.execute("""
                create table if not exists sys_user_role (
                    user_id varchar(80) not null,
                    role_code varchar(80) not null,
                    created_at varchar(40) not null,
                    primary key (user_id, role_code)
                )
                """);
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
        jdbc.execute("alter table sys_user add column if not exists deleted_at varchar(40)");
        jdbc.execute("alter table sys_user add column if not exists deleted_by varchar(80)");
        migrateActiveAccountUniqueness();
        jdbc.execute("alter table sys_role add column if not exists deleted_at varchar(40)");
        jdbc.execute("alter table sys_role add column if not exists deleted_by varchar(80)");
        jdbc.execute("""
                create table if not exists security_seed_marker (
                    seed_key varchar(120) primary key,
                    created_at varchar(40) not null
                )
                """);
    }

    private void migrateActiveAccountUniqueness() {
        var postgres = jdbc.execute((ConnectionCallback<Boolean>) connection ->
                "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName()));
        if (!Boolean.TRUE.equals(postgres)) return;
        var activeIndexExists = exists("""
                select count(*) from pg_index i
                join pg_class idx on idx.oid=i.indexrelid
                join pg_class tbl on tbl.oid=i.indrelid
                where tbl.oid='sys_user'::regclass
                  and idx.relname='ux_sys_user_active_account'
                  and i.indisunique=true and i.indpred is not null
                """);
        var legacyConstraintExists = exists("""
                select count(*) from pg_constraint
                where conrelid='sys_user'::regclass and conname='sys_user_account_key'
                """);
        if (activeIndexExists && !legacyConstraintExists) return;
        // Keep deleted rows and their user IDs for audit while allowing a new user to reuse the account.
        jdbc.execute("create unique index if not exists ux_sys_user_active_account on sys_user(account) where deleted_at is null");
        jdbc.execute("alter table sys_user drop constraint if exists sys_user_account_key");
    }

    private void seedData() {
        var now = Instant.now().toString();
        boolean adminRoleCreated = seedRole("admin", "管理员", "平台管理员", "ALL", now);
        boolean leaderRoleCreated = seedRole("department_leader", "部门负责人", "部门数据和项目负责人", "DEPARTMENT", now);
        boolean memberRoleCreated = seedRole("member", "普通成员", "工作台普通成员", "SELF_PROJECT", now);
        boolean auditorRoleCreated = seedRole("auditor", "审计员", "审计和日志查看", "ALL", now);

        for (var permission : PermissionCatalog.PERMISSIONS) {
            upsert("""
                    update sys_permission set name=?, module=?, sensitive=?, built_in=?, updated_at=? where code=?
                    """, """
                    insert into sys_permission (code, name, module, sensitive, built_in, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    new Object[]{permission.name(), permission.module(), permission.sensitive(), true, now, permission.code()},
                    new Object[]{permission.code(), permission.name(), permission.module(), permission.sensitive(), true, now, now});
        }

        for (var permission : PermissionCatalog.PERMISSIONS) {
            seedRolePermission("admin", permission.code(), now);
        }
        if (leaderRoleCreated) {
            seedRolePermissions("department_leader", List.of(
                    "workbench:access", "project:create", "project:own", "project:read", "project:write",
                    "project:result:read", "project:result:download", "run:self:read", "run:department:read",
                    "admin:department:read", "admin:tool:read", "admin:deploy:read"), now);
        }
        if (memberRoleCreated) {
            seedRolePermissions("member", List.of(
                    "workbench:access", "project:create", "project:own", "project:read", "project:write",
                    "project:result:read", "project:result:download", "run:self:read",
                    "admin:department:read", "admin:tool:read"), now);
        }
        // 品牌页面是工作台的公共浏览能力。这里单独补种，确保已有默认角色升级后也能访问。
        seedRolePermission("department_leader", "brand:page:read", now);
        seedRolePermission("member", "brand:page:read", now);
        if (auditorRoleCreated) {
            seedRolePermissions("auditor", List.of(
                    "workbench:access", "project:read", "project:result:read", "run:self:read",
                    "run:department:read", "admin:user:read", "admin:department:read", "admin:role:read",
                    "admin:tool:read", "admin:model:read", "admin:ai-log:read", "admin:log:read",
                    "admin:deploy:read"), now);
        }

        if (seedAdminUser(now)) {
            seedUserRole("u_admin", "admin", now);
        }
        migrateBuiltInMemberProjectPermissions(now);
    }

    private void migrateBuiltInMemberProjectPermissions(String now) {
        var marker = "builtin-member-project-permissions-v2";
        if (exists("select count(*) from security_seed_marker where seed_key=?", marker)) {
            return;
        }
        // 普通成员默认只参与被加入的项目，不能创建项目、担任负责人或编辑项目资料。
        jdbc.update("""
                delete from sys_role_permission
                where role_code='member' and permission_code in ('project:create', 'project:own', 'project:write')
                """);
        jdbc.update("insert into security_seed_marker (seed_key, created_at) values (?, ?)", marker, now);
    }

    private boolean seedRole(String code, String name, String description, String dataScope, String now) {
        if (exists("select count(*) from sys_role where code=?", code)) {
            return false;
        }
        jdbc.update("""
                insert into sys_role (code, name, description, data_scope, enabled, built_in, permission_version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, code, name, description, dataScope, true, true, 1, now, now);
        return true;
    }

    private boolean seedAdminUser(String now) {
        if (exists("select count(*) from sys_user where id=?", "u_admin")) {
            return false;
        }
        jdbc.update("""
                insert into sys_user (id, account, display_name, password_hash, enabled, must_change_password, session_version, permission_version, failed_attempts, locked_until, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "u_admin", "admin", "系统管理员", "admin123", true, false, 1, 1, 0, null, now, now);
        return true;
    }

    private void seedRolePermissions(String roleCode, List<String> permissionCodes, String now) {
        for (var permissionCode : permissionCodes) {
            seedRolePermission(roleCode, permissionCode, now);
        }
    }

    private void seedRolePermission(String roleCode, String permissionCode, String now) {
        if (!exists("select count(*) from sys_role_permission where role_code=? and permission_code=?",
                roleCode, permissionCode)) {
            jdbc.update("insert into sys_role_permission (role_code, permission_code, created_at) values (?, ?, ?)",
                    roleCode, permissionCode, now);
        }
    }

    private void seedUserRole(String userId, String roleCode, String now) {
        if (!exists("select count(*) from sys_user_role where user_id=? and role_code=?", userId, roleCode)) {
            jdbc.update("insert into sys_user_role (user_id, role_code, created_at) values (?, ?, ?)",
                    userId, roleCode, now);
        }
    }

    private void upsert(String updateSql, String insertSql, Object[] updateArgs, Object[] insertArgs) {
        if (jdbc.update(updateSql, updateArgs) == 0) {
            jdbc.update(insertSql, insertArgs);
        }
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    public Optional<SysUser> findByAccount(String account) {
        return jdbc.query("""
                        select id, account, display_name, password_hash, enabled, must_change_password, session_version, permission_version, failed_attempts, locked_until
                        from sys_user
                        where account=? and deleted_at is null
                        """,
                (rs, rowNum) -> new SysUser(
                        rs.getString("id"),
                        rs.getString("account"),
                        rs.getString("display_name"),
                        rs.getString("password_hash"),
                        rs.getBoolean("enabled"),
                        rs.getBoolean("must_change_password"),
                        rs.getInt("session_version"),
                        rs.getInt("permission_version"),
                        rs.getInt("failed_attempts"),
                        parseInstant(rs.getString("locked_until"))),
                account).stream().findFirst();
    }

    public Optional<SysUser> findById(String userId) {
        return jdbc.query("""
                        select id, account, display_name, password_hash, enabled, must_change_password, session_version, permission_version, failed_attempts, locked_until
                        from sys_user
                        where id=? and deleted_at is null
                        """,
                (rs, rowNum) -> new SysUser(
                        rs.getString("id"),
                        rs.getString("account"),
                        rs.getString("display_name"),
                        rs.getString("password_hash"),
                        rs.getBoolean("enabled"),
                        rs.getBoolean("must_change_password"),
                        rs.getInt("session_version"),
                        rs.getInt("permission_version"),
                        rs.getInt("failed_attempts"),
                        parseInstant(rs.getString("locked_until"))),
                userId).stream().findFirst();
    }

    public List<SysUser> listUsers() {
        return jdbc.query("""
                select id, account, display_name, password_hash, enabled, must_change_password, session_version, permission_version, failed_attempts, locked_until
                from sys_user
                where deleted_at is null
                order by created_at desc, id desc
                """, (rs, rowNum) -> new SysUser(
                rs.getString("id"),
                rs.getString("account"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getBoolean("enabled"),
                rs.getBoolean("must_change_password"),
                rs.getInt("session_version"),
                rs.getInt("permission_version"),
                rs.getInt("failed_attempts"),
                parseInstant(rs.getString("locked_until"))));
    }

    public List<SysUser> listUsers(int page, int pageSize) {
        return listUsers(page, pageSize, null);
    }

    public List<SysUser> listUsers(int page, int pageSize, String search) {
        // 子串搜索扫描当前用户目录，时间 O(N)；先筛选再分页，避免只查当前页。
        String pattern = userSearchPattern(search);
        return jdbc.query("""
                select id, account, display_name, password_hash, enabled, must_change_password, session_version, permission_version, failed_attempts, locked_until
                from sys_user
                where deleted_at is null and (lower(account) like ? escape '!' or lower(display_name) like ? escape '!')
                order by created_at desc, id desc
                limit ? offset ?
                """, (rs, rowNum) -> new SysUser(
                rs.getString("id"),
                rs.getString("account"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getBoolean("enabled"),
                rs.getBoolean("must_change_password"),
                rs.getInt("session_version"),
                rs.getInt("permission_version"),
                rs.getInt("failed_attempts"),
                parseInstant(rs.getString("locked_until"))), pattern, pattern, pageSize, (long) (page - 1) * pageSize);
    }

    public long countUsers() {
        return countUsers(null);
    }

    public long countUsers(String search) {
        String pattern = userSearchPattern(search);
        Long count = jdbc.queryForObject("""
                select count(*) from sys_user
                where deleted_at is null and (lower(account) like ? escape '!' or lower(display_name) like ? escape '!')
                """, Long.class, pattern, pattern);
        return count == null ? 0 : count;
    }

    private static String userSearchPattern(String search) {
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        return "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public List<String> listPermissionCodes(String userId) {
        return new ArrayList<>(jdbc.query("""
                select distinct rp.permission_code
                from sys_user_role ur
                join sys_role r on r.code = ur.role_code and r.enabled = true and r.deleted_at is null
                join sys_role_permission rp on rp.role_code = r.code
                where ur.user_id = ?
                order by rp.permission_code
                """, (rs, rowNum) -> rs.getString("permission_code"), userId));
    }

    public void updatePassword(String userId, String passwordHash, boolean mustChangePassword) {
        jdbc.update("""
                update sys_user
                set password_hash=?, must_change_password=?, updated_at=?
                where id=?
                """, passwordHash, mustChangePassword, Instant.now().toString(), userId);
    }

    public void recordLoginFailure(String userId, int failedAttempts) {
        var lockedUntil = failedAttempts >= 5 ? Instant.now().plusSeconds(900).toString() : null;
        jdbc.update("""
                update sys_user
                set failed_attempts=?, locked_until=?, updated_at=?
                where id=?
                """, failedAttempts, lockedUntil, Instant.now().toString(), userId);
    }

    public void clearLoginFailure(String userId) {
        jdbc.update("""
                update sys_user
                set failed_attempts=0, locked_until=null, updated_at=?
                where id=?
                """, Instant.now().toString(), userId);
    }

    public void bumpSessionVersion(String userId) {
        jdbc.update("""
                update sys_user
                set session_version=session_version+1, updated_at=?
                where id=?
                """, Instant.now().toString(), userId);
    }

    public void updateEnabled(String userId, boolean enabled) {
        jdbc.update("""
                update sys_user
                set enabled=?, updated_at=?
                where id=?
                """, enabled, Instant.now().toString(), userId);
    }

    public void updateDisplayName(String userId, String displayName) {
        if (!exists("select count(*) from sys_user where id=?", userId)) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        jdbc.update("""
                update sys_user
                set display_name=?, updated_at=?
                where id=?
                """, displayName, Instant.now().toString(), userId);
    }

    public String createUser(String account, String displayName, String passwordHash, boolean mustChangePassword) {
        if (exists("select count(*) from sys_user where account=? and deleted_at is null", account)) {
            throw new IllegalArgumentException("账号已存在");
        }
        var now = Instant.now().toString();
        var id = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("""
                insert into sys_user (id, account, display_name, password_hash, enabled, must_change_password, session_version, permission_version, failed_attempts, locked_until, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, account, displayName, passwordHash, true, mustChangePassword, 1, 1, 0, null, now, now);
        return id;
    }

    public void addUserRole(String userId, String roleCode) {
        validateRoleCodes(List.of(roleCode));
        if (!exists("select count(*) from sys_user_role where user_id=? and role_code=?", userId, roleCode)) {
            jdbc.update("insert into sys_user_role (user_id, role_code, created_at) values (?, ?, ?)",
                    userId, roleCode, Instant.now().toString());
        }
    }

    public List<String> listUserRoleCodes(String userId) {
        return jdbc.query("""
                select role_code
                from sys_user_role
                where user_id=?
                order by role_code
                """, (rs, rowNum) -> rs.getString("role_code"), userId);
    }

    /** 服务间身份接口只下发当前生效的角色，避免已停用角色继续授权。 */
    public List<String> listActiveUserRoleCodes(String userId) {
        return jdbc.query("""
                select ur.role_code
                from sys_user_role ur
                join sys_role r on r.code=ur.role_code
                where ur.user_id=? and r.enabled=true and r.deleted_at is null
                order by ur.role_code
                """, (rs, rowNum) -> rs.getString("role_code"), userId);
    }

    public List<String> listDepartmentKeys(String userId) {
        return jdbc.query("""
                select department_id
                from sys_user_department
                where user_id=?
                order by primary_department desc, sort_order, department_id
                """, (rs, rowNum) -> rs.getString("department_id"), userId);
    }

    public void replaceUserRoles(String userId, List<String> roleCodes) {
        if (!exists("select count(*) from sys_user where id=?", userId)) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        validateRoleCodes(roleCodes);
        jdbc.update("delete from sys_user_role where user_id=?", userId);
        if (roleCodes == null) {
            return;
        }
        var now = Instant.now().toString();
        for (var roleCode : roleCodes.stream().distinct().toList()) {
            jdbc.update("insert into sys_user_role (user_id, role_code, created_at) values (?, ?, ?)",
                    userId, roleCode, now);
        }
        jdbc.update("""
                update sys_user
                set permission_version=permission_version+1, updated_at=?
                where id=?
                """, now, userId);
    }

    public void requireRoleCodes(List<String> roleCodes) {
        validateRoleCodes(roleCodes);
    }

    public List<RoleRecord> listRoles() {
        return jdbc.query("""
                select code, name, description, data_scope, enabled, built_in, permission_version, created_at, updated_at
                from sys_role
                where deleted_at is null
                order by created_at desc, code desc
                """, (rs, rowNum) -> new RoleRecord(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("data_scope"),
                rs.getBoolean("enabled"),
                rs.getBoolean("built_in"),
                rs.getInt("permission_version"),
                listRolePermissionCodes(rs.getString("code"))));
    }

    public List<RoleRecord> listRoles(int page, int pageSize) {
        return jdbc.query("""
                select code, name, description, data_scope, enabled, built_in, permission_version, created_at, updated_at
                from sys_role
                where deleted_at is null
                order by created_at desc, code desc
                limit ? offset ?
                """, (rs, rowNum) -> new RoleRecord(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("data_scope"),
                rs.getBoolean("enabled"),
                rs.getBoolean("built_in"),
                rs.getInt("permission_version"),
                listRolePermissionCodes(rs.getString("code"))), pageSize, (long) (page - 1) * pageSize);
    }

    public long countRoles() {
        Long count = jdbc.queryForObject("select count(*) from sys_role where deleted_at is null", Long.class);
        return count == null ? 0 : count;
    }

    public Optional<RoleRecord> findRole(String roleCode) {
        return jdbc.query("""
                        select code, name, description, data_scope, enabled, built_in, permission_version, created_at, updated_at
                        from sys_role
                        where code=? and deleted_at is null
                        """,
                (rs, rowNum) -> new RoleRecord(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("data_scope"),
                        rs.getBoolean("enabled"),
                        rs.getBoolean("built_in"),
                        rs.getInt("permission_version"),
                        listRolePermissionCodes(rs.getString("code"))),
                roleCode).stream().findFirst();
    }

    public RoleRecord createRole(String code, String name, String description, String dataScope, List<String> permissionCodes) {
        if (exists("select count(*) from sys_role where code=?", code)) {
            throw new IllegalArgumentException("角色已存在: " + code);
        }
        validatePermissionCodes(permissionCodes);
        var now = Instant.now().toString();
        jdbc.update("""
                insert into sys_role (code, name, description, data_scope, enabled, built_in, permission_version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, code, name, description == null ? "" : description, dataScope, true, false, 1, now, now);
        replaceRolePermissions(code, permissionCodes);
        return findRole(code).orElseThrow(() -> new IllegalArgumentException("角色创建失败: " + code));
    }

    public RoleRecord updateRole(String code, String name, String description, String dataScope, List<String> permissionCodes) {
        var role = findRole(code).orElseThrow(() -> new IllegalArgumentException("角色不存在: " + code));
        validatePermissionCodes(permissionCodes);
        jdbc.update("""
                update sys_role
                set name=?, description=?, data_scope=?, permission_version=permission_version+1, updated_at=?
                where code=?
                """,
                isBlank(name) ? role.name() : name,
                description == null ? role.description() : description,
                isBlank(dataScope) ? role.dataScope() : dataScope,
                Instant.now().toString(),
                code);
        if (permissionCodes != null) {
            replaceRolePermissions(code, permissionCodes);
        }
        return findRole(code).orElseThrow(() -> new IllegalArgumentException("角色不存在: " + code));
    }

    public RoleRecord updateRoleStatus(String code, boolean enabled) {
        findRole(code).orElseThrow(() -> new IllegalArgumentException("角色不存在: " + code));
        jdbc.update("""
                update sys_role
                set enabled=?, permission_version=permission_version+1, updated_at=?
                where code=?
                """, enabled, Instant.now().toString(), code);
        return findRole(code).orElseThrow(() -> new IllegalArgumentException("角色不存在: " + code));
    }

    public List<String> listRolePermissionCodes(String roleCode) {
        return jdbc.query("""
                select permission_code
                from sys_role_permission
                where role_code=?
                order by permission_code
                """, (rs, rowNum) -> rs.getString("permission_code"), roleCode);
    }

    public List<String> listDataScopes(String userId) {
        return jdbc.query("""
                select distinct r.data_scope
                from sys_user_role ur
                join sys_role r on r.code = ur.role_code and r.enabled = true and r.deleted_at is null
                where ur.user_id=?
                """, (rs, rowNum) -> rs.getString("data_scope"), userId);
    }

    private void replaceRolePermissions(String roleCode, List<String> permissionCodes) {
        jdbc.update("delete from sys_role_permission where role_code=?", roleCode);
        if (permissionCodes == null) {
            return;
        }
        var now = Instant.now().toString();
        for (var permissionCode : permissionCodes.stream().distinct().toList()) {
            jdbc.update("insert into sys_role_permission (role_code, permission_code, created_at) values (?, ?, ?)",
                    roleCode, permissionCode, now);
        }
    }

    public void logicalDeleteUser(String userId, String deletedBy) {
        var user = findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        var now = Instant.now().toString();
        jdbc.update("""
                update sys_user
                set enabled=false, session_version=session_version+1, deleted_at=?, deleted_by=?, updated_at=?
                where id=? and deleted_at is null
                """, now, deletedBy, now, user.id());
        jdbc.update("update sys_session set revoked_at=? where user_id=? and revoked_at is null", now, userId);
    }

    public void logicalDeleteRole(String roleCode, String deletedBy) {
        var role = findRole(roleCode).orElseThrow(() -> new IllegalArgumentException("角色不存在: " + roleCode));
        if (role.builtIn()) {
            throw new IllegalStateException("内置角色不能删除");
        }
        Integer references = jdbc.queryForObject("""
                select count(*)
                from sys_user_role ur
                join sys_user u on u.id=ur.user_id and u.deleted_at is null
                where ur.role_code=?
                """, Integer.class, roleCode);
        if (references != null && references > 0) {
            throw new IllegalStateException("角色仍被有效用户使用，请先解除关联");
        }
        var now = Instant.now().toString();
        jdbc.update("""
                update sys_role
                set enabled=false, permission_version=permission_version+1, deleted_at=?, deleted_by=?, updated_at=?
                where code=? and deleted_at is null
                """, now, deletedBy, now, roleCode);
    }

    private void validatePermissionCodes(List<String> permissionCodes) {
        if (permissionCodes == null) {
            return;
        }
        for (var permissionCode : permissionCodes.stream().distinct().toList()) {
            if (!exists("select count(*) from sys_permission where code=?", permissionCode)) {
                throw new IllegalArgumentException("权限点不存在: " + permissionCode);
            }
        }
    }

    private void validateRoleCodes(List<String> roleCodes) {
        if (roleCodes == null) {
            return;
        }
        for (var roleCode : roleCodes.stream().distinct().toList()) {
            if (!exists("select count(*) from sys_role where code=? and deleted_at is null", roleCode)) {
                throw new IllegalArgumentException("角色不存在: " + roleCode);
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    public record SysUser(
            String id,
            String account,
            String displayName,
            String passwordHash,
            boolean enabled,
            boolean mustChangePassword,
            int sessionVersion,
            int permissionVersion,
            int failedAttempts,
            Instant lockedUntil) {
    }

    public record RoleRecord(
            String code,
            String name,
            String description,
            String dataScope,
            boolean enabled,
            boolean builtIn,
            int permissionVersion,
            List<String> permissionCodes) {
    }
}
