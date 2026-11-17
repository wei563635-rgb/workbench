package com.yiqi.aitoolbench.org;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationDirectoryService {
    private final JdbcTemplate jdbc;

    public OrganizationDirectoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 目录数据在同一数据库快照中读取，供受信任的详情页服务端全量同步。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Directory snapshot() {
        var departments = jdbc.query("""
                select id, parent_id, name, enabled, sort_order
                from organization_department
                where deleted_at is null
                order by sort_order, id
                """, (rs, rowNum) -> new Department(rs.getString("id"), rs.getString("parent_id"),
                rs.getString("name"), rs.getBoolean("enabled"), rs.getInt("sort_order")));
        var users = jdbc.query("""
                select id, account, display_name, enabled
                from sys_user
                where deleted_at is null
                order by id
                """, (rs, rowNum) -> new User(rs.getString("id"), rs.getString("account"),
                rs.getString("display_name"), rs.getBoolean("enabled")));
        var memberships = jdbc.query("""
                select ud.user_id, ud.department_id, ud.primary_department
                from sys_user_department ud
                join sys_user u on u.id=ud.user_id and u.deleted_at is null
                join organization_department d on d.id=ud.department_id and d.deleted_at is null
                order by ud.user_id, ud.primary_department desc, ud.sort_order, ud.department_id
                """, (rs, rowNum) -> new Membership(rs.getString("user_id"),
                rs.getString("department_id"), rs.getBoolean("primary_department")));
        var managerRoles = jdbc.query("""
                select ur.user_id, ur.role_code
                from sys_user_role ur
                join sys_user u on u.id=ur.user_id and u.enabled=true and u.deleted_at is null
                join sys_role r on r.code=ur.role_code and r.enabled=true and r.deleted_at is null
                where ur.role_code in ('admin', 'department_leader')
                order by ur.user_id, ur.role_code
                """, (rs, rowNum) -> new ManagerRole(rs.getString("user_id"), rs.getString("role_code")));
        return new Directory(Instant.now(), departments, users, memberships,
                managerScopes(departments, memberships, managerRoles));
    }

    private List<ManagerScope> managerScopes(List<Department> departments, List<Membership> memberships,
            List<ManagerRole> roles) {
        var byId = new HashMap<String, Department>();
        departments.forEach(department -> byId.put(department.id(), department));
        var enabled = departments.stream().filter(department -> enabledChain(department, byId))
                .map(Department::id).toList();
        var rolesByUser = new HashMap<String, Set<String>>();
        roles.forEach(role -> rolesByUser.computeIfAbsent(role.userId(), ignored -> new HashSet<>())
                .add(role.roleCode()));
        var ownedByUser = new HashMap<String, Set<String>>();
        memberships.forEach(membership -> ownedByUser.computeIfAbsent(membership.userId(), ignored -> new HashSet<>())
                .add(membership.departmentId()));
        var result = new ArrayList<ManagerScope>();
        rolesByUser.keySet().stream().sorted().forEach(userId -> {
            var userRoles = rolesByUser.get(userId);
            if (userRoles.contains("admin")) {
                result.add(new ManagerScope(userId, "ALL", enabled));
            } else {
                var owned = ownedByUser.getOrDefault(userId, Set.of());
                var managed = enabled.stream().filter(id -> underAny(id, owned, byId)).toList();
                result.add(new ManagerScope(userId, "DEPARTMENT", managed));
            }
        });
        return result;
    }

    private boolean enabledChain(Department department, Map<String, Department> byId) {
        var visited = new HashSet<String>();
        var current = department;
        while (current != null && visited.add(current.id())) {
            if (!current.enabled()) return false;
            if (current.parentId() == null) return true;
            current = byId.get(current.parentId());
        }
        return false;
    }

    private boolean underAny(String departmentId, Set<String> owned, Map<String, Department> byId) {
        var visited = new HashSet<String>();
        var current = byId.get(departmentId);
        while (current != null && visited.add(current.id())) {
            if (owned.contains(current.id())) return true;
            current = byId.get(current.parentId());
        }
        return false;
    }

    public record Directory(Instant generatedAt, List<Department> departments, List<User> users,
            List<Membership> memberships, List<ManagerScope> managerScopes) {}
    public record Department(String id, String parentId, String name, boolean enabled, int sortOrder) {}
    public record User(String userId, String account, String name, boolean enabled) {}
    public record Membership(String userId, String departmentId, boolean primary) {}
    public record ManagerScope(String userId, String scope, List<String> managedDepartmentIds) {}
    private record ManagerRole(String userId, String roleCode) {}
}
