package com.yiqi.aitoolbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.yiqi.aitoolbench.ai.AiModelConfig;
import com.yiqi.aitoolbench.security.SecretCrypto;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkbenchRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final SecretCrypto secretCrypto;

    public WorkbenchRepository(JdbcTemplate jdbc, SecretCrypto secretCrypto) {
        this.jdbc = jdbc;
        this.secretCrypto = secretCrypto;
        createSchema();
    }

    public boolean hasBusinessData() {
        Integer count = jdbc.queryForObject("select count(*) from sys_user", Integer.class);
        return count != null && count > 0;
    }

    public LinkedHashMap<String, ApiController.ToolRun> loadRuns() {
        var result = new LinkedHashMap<String, ApiController.ToolRun>();
        String projectIdSelect = columnExists("tool_run", "project_id") ? "project_id" : "null as project_id";
        jdbc.query("""
                select run_id, tool_key, user_id, %s, status, event_type, input_summary, output_summary, output_data, started_at, finished_at
                from tool_run
                order by started_at desc, run_id desc
                """.formatted(projectIdSelect), (rs, rowNum) -> mapRun(rs)).forEach(run -> result.put(run.runId(), run));
        return result;
    }

    public void saveRun(ApiController.ToolRun run) {
        if (columnExists("tool_run", "project_id")) {
            upsert("""
                    update tool_run set tool_key=?, user_id=?, project_id=?, status=?, event_type=?, input_summary=?, output_summary=?, output_data=?, started_at=?, finished_at=? where run_id=?
                    """, """
                    insert into tool_run (run_id, tool_key, user_id, project_id, status, event_type, input_summary, output_summary, output_data, started_at, finished_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    new Object[]{run.toolKey(), run.userId(), run.projectId(), run.status(), run.eventType(), run.inputSummary(), run.outputSummary(), JsonSupport.toJson(run.outputData()), toText(run.startedAt()), toText(run.finishedAt()), run.runId()},
                    new Object[]{run.runId(), run.toolKey(), run.userId(), run.projectId(), run.status(), run.eventType(), run.inputSummary(), run.outputSummary(), JsonSupport.toJson(run.outputData()), toText(run.startedAt()), toText(run.finishedAt())});
            return;
        }
        upsert("""
                update tool_run set tool_key=?, user_id=?, status=?, event_type=?, input_summary=?, output_summary=?, output_data=?, started_at=?, finished_at=? where run_id=?
                """, """
                insert into tool_run (run_id, tool_key, user_id, status, event_type, input_summary, output_summary, output_data, started_at, finished_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new Object[]{run.toolKey(), run.userId(), run.status(), run.eventType(), run.inputSummary(), run.outputSummary(), JsonSupport.toJson(run.outputData()), toText(run.startedAt()), toText(run.finishedAt()), run.runId()},
                new Object[]{run.runId(), run.toolKey(), run.userId(), run.status(), run.eventType(), run.inputSummary(), run.outputSummary(), JsonSupport.toJson(run.outputData()), toText(run.startedAt()), toText(run.finishedAt())});
    }

    public LinkedHashMap<String, ApiController.UserAccountState> loadUsers() {
        var departments = new LinkedHashMap<String, List<String>>();
        jdbc.query("select user_id, department_id from sys_user_department order by user_id, sort_order, department_id", (rs) -> {
            departments.computeIfAbsent(rs.getString("user_id"), key -> new ArrayList<>()).add(rs.getString("department_id"));
        });
        var result = new LinkedHashMap<String, ApiController.UserAccountState>();
        jdbc.query("""
                select u.id, u.account, u.display_name as name,
                       coalesce((select role_code from sys_user_role where user_id = u.id order by role_code limit 1), 'member') as role,
                       u.password_hash as password, u.enabled, u.must_change_password, u.failed_attempts, u.locked_until
                from sys_user u
                where u.deleted_at is null
                order by u.created_at desc, u.id desc
                """, (rs, rowNum) -> mapUser(rs, departments.getOrDefault(rs.getString("id"), List.of())))
                .forEach(user -> result.put(user.id(), user));
        return result;
    }

    public void saveUser(ApiController.UserAccountState user) {
        jdbc.update("delete from sys_user_department where user_id = ?", user.id());
        var index = 0;
        for (var department : user.departmentKeys()) {
            jdbc.update("""
                    insert into sys_user_department (user_id, department_id, primary_department, data_scope, sort_order, created_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, user.id(), department, index == 0, "DEPARTMENT", index++, Instant.now().toString());
        }
    }

    public LinkedHashMap<String, ApiController.ToolConfig> loadToolConfigs() {
        var result = new LinkedHashMap<String, ApiController.ToolConfig>();
        jdbc.query("""
                select tool_key, name, description, department_key, status, theme, callback, callback_enabled, entry_url
                from tool_config
                order by created_at desc nulls last, tool_key desc
                """, (rs, rowNum) -> new ApiController.ToolConfig(
                rs.getString("tool_key"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("department_key"),
                rs.getString("status"),
                rs.getString("theme"),
                rs.getBoolean("callback"),
                rs.getBoolean("callback_enabled"),
                rs.getString("entry_url"))).forEach(tool -> result.put(tool.key(), tool));
        return result;
    }

    public void saveToolConfig(ApiController.ToolConfig tool) {
        upsert("""
                update tool_config set name=?, description=?, department_key=?, status=?, theme=?, callback=?, callback_enabled=?, entry_url=? where tool_key=?
                """, """
                insert into tool_config (tool_key, name, description, department_key, status, theme, callback, callback_enabled, entry_url)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new Object[]{tool.name(), tool.desc(), tool.departmentKey(), tool.status(), tool.theme(), tool.callback(), tool.callbackEnabled(), tool.entryUrl(), tool.key()},
                new Object[]{tool.key(), tool.name(), tool.desc(), tool.departmentKey(), tool.status(), tool.theme(), tool.callback(), tool.callbackEnabled(), tool.entryUrl()});
    }

    public LinkedHashMap<String, AiModelConfig> loadModelConfigs() {
        var result = new LinkedHashMap<String, AiModelConfig>();
        jdbc.query("""
                select id, name, provider, model_type, model_name, api_base_url,
                       api_key, api_key_encrypted, provider_options,
                       timeout_seconds, retry_count, enabled
                from model_config
                order by created_at desc nulls last, id desc
                """, (rs, rowNum) -> mapAndMigrateModelConfig(rs))
                .forEach(config -> result.put(config.id(), config));
        return result;
    }

    public void saveModelConfig(AiModelConfig config) {
        upsert("""
                update model_config set name=?, provider=?, model_type=?, model_name=?, api_base_url=?,
                    api_key='', api_key_encrypted=?, provider_options=?, timeout_seconds=?, retry_count=?, enabled=? where id=?
                """, """
                insert into model_config (id, name, provider, model_type, model_name, api_base_url,
                    api_key, api_key_encrypted, provider_options, timeout_seconds, retry_count, enabled, created_at)
                values (?, ?, ?, ?, ?, ?, '', ?, ?, ?, ?, ?, ?)
                """,
                new Object[]{config.name(), config.provider(), config.modelType(), config.modelName(), config.apiBaseUrl(), config.encryptedApiKey(), JsonSupport.toJson(config.providerOptions()), config.timeoutSeconds(), config.retryCount(), config.enabled(), config.id()},
                new Object[]{config.id(), config.name(), config.provider(), config.modelType(), config.modelName(), config.apiBaseUrl(), config.encryptedApiKey(), JsonSupport.toJson(config.providerOptions()), config.timeoutSeconds(), config.retryCount(), config.enabled(), Instant.now().toString()});
    }

    private AiModelConfig mapAndMigrateModelConfig(ResultSet rs) throws SQLException {
        var id = rs.getString("id");
        var legacyApiKey = rs.getString("api_key");
        var encryptedApiKey = rs.getString("api_key_encrypted");
        if ((encryptedApiKey == null || encryptedApiKey.isBlank()) && legacyApiKey != null && !legacyApiKey.isBlank()) {
            var candidate = secretCrypto.encrypt(legacyApiKey);
            var migrated = jdbc.update("""
                    update model_config set api_key_encrypted=?, api_key=''
                    where id=? and api_key=? and (api_key_encrypted is null or api_key_encrypted='')
                    """, candidate, id, legacyApiKey);
            if (migrated == 1) {
                encryptedApiKey = candidate;
                legacyApiKey = "";
            } else {
                var current = jdbc.queryForMap(
                        "select api_key, api_key_encrypted from model_config where id=?", id);
                legacyApiKey = current.get("api_key") == null ? "" : String.valueOf(current.get("api_key"));
                encryptedApiKey = current.get("api_key_encrypted") == null
                        ? "" : String.valueOf(current.get("api_key_encrypted"));
                if (encryptedApiKey.isBlank()) {
                    throw new IllegalStateException("模型凭证并发迁移失败: " + id);
                }
            }
        }
        if (encryptedApiKey != null && !encryptedApiKey.isBlank()
                && legacyApiKey != null && !legacyApiKey.isBlank()) {
            jdbc.update("""
                    update model_config set api_key=''
                    where id=? and api_key=? and api_key_encrypted=?
                    """, id, legacyApiKey, encryptedApiKey);
        }
        return new AiModelConfig(
                id,
                rs.getString("name"),
                defaultText(rs.getString("provider"), "openai-compatible"),
                rs.getString("model_type"),
                rs.getString("model_name"),
                rs.getString("api_base_url"),
                encryptedApiKey == null ? "" : encryptedApiKey,
                JsonSupport.toMap(rs.getString("provider_options")),
                rs.getInt("timeout_seconds"),
                rs.getInt("retry_count"),
                rs.getBoolean("enabled"));
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public List<ApiController.ProjectBoard> loadProjects() {
        var members = grouped("project_member", "project_id", "member_name");
        var memberUserIds = groupedNonBlank("project_member", "project_id", "member_user_id");
        var suggestions = grouped("project_suggestion", "project_id", "suggestion");
        return jdbc.query("""
                select id, name, owner,
                       case when deleted_at is not null then '已删除' else status end as status,
                       result_count, token_usage, image_count, summary, details, created_at,
                       department_id, owner_user_id, created_by
                from project_board
                order by created_at desc nulls last, id desc
                """, (rs, rowNum) -> {
            var id = rs.getString("id");
            var details = JsonSupport.toMap(rs.getString("details"));
            var departmentId = rs.getString("department_id");
            if ((departmentId == null || departmentId.isBlank()) && details.get("departmentId") != null) {
                departmentId = String.valueOf(details.get("departmentId"));
            }
            return new ApiController.ProjectBoard(
                    id,
                    rs.getString("name"),
                    rs.getString("owner"),
                    rs.getString("status"),
                    members.getOrDefault(id, List.of()),
                    rs.getInt("result_count"),
                    rs.getString("token_usage"),
                    rs.getInt("image_count"),
                    rs.getString("summary"),
                    details,
                    suggestions.getOrDefault(id, List.of()),
                    rs.getString("created_at"),
                    departmentId,
                    rs.getString("owner_user_id"),
                    memberUserIds.getOrDefault(id, List.of()),
                    rs.getString("created_by"),
                    false,
                    false);
        });
    }

    public void saveProject(ApiController.ProjectBoard project) {
        upsert("""
                update project_board set name=?, owner=?, status=?, result_count=?, token_usage=?, image_count=?,
                    summary=?, details=?, department_id=?, owner_user_id=?, created_by=?,
                    created_at=coalesce(created_at, ?), updated_at=? where id=?
                """, """
                insert into project_board (id, name, owner, status, result_count, token_usage, image_count, summary,
                    details, department_id, owner_user_id, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new Object[]{project.name(), project.owner(), project.status(), project.resultCount(),
                        project.tokenUsage(), project.imageCount(), project.summary(), JsonSupport.toJson(project.details()),
                        project.departmentId(), project.ownerUserId(), project.createdBy(), project.createdAt(),
                        Instant.now().toString(), project.id()},
                new Object[]{project.id(), project.name(), project.owner(), project.status(), project.resultCount(),
                        project.tokenUsage(), project.imageCount(), project.summary(), JsonSupport.toJson(project.details()),
                        project.departmentId(), project.ownerUserId(), project.createdBy(), project.createdAt(),
                        Instant.now().toString()});
        replaceProjectMembers(project);
        replaceList("project_suggestion", "project_id", "suggestion", project.id(), project.suggestions());
    }

    public void logicalDeleteProject(String projectId, String deletedBy) {
        var now = Instant.now().toString();
        int updated = jdbc.update("""
                update project_board
                set status='已删除', deleted_at=?, deleted_by=?, updated_at=?
                where id=? and deleted_at is null
                """, now, deletedBy, now, projectId);
        if (updated == 0) {
            throw new IllegalArgumentException("项目不存在或已删除: " + projectId);
        }
    }

    private void replaceProjectMembers(ApiController.ProjectBoard project) {
        jdbc.update("delete from project_member where project_id=?", project.id());
        var memberUserIds = project.memberUserIds() == null ? List.<String>of() : project.memberUserIds();
        var names = project.members() == null ? List.<String>of() : project.members();
        var now = Instant.now().toString();
        var storedNames = new java.util.HashSet<String>();
        for (var index = 0; index < memberUserIds.size(); index++) {
            var userId = memberUserIds.get(index);
            var memberName = index < names.size() ? names.get(index) : userId;
            if (!storedNames.add(memberName)) {
                // 兼容仍保留旧 (project_id, member_name) 主键的环境；授权始终使用 member_user_id。
                memberName = memberName + " [" + userId + "]";
                storedNames.add(memberName);
            }
            jdbc.update("""
                    insert into project_member
                        (project_id, member_name, sort_order, member_user_id, project_role, joined_at, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, project.id(), memberName, index, userId,
                    userId.equals(project.ownerUserId()) ? "PROJECT_OWNER" : "PROJECT_MEMBER", now, now);
        }
    }

    public List<ApiController.ProjectResultLink> loadProjectLinks() {
        return jdbc.query("""
                select id, project_id, run_id, category, remark, submitted_by, output_summary, result_snapshot, linked_at
                from project_result_link
                order by linked_at desc
                """, (rs, rowNum) -> new ApiController.ProjectResultLink(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("run_id"),
                rs.getString("category"),
                rs.getString("remark"),
                rs.getString("submitted_by"),
                rs.getString("output_summary"),
                JsonSupport.toMap(rs.getString("result_snapshot")),
                Instant.parse(rs.getString("linked_at"))));
    }

    public void saveProjectLink(ApiController.ProjectResultLink link) {
        upsert("""
                update project_result_link set project_id=?, run_id=?, category=?, remark=?, submitted_by=?, output_summary=?, result_snapshot=?, linked_at=? where id=?
                """, """
                insert into project_result_link (id, project_id, run_id, category, remark, submitted_by, output_summary, result_snapshot, linked_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new Object[]{link.projectId(), link.runId(), link.category(), link.remark(), link.submittedBy(), link.outputSummary(), JsonSupport.toJson(link.resultSnapshot()), toText(link.linkedAt()), link.id()},
                new Object[]{link.id(), link.projectId(), link.runId(), link.category(), link.remark(), link.submittedBy(), link.outputSummary(), JsonSupport.toJson(link.resultSnapshot()), toText(link.linkedAt())});
    }

    public void deleteProjectLink(String id) {
        jdbc.update("delete from project_result_link where id = ?", id);
    }

    public List<ApiController.OrganizationDepartment> loadOrganizationDepartments() {
        return jdbc.query("""
                select id, parent_id, name, enabled, sort_order, created_at, updated_at
                from organization_department
                where deleted_at is null
                order by sort_order, created_at
                """, (rs, rowNum) -> new ApiController.OrganizationDepartment(
                rs.getString("id"),
                rs.getString("parent_id"),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getInt("sort_order"),
                rs.getString("created_at"),
                rs.getString("updated_at")));
    }

    public void saveOrganizationDepartment(ApiController.OrganizationDepartment department) {
        upsert("""
                update organization_department set parent_id=?, name=?, enabled=?, sort_order=?, updated_at=? where id=?
                """, """
                insert into organization_department (id, parent_id, name, enabled, sort_order, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                new Object[]{department.parentId(), department.name(), department.enabled(), department.sortOrder(), department.updatedAt(), department.id()},
                new Object[]{department.id(), department.parentId(), department.name(), department.enabled(), department.sortOrder(), department.createdAt(), department.updatedAt()});
    }

    public void logicalDeleteOrganizationDepartment(String departmentId, String deletedBy) {
        var now = Instant.now().toString();
        int updated = jdbc.update("""
                update organization_department
                set enabled=false, deleted_at=?, deleted_by=?, updated_at=?
                where id=? and deleted_at is null
                """, now, deletedBy, now, departmentId);
        if (updated == 0) {
            throw new IllegalArgumentException("组织部门不存在: " + departmentId);
        }
    }

    public List<ApiController.DeploymentRequest> loadDeploymentRequests() {
        var errors = grouped("deployment_error", "deployment_id", "error_message");
        return jdbc.query("""
                select id, tool_key, name, version, requested_by, status, created_at
                from deployment_request
                order by created_at desc
                """, (rs, rowNum) -> new ApiController.DeploymentRequest(
                rs.getString("id"),
                rs.getString("tool_key"),
                rs.getString("name"),
                rs.getString("version"),
                rs.getString("requested_by"),
                rs.getString("status"),
                errors.getOrDefault(rs.getString("id"), List.of()),
                Instant.parse(rs.getString("created_at"))));
    }

    public void saveDeploymentRequest(ApiController.DeploymentRequest request) {
        upsert("""
                update deployment_request set tool_key=?, name=?, version=?, requested_by=?, status=?, created_at=? where id=?
                """, """
                insert into deployment_request (id, tool_key, name, version, requested_by, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                new Object[]{request.toolKey(), request.name(), request.version(), request.requestedBy(), request.status(), toText(request.createdAt()), request.id()},
                new Object[]{request.id(), request.toolKey(), request.name(), request.version(), request.requestedBy(), request.status(), toText(request.createdAt())});
        replaceList("deployment_error", "deployment_id", "error_message", request.id(), request.errors());
    }

    private ApiController.ToolRun mapRun(ResultSet rs) throws SQLException {
        return new ApiController.ToolRun(
                rs.getString("run_id"),
                rs.getString("tool_key"),
                rs.getString("user_id"),
                rs.getString("project_id"),
                rs.getString("status"),
                rs.getString("event_type"),
                rs.getString("input_summary"),
                rs.getString("output_summary"),
                JsonSupport.toMap(rs.getString("output_data")),
                parseInstant(rs.getString("started_at")),
                parseInstant(rs.getString("finished_at")));
    }

    private ApiController.UserAccountState mapUser(ResultSet rs, List<String> departments) throws SQLException {
        return new ApiController.UserAccountState(
                rs.getString("id"),
                rs.getString("account"),
                rs.getString("name"),
                rs.getString("role"),
                departments,
                rs.getString("password"),
                rs.getBoolean("enabled"),
                rs.getBoolean("must_change_password"),
                rs.getInt("failed_attempts"),
                parseInstant(rs.getString("locked_until")));
    }

    private Map<String, List<String>> grouped(String table, String ownerColumn, String valueColumn) {
        var result = new LinkedHashMap<String, List<String>>();
        jdbc.query("select " + ownerColumn + ", " + valueColumn + " from " + table + " order by " + ownerColumn + ", sort_order", rs -> {
            result.computeIfAbsent(rs.getString(ownerColumn), key -> new ArrayList<>()).add(rs.getString(valueColumn));
        });
        return result;
    }

    private Map<String, List<String>> groupedNonBlank(String table, String ownerColumn, String valueColumn) {
        var result = new LinkedHashMap<String, List<String>>();
        jdbc.query("select " + ownerColumn + ", " + valueColumn + " from " + table
                + " where " + valueColumn + " is not null order by " + ownerColumn + ", sort_order", rs -> {
            var value = rs.getString(valueColumn);
            if (value != null && !value.isBlank()) {
                result.computeIfAbsent(rs.getString(ownerColumn), key -> new ArrayList<>()).add(value);
            }
        });
        return result;
    }

    private void replaceList(String table, String ownerColumn, String valueColumn, String ownerId, List<String> values) {
        jdbc.update("delete from " + table + " where " + ownerColumn + " = ?", ownerId);
        var index = 0;
        for (var value : values == null ? List.<String>of() : values) {
            jdbc.update("insert into " + table + " (" + ownerColumn + ", " + valueColumn + ", sort_order) values (?, ?, ?)", ownerId, value, index++);
        }
    }

    private void upsert(String updateSql, String insertSql, Object[] updateArgs, Object[] insertArgs) {
        int updated = jdbc.update(updateSql, updateArgs);
        if (updated == 0) {
            jdbc.update(insertSql, insertArgs);
        }
    }

    private String toText(Instant value) {
        return value == null ? null : value.toString();
    }

    private Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists app_user (
                    id varchar(80) primary key,
                    account varchar(120) not null unique,
                    name varchar(120) not null,
                    role varchar(40) not null,
                    password varchar(255) not null,
                    enabled boolean not null,
                    must_change_password boolean not null,
                    failed_attempts int not null,
                    locked_until varchar(40)
                )
                """);
        jdbc.execute("""
                create table if not exists user_department (
                    user_id varchar(80) not null,
                    department_key varchar(80) not null,
                    sort_order int not null,
                    primary key (user_id, department_key)
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
        jdbc.execute("""
                create table if not exists organization_department (
                    id varchar(80) primary key,
                    parent_id varchar(80),
                    name varchar(120) not null,
                    enabled boolean not null,
                    sort_order int not null,
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null
                )
                """);
        jdbc.execute("alter table organization_department add column if not exists deleted_at varchar(40)");
        jdbc.execute("alter table organization_department add column if not exists deleted_by varchar(80)");
        jdbc.execute("""
                create table if not exists tool_config (
                    tool_key varchar(120) primary key,
                    name varchar(120) not null,
                    description varchar(500) not null,
                    department_key varchar(80) not null,
                    status varchar(40) not null,
                    theme varchar(40) not null,
                    callback boolean not null,
                    callback_enabled boolean not null
                )
                """);
        jdbc.execute("""
                create table if not exists model_config (
                    id varchar(120) primary key,
                    name varchar(120) not null,
                    provider varchar(80) not null default 'openai-compatible',
                    model_type varchar(40) not null,
                    model_name varchar(160) not null,
                    api_base_url varchar(500) not null,
                    api_key varchar(500) not null default '',
                    api_key_encrypted text,
                    provider_options text not null default '{}',
                    timeout_seconds int not null,
                    retry_count int not null,
                    enabled boolean not null,
                    created_at varchar(40) not null
                )
                """);
        addColumnIfMissing("model_config", "provider", "varchar(80) default 'openai-compatible'");
        addColumnIfMissing("model_config", "provider_options", "text default '{}'");
        addColumnIfMissing("model_config", "api_key_encrypted", "text");
        addColumnIfMissing("model_config", "created_at", "varchar(40)");
        // 旧模型配置没有创建时间，使用固定迁移基线，避免把每次服务启动都误记为创建时间。
        jdbc.update("update model_config set created_at=? where created_at is null", Instant.EPOCH.toString());
        jdbc.execute("""
                create table if not exists tool_run (
                    run_id varchar(120) primary key,
                    tool_key varchar(120) not null,
                    user_id varchar(80) not null,
                    project_id varchar(120),
                    status varchar(40) not null,
                    event_type varchar(40) not null,
                    input_summary text,
                    output_summary text,
                    output_data text not null,
                    started_at varchar(40) not null,
                    finished_at varchar(40)
                )
                """);
        addColumnIfMissing("tool_run", "project_id", "varchar(120)");
        jdbc.execute("""
                create table if not exists project_board (
                    id varchar(120) primary key,
                    name varchar(200) not null,
                    owner varchar(120) not null,
                    status varchar(40) not null,
                    result_count int not null,
                    token_usage varchar(80) not null,
                    image_count int not null,
                    summary text not null
                )
                """);
        jdbc.execute("alter table project_board add column if not exists details text");
        jdbc.execute("alter table project_board add column if not exists created_at varchar(40)");
        addColumnIfMissing("project_board", "department_id", "varchar(80)");
        addColumnIfMissing("project_board", "owner_user_id", "varchar(80)");
        addColumnIfMissing("project_board", "created_by", "varchar(80)");
        addColumnIfMissing("project_board", "updated_at", "varchar(40)");
        addColumnIfMissing("project_board", "deleted_at", "varchar(40)");
        addColumnIfMissing("project_board", "deleted_by", "varchar(80)");
        jdbc.execute("""
                create table if not exists project_member (
                    project_id varchar(120) not null,
                    member_name varchar(120) not null,
                    sort_order int not null,
                    primary key (project_id, member_name)
                )
                """);
        jdbc.execute("""
                create table if not exists project_suggestion (
                    project_id varchar(120) not null,
                    suggestion text not null,
                    sort_order int not null
                )
                """);
        jdbc.execute("""
                create table if not exists project_result_link (
                    id varchar(120) primary key,
                    project_id varchar(120) not null,
                    run_id varchar(120) not null,
                    category varchar(120) not null,
                    remark text not null,
                    submitted_by varchar(120) not null,
                    output_summary text,
                    result_snapshot text not null,
                    linked_at varchar(40) not null
                )
                """);
        jdbc.execute("""
                create table if not exists deployment_request (
                    id varchar(120) primary key,
                    tool_key varchar(120) not null,
                    name varchar(200) not null,
                    version varchar(80) not null,
                    requested_by varchar(120) not null,
                    status varchar(40) not null,
                    created_at varchar(40) not null
                )
                """);
        jdbc.execute("""
                create table if not exists deployment_error (
                    deployment_id varchar(120) not null,
                    error_message text not null,
                    sort_order int not null
                )
                """);
        addColumnIfMissing("tool_config", "category_key", "varchar(80)");
        addColumnIfMissing("tool_config", "availability_status", "varchar(40)");
        addColumnIfMissing("tool_config", "integration_type", "varchar(40)");
        addColumnIfMissing("tool_config", "entry_url", "varchar(500)");
        addColumnIfMissing("tool_config", "owner", "varchar(120)");
        addColumnIfMissing("tool_config", "version", "varchar(80)");
        addColumnIfMissing("tool_config", "ai_gateway_required", "boolean");
        addColumnIfMissing("tool_config", "created_at", "varchar(40)");
        addColumnIfMissing("tool_config", "updated_at", "varchar(40)");
        addColumnIfMissing("tool_run", "department_id", "varchar(80)");
        addColumnIfMissing("tool_run", "input_payload", "text");
        addColumnIfMissing("tool_run", "result_payload", "text");
        addColumnIfMissing("tool_run", "error_message", "text");
        addColumnIfMissing("tool_run", "created_at", "varchar(40)");
        addColumnIfMissing("tool_run", "updated_at", "varchar(40)");
        addColumnIfMissing("project_member", "member_user_id", "varchar(80)");
        addColumnIfMissing("project_member", "project_role", "varchar(40)");
        addColumnIfMissing("project_member", "joined_at", "varchar(40)");
        addColumnIfMissing("project_member", "created_at", "varchar(40)");
        migrateProjectMemberIdentityKey();
    }

    private void migrateProjectMemberIdentityKey() {
        try {
            jdbc.execute("alter table project_member drop constraint if exists project_member_pkey");
            jdbc.execute("""
                    create unique index if not exists ux_project_member_user
                    on project_member(project_id, member_user_id)
                    """);
        } catch (RuntimeException ignored) {
            // 无 DDL 权限时保留旧约束；权限逻辑仍使用 member_user_id，运维可按发布清单执行迁移。
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            if (!columnExists(table, column)) {
                jdbc.execute("alter table " + table + " add column " + column + " " + definition);
            }
        } catch (RuntimeException ignored) {
            // Production service accounts may not own legacy tables. DDL is best-effort only.
        }
    }

    private boolean columnExists(String table, String column) {
        try (var connection = jdbc.getDataSource().getConnection()) {
            var columns = connection.getMetaData().getColumns(null, null, table, column);
            return columns.next();
        } catch (SQLException | RuntimeException ignored) {
            return false;
        }
    }
}
