package com.yiqi.aitoolbench.tool;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ToolRepository {
    private static final String DETAIL_PAGE_TOOL_KEY = ToolAvailabilityPolicy.DETAIL_PAGE_TOOL_KEY;
    private static final String DETAIL_PAGE_SSO_TOOL_KEY = ToolAvailabilityPolicy.DETAIL_PAGE_SSO_TOOL_KEY;
    private final JdbcTemplate jdbc;
    private final String detailPageEntryUrl;
    private final String cloudAiEntryUrl;

    public ToolRepository(
            JdbcTemplate jdbc,
            @Value("${app.detail-page.entry-url:https://detailpage.sharpseek.cn/}") String detailPageEntryUrl,
            @Value("${app.cloud-ai.entry-url:}") String cloudAiEntryUrl) {
        this.jdbc = jdbc;
        this.detailPageEntryUrl = detailPageEntryUrl;
        this.cloudAiEntryUrl = cloudAiEntryUrl;
        createSchema();
        seedData();
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists ai_task_category (
                    category_key varchar(80) primary key,
                    name varchar(120) not null,
                    description varchar(500),
                    enabled boolean not null,
                    sort_order int not null,
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null
                )
                """);
        jdbc.execute("""
                create table if not exists tool_config (
                    tool_key varchar(120) primary key,
                    name varchar(120) not null,
                    description varchar(500) not null,
                    department_key varchar(80) not null,
                    status varchar(40) not null,
                    theme varchar(40) not null,
                    callback boolean not null,
                    callback_enabled boolean not null,
                    category_key varchar(80),
                    availability_status varchar(40),
                    integration_type varchar(40),
                    entry_url varchar(500),
                    owner varchar(120),
                    version varchar(80),
                    ai_gateway_required boolean,
                    created_at varchar(40),
                    updated_at varchar(40)
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
        jdbc.execute("""
                create table if not exists role_tool_permission (
                    role_code varchar(80) not null,
                    tool_key varchar(120) not null,
                    allowed boolean not null,
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null,
                    primary key (role_code, tool_key)
                )
                """);
        jdbc.execute("""
                create table if not exists app_seed_marker (
                    seed_key varchar(120) primary key,
                    applied_at varchar(40) not null
                )
                """);
    }

    private void seedData() {
        var now = Instant.now().toString();
        seedCategory("ai-market", "AI 市场部", 10, now);
        seedCategory("ai-design", "AI 设计部", 20, now);
        seedCategory("ai-operation", "AI 运营部", 30, now);
        seedCategory("ai-hr", "AI 人事部", 40, now);
        seedCategory("ai-content", "AI 内容部", 50, now);

        var tools = List.of(
                new ToolSeed("market-intelligence", "市场情报", "同源数据生成 A/B 两类看板", "ai-market", ToolAvailabilityPolicy.defaultStatus("market-intelligence"), "blue", true),
                new ToolSeed("consumer-insight", "消费者洞察", "目标人群需求痛点", "ai-market", ToolAvailabilityPolicy.defaultStatus("consumer-insight"), "purple", true),
                new ToolSeed("product-dev", "选品与开发", "数据驱动选品决策", "ai-market", ToolAvailabilityPolicy.defaultStatus("product-dev"), "green", true),
                new ToolSeed("competitor-analysis", "竞店分析", "竞争对手拆解分析", "ai-market", ToolAvailabilityPolicy.defaultStatus("competitor-analysis"), "orange", true),
                new ToolSeed("model-render", "建模与渲染", "一键渲染高级场景图", "ai-design", ToolAvailabilityPolicy.defaultStatus("model-render"), "red", true),
                new ToolSeed("promo-image", "推广图制作", "主图与推广素材生成", "ai-design", ToolAvailabilityPolicy.defaultStatus("promo-image"), "cyan", true),
                new ToolSeed("detail-page", "详情页制作", "详情页视觉生成", "ai-design", ToolAvailabilityPolicy.defaultStatus("detail-page"), "blue", true),
                new ToolSeed(ToolAvailabilityPolicy.CLOUD_AI_TOOL_KEY, "AI 文案与生图", "Coze 文案、豆包反推与 GPT 生图", "ai-design", cloudAiEntryUrl.isBlank() ? "unopened" : "normal", "purple", true),
                new ToolSeed(DETAIL_PAGE_SSO_TOOL_KEY, "详情页制作（新版）", "工作台登录与统一 AI 中转", "ai-design", ToolAvailabilityPolicy.defaultStatus(DETAIL_PAGE_SSO_TOOL_KEY), "blue", true),
                new ToolSeed("package-design", "包装设计", "预留入口，暂未开放", "ai-design", ToolAvailabilityPolicy.defaultStatus("package-design"), "gray", false),
                new ToolSeed("geo-optimization", "地域优化", "广告投放地域建议", "ai-operation", ToolAvailabilityPolicy.defaultStatus("geo-optimization"), "green", true),
                new ToolSeed("project-dashboard", "项目管理看板", "汇总工具结果与项目洞察", "ai-operation", ToolAvailabilityPolicy.defaultStatus("project-dashboard"), "blue", true),
                new ToolSeed("keyword-demand", "关键词需求分析", "后续工具预留入口", "ai-operation", ToolAvailabilityPolicy.defaultStatus("keyword-demand"), "purple", false),
                new ToolSeed("title-maker", "标题制作", "后续工具预留入口", "ai-operation", ToolAvailabilityPolicy.defaultStatus("title-maker"), "orange", false),
                new ToolSeed("attendance-data", "考勤数据", "自动分析月度考勤异常", "ai-hr", ToolAvailabilityPolicy.defaultStatus("attendance-data"), "purple", true),
                new ToolSeed("main-video", "主图视频", "二期预留入口", "ai-content", ToolAvailabilityPolicy.defaultStatus("main-video"), "orange", false),
                new ToolSeed("ad-material", "投放素材", "二期预留入口", "ai-content", ToolAvailabilityPolicy.defaultStatus("ad-material"), "blue", false));
        for (var tool : tools) {
            if (seedTool(tool, now)) {
                seedRoleToolPermission("admin", tool.key(), now);
                if ("ONLINE".equals(mappedStatus(tool.legacyStatus()))) {
                    seedRoleToolPermission("department_leader", tool.key(), now);
                    seedRoleToolPermission("member", tool.key(), now);
                }
            }
        }
        seedDefaultRoleToolsIfEmpty("admin", tools, false, now);
        seedDefaultRoleToolsIfEmpty("department_leader", tools, true, now);
        seedDefaultRoleToolsIfEmpty("member", tools, true, now);
        migrateDetailPageExternalEntry(now);
        migrateDetailPageWorkbenchIntegration(now);
        syncDetailPageEntryUrl(now);
        migrateDetailPageSsoEntry(now);
        syncDetailPageSsoEntryUrl(now);
        syncCloudAiEntry(now);
        seedMarker("builtin-role-tool-permissions-v1", now);
        // 独立入口不加入普通工具默认授权，首次注册仅授予管理员。
        if (seedTool(new ToolSeed(ToolAvailabilityPolicy.NEW_CHAT_TOOL_KEY, "new chat",
                "无需关联项目的 AI 对话与创作", "ai-content", "normal", "purple", false), now)) {
            seedRoleToolPermission("admin", ToolAvailabilityPolicy.NEW_CHAT_TOOL_KEY, now);
            if (hasToolMetadataColumns()) {
                jdbc.update("update tool_config set integration_type='EXTERNAL', ai_gateway_required=false where tool_key=?",
                        ToolAvailabilityPolicy.NEW_CHAT_TOOL_KEY);
            }
        }
    }

    private void migrateDetailPageExternalEntry(String now) {
        var migrationKey = "detail-page-external-entry-v1";
        if (exists("select count(*) from app_seed_marker where seed_key=?", migrationKey)) {
            return;
        }
        jdbc.update("""
                update tool_config
                set status='normal', availability_status='ONLINE', entry_url=?, updated_at=?
                where tool_key=?
                """, detailPageEntryUrl, now, DETAIL_PAGE_TOOL_KEY);
        seedRoleToolPermission("department_leader", DETAIL_PAGE_TOOL_KEY, now);
        seedRoleToolPermission("member", DETAIL_PAGE_TOOL_KEY, now);
        seedMarker(migrationKey, now);
    }

    private void migrateDetailPageWorkbenchIntegration(String now) {
        var migrationKey = "detail-page-workbench-integration-v1";
        if (exists("select count(*) from app_seed_marker where seed_key=?", migrationKey)) {
            return;
        }
        if (hasToolMetadataColumns()) {
            jdbc.update("""
                    update tool_config
                    set status='normal', availability_status='ONLINE', callback=true, callback_enabled=true,
                        integration_type='EXTERNAL', entry_url=?, ai_gateway_required=true, version='1.0.0', updated_at=?
                    where tool_key=?
                    """, detailPageEntryUrl, now, DETAIL_PAGE_TOOL_KEY);
        } else {
            jdbc.update("""
                    update tool_config
                    set status='normal', callback=true, callback_enabled=true, entry_url=?
                    where tool_key=?
                    """, detailPageEntryUrl, DETAIL_PAGE_TOOL_KEY);
        }
        seedMarker(migrationKey, now);
    }

    private void syncDetailPageEntryUrl(String now) {
        jdbc.update("""
                update tool_config
                set entry_url=?, updated_at=?
                where tool_key=? and (entry_url is null or entry_url<>?)
                """, detailPageEntryUrl, now, DETAIL_PAGE_TOOL_KEY, detailPageEntryUrl);
    }

    private void migrateDetailPageSsoEntry(String now) {
        var migrationKey = "detail-page-sso-entry-v1";
        if (exists("select count(*) from app_seed_marker where seed_key=?", migrationKey)) {
            return;
        }
        jdbc.update("""
                update tool_config
                set status='normal', availability_status='ONLINE', callback=true, callback_enabled=true,
                    integration_type='EXTERNAL', entry_url=?, ai_gateway_required=true, version='1.0.0', updated_at=?
                where tool_key=?
                """, detailPageEntryUrl, now, DETAIL_PAGE_SSO_TOOL_KEY);
        seedMarker(migrationKey, now);
    }

    private void syncDetailPageSsoEntryUrl(String now) {
        jdbc.update("""
                update tool_config
                set entry_url=?, updated_at=?
                where tool_key=? and (entry_url is null or entry_url<>?)
                """, detailPageEntryUrl, now, DETAIL_PAGE_SSO_TOOL_KEY, detailPageEntryUrl);
    }

    private void syncCloudAiEntry(String now) {
        if (cloudAiEntryUrl.isBlank()) {
            jdbc.update("update tool_config set status='unopened', availability_status='COMING_SOON', entry_url='', updated_at=? where tool_key=?",
                    now, ToolAvailabilityPolicy.CLOUD_AI_TOOL_KEY);
            return;
        }
        jdbc.update("""
                update tool_config set status='normal', availability_status='ONLINE', callback=true,
                    callback_enabled=true, integration_type='EXTERNAL', entry_url=?, ai_gateway_required=true,
                    updated_at=? where tool_key=?
                """, cloudAiEntryUrl, now, ToolAvailabilityPolicy.CLOUD_AI_TOOL_KEY);
        jdbc.update("""
                update tool_config set entry_url=?, integration_type='EXTERNAL', ai_gateway_required=true,
                    updated_at=? where tool_key=?
                """, cloudAiEntryUrl, now, ToolAvailabilityPolicy.INTEGRATED_TOOL_KEY);
        for (var role : List.of("admin", "department_leader", "member")) {
            seedRoleToolPermission(role, ToolAvailabilityPolicy.CLOUD_AI_TOOL_KEY, now);
        }
    }

    private void seedCategory(String key, String name, int sortOrder, String now) {
        if (!exists("select count(*) from ai_task_category where category_key=?", key)) {
            jdbc.update("""
                    insert into ai_task_category (category_key, name, description, enabled, sort_order, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, key, name, "", true, sortOrder, now, now);
        }
    }

    private boolean seedTool(ToolSeed tool, String now) {
        if (exists("select count(*) from tool_config where tool_key=?", tool.key())) {
            return false;
        }
        if (hasToolMetadataColumns()) {
            jdbc.update("""
                    insert into tool_config (
                        tool_key, name, description, department_key, status, theme, callback, callback_enabled,
                        category_key, availability_status, integration_type, entry_url, owner, version,
                        ai_gateway_required, created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tool.key(), tool.name(), tool.description(), tool.categoryKey(), tool.legacyStatus(), tool.theme(),
                    tool.callbackEnabled(), tool.callbackEnabled(), tool.categoryKey(), mappedStatus(tool.legacyStatus()),
                    tool.callbackEnabled() ? "CALLBACK" : "RESERVED", "/tools/" + tool.key(), "system", "0.1.0",
                    tool.callbackEnabled(), now, now);
            return true;
        }
        jdbc.update("""
                insert into tool_config (tool_key, name, description, department_key, status, theme, callback, callback_enabled)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tool.key(), tool.name(), tool.description(), tool.categoryKey(), tool.legacyStatus(), tool.theme(),
                tool.callbackEnabled(), tool.callbackEnabled());
        return true;
    }

    private void seedRoleToolPermission(String roleCode, String toolKey, String now) {
        if (!exists("select count(*) from role_tool_permission where role_code=? and tool_key=?", roleCode, toolKey)) {
            jdbc.update("""
                    insert into role_tool_permission (role_code, tool_key, allowed, created_at, updated_at)
                    values (?, ?, ?, ?, ?)
                    """, roleCode, toolKey, true, now, now);
        }
    }

    private void seedDefaultRoleToolsIfEmpty(String roleCode, List<ToolSeed> tools, boolean onlineOnly, String now) {
        if (exists("select count(*) from app_seed_marker where seed_key=?", "builtin-role-tool-permissions-v1")) {
            return;
        }
        if (exists("select count(*) from role_tool_permission where role_code=?", roleCode)) {
            return;
        }
        for (var tool : tools) {
            if (!onlineOnly || "ONLINE".equals(mappedStatus(tool.legacyStatus()))) {
                seedRoleToolPermission(roleCode, tool.key(), now);
            }
        }
    }

    private void seedMarker(String seedKey, String now) {
        if (!exists("select count(*) from app_seed_marker where seed_key=?", seedKey)) {
            jdbc.update("insert into app_seed_marker (seed_key, applied_at) values (?, ?)", seedKey, now);
        }
    }

    private String mappedStatus(String legacyStatus) {
        return switch (legacyStatus) {
            case "normal" -> "ONLINE";
            case "unopened" -> "COMING_SOON";
            case "maintenance" -> "MAINTENANCE";
            case "disabled" -> "DISABLED";
            default -> "DISABLED";
        };
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try (var connection = jdbc.getDataSource().getConnection()) {
            var columns = connection.getMetaData().getColumns(null, null, table, column);
            if (!columns.next()) {
                jdbc.execute("alter table " + table + " add column " + column + " " + definition);
            }
        } catch (SQLException ignored) {
            // Existing installations may already have a compatible column with different metadata casing.
        } catch (RuntimeException ignored) {
            // Production service accounts may not own legacy tables. DDL is best-effort only.
        }
    }

    private boolean hasToolMetadataColumns() {
        return columnExists("tool_config", "category_key")
                && columnExists("tool_config", "availability_status")
                && columnExists("tool_config", "integration_type")
                && columnExists("tool_config", "entry_url")
                && columnExists("tool_config", "owner")
                && columnExists("tool_config", "version")
                && columnExists("tool_config", "ai_gateway_required")
                && columnExists("tool_config", "created_at")
                && columnExists("tool_config", "updated_at");
    }

    private boolean columnExists(String table, String column) {
        try (var connection = jdbc.getDataSource().getConnection()) {
            var columns = connection.getMetaData().getColumns(null, null, table, column);
            return columns.next();
        } catch (SQLException | RuntimeException ignored) {
            return false;
        }
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    public boolean hasToolAccess(String userId, String toolKey) {
        // The SSO key is an internal route of the visible detail-page tool.
        if (DETAIL_PAGE_SSO_TOOL_KEY.equals(toolKey)) toolKey = DETAIL_PAGE_TOOL_KEY;
        // Cloud AI is launched from the visible promo-image card and inherits its access rules.
        if (ToolAvailabilityPolicy.CLOUD_AI_TOOL_KEY.equals(toolKey)) toolKey = ToolAvailabilityPolicy.INTEGRATED_TOOL_KEY;
        String availabilityPredicate = columnExists("tool_config", "availability_status")
                ? """
                  coalesce(tc.availability_status,
                    case
                      when tc.status = 'normal' then 'ONLINE'
                      when tc.status = 'unopened' then 'COMING_SOON'
                      when tc.status = 'maintenance' then 'MAINTENANCE'
                      else 'DISABLED'
                    end
                  ) <> 'DISABLED'
                  """
                : "tc.status <> 'disabled'";
        Integer count = jdbc.queryForObject("""
                select count(*)
                from sys_user_role ur
                join sys_role r on r.code = ur.role_code and r.enabled = true
                join role_tool_permission rtp on rtp.role_code = r.code and rtp.allowed = true
                join tool_config tc on tc.tool_key = rtp.tool_key
                where ur.user_id = ?
                  and rtp.tool_key = ?
                  and %s
                """.formatted(availabilityPredicate), Integer.class, userId, toolKey);
        return count != null && count > 0;
    }

    public boolean isEnabled(String toolKey) {
        String predicate = columnExists("tool_config", "availability_status")
                ? "status='normal' and coalesce(availability_status, 'ONLINE')='ONLINE'"
                : "status='normal'";
        Integer count = jdbc.queryForObject(
                "select count(*) from tool_config where tool_key=? and " + predicate, Integer.class, toolKey);
        return count != null && count == 1;
    }

    public List<String> listRoleToolKeys(String roleCode) {
        return jdbc.query("""
                select tool_key
                from role_tool_permission
                where role_code=? and allowed=true
                order by tool_key
                """, (rs, rowNum) -> rs.getString("tool_key"), roleCode);
    }

    public void replaceRoleToolPermissions(String roleCode, List<String> toolKeys) {
        validateToolKeys(toolKeys);
        jdbc.update("delete from role_tool_permission where role_code=?", roleCode);
        if (toolKeys == null) {
            return;
        }
        var now = Instant.now().toString();
        for (var toolKey : toolKeys.stream().distinct().toList()) {
            jdbc.update("""
                    insert into role_tool_permission (role_code, tool_key, allowed, created_at, updated_at)
                    values (?, ?, ?, ?, ?)
                    """, roleCode, toolKey, true, now, now);
        }
    }

    public void validateToolKeys(List<String> toolKeys) {
        if (toolKeys == null) {
            return;
        }
        for (var toolKey : toolKeys.stream().distinct().toList()) {
            if (!exists("select count(*) from tool_config where tool_key=?", toolKey)) {
                throw new IllegalArgumentException("工具不存在: " + toolKey);
            }
        }
    }

    private record ToolSeed(String key, String name, String description, String categoryKey, String legacyStatus, String theme, boolean callbackEnabled) {}
}
