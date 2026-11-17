package com.yiqi.aitoolbench.project;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {
    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createSchema();
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists project (
                    id varchar(120) primary key,
                    project_no varchar(120) not null unique,
                    name varchar(200) not null,
                    department_id varchar(80) not null,
                    owner_user_id varchar(80) not null,
                    owner_name varchar(120) not null,
                    status varchar(40) not null,
                    objective text,
                    created_by varchar(80) not null,
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null,
                    archived_at varchar(40)
                )
                """);
        jdbc.execute("""
                create table if not exists project_product (
                    id varchar(120) primary key,
                    project_id varchar(120) not null,
                    product_name varchar(200) not null,
                    brand varchar(120),
                    category varchar(120),
                    sale_price decimal(18, 2),
                    selling_points text,
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null
                )
                """);
        jdbc.execute("""
                create table if not exists project_member (
                    project_id varchar(120) not null,
                    member_name varchar(120) not null,
                    sort_order int not null,
                    member_user_id varchar(80),
                    project_role varchar(40),
                    joined_at varchar(40),
                    created_at varchar(40),
                    primary key (project_id, member_name)
                )
                """);
        addColumnIfMissing("project_member", "member_user_id", "varchar(80)");
        addColumnIfMissing("project_member", "project_role", "varchar(40)");
        addColumnIfMissing("project_member", "joined_at", "varchar(40)");
        addColumnIfMissing("project_member", "created_at", "varchar(40)");
        jdbc.execute("""
                create table if not exists project_task (
                    id varchar(120) primary key,
                    project_id varchar(120) not null,
                    title varchar(200) not null,
                    status varchar(40) not null,
                    tool_key varchar(120),
                    run_id varchar(120),
                    assignee_user_id varchar(80),
                    sort_order int not null,
                    due_at varchar(40),
                    completed_at varchar(40),
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null
                )
                """);
        addColumnIfMissing("project_task", "tool_key", "varchar(120)");
        addColumnIfMissing("project_task", "run_id", "varchar(120)");
        addColumnIfMissing("project_task", "completed_at", "varchar(40)");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try (var connection = jdbc.getDataSource().getConnection()) {
            var columns = connection.getMetaData().getColumns(null, null, table, column);
            if (!columns.next()) {
                jdbc.execute("alter table " + table + " add column " + column + " " + definition);
            }
        } catch (SQLException ignored) {
            // Metadata checks are best-effort to keep startup idempotent across H2 and PostgreSQL.
        } catch (RuntimeException ignored) {
            // Production service accounts may not own legacy tables. DDL is best-effort only.
        }
    }

    public void ensureDefaultTasks(String projectId, String assigneeUserId) {
        Integer count = jdbc.queryForObject("select count(*) from project_task where project_id=?", Integer.class, projectId);
        if (count != null && count > 0) {
            return;
        }
        var defaults = List.of(
                new TaskSeed("市场情报分析", "market-intelligence"),
                new TaskSeed("消费者洞察分析", "consumer-insight"),
                new TaskSeed("竞店分析", "competitor-analysis"),
                new TaskSeed("推广图制作", "promo-image"),
                new TaskSeed("地域优化", "geo-optimization"),
                new TaskSeed("项目复盘汇总", "project-dashboard"));
        var order = 10;
        for (var seed : defaults) {
            createTask(projectId, seed.title(), seed.toolKey(), assigneeUserId, order);
            order += 10;
        }
    }

    public ProjectTask createTask(String projectId, String title, String toolKey, String assigneeUserId, int sortOrder) {
        var now = Instant.now().toString();
        var id = "task_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into project_task (id, project_id, title, status, tool_key, run_id, assignee_user_id, sort_order, due_at, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, title, "TODO", toolKey, null, assigneeUserId, sortOrder, null, null, now, now);
        return findTask(id);
    }

    public List<ProjectTask> listTasks(String projectId) {
        return jdbc.query("""
                select id, project_id, title, status, tool_key, run_id, assignee_user_id, sort_order, due_at, completed_at, created_at, updated_at
                from project_task
                where project_id=?
                order by sort_order, created_at
                """, this::mapTask, projectId);
    }

    public List<ProjectTask> listTasks(Collection<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return List.of();
        }
        return projectIds.stream()
                .flatMap(projectId -> listTasks(projectId).stream())
                .toList();
    }

    public ProjectTask updateTaskStatus(String projectId, String taskId, String status) {
        var completedAt = "DONE".equals(status) ? Instant.now().toString() : null;
        jdbc.update("""
                update project_task
                set status=?, completed_at=?, updated_at=?
                where id=? and project_id=?
                """, status, completedAt, Instant.now().toString(), taskId, projectId);
        return findTask(taskId);
    }

    public void markTaskDoneByTool(String projectId, String toolKey, String runId) {
        jdbc.update("""
                update project_task
                set status='DONE', run_id=?, completed_at=?, updated_at=?
                where project_id=? and tool_key=? and status <> 'DONE'
                """, runId, Instant.now().toString(), Instant.now().toString(), projectId, toolKey);
    }

    public void detachRun(String projectId, String runId) {
        var now = Instant.now().toString();
        jdbc.update("""
                update project_task
                set status='TODO', run_id=null, completed_at=null, updated_at=?
                where project_id=? and run_id=?
                """, now, projectId, runId);
    }

    private ProjectTask findTask(String taskId) {
        return jdbc.query("""
                        select id, project_id, title, status, tool_key, run_id, assignee_user_id, sort_order, due_at, completed_at, created_at, updated_at
                        from project_task
                        where id=?
                        """, this::mapTask, taskId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("项目任务不存在: " + taskId));
    }

    private ProjectTask mapTask(java.sql.ResultSet rs, int rowNum) throws SQLException {
        return new ProjectTask(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("tool_key"),
                rs.getString("run_id"),
                rs.getString("assignee_user_id"),
                rs.getInt("sort_order"),
                rs.getString("due_at"),
                rs.getString("completed_at"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private record TaskSeed(String title, String toolKey) {}

    public record ProjectTask(
            String id,
            String projectId,
            String title,
            String status,
            String toolKey,
            String runId,
            String assigneeUserId,
            int sortOrder,
            String dueAt,
            String completedAt,
            String createdAt,
            String updatedAt) {}
}
