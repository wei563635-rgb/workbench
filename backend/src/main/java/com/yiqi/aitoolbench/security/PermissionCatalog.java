package com.yiqi.aitoolbench.security;

import java.util.List;

public final class PermissionCatalog {
    private PermissionCatalog() {}

    public static final List<PermissionDefinition> PERMISSIONS = List.of(
            p("admin:relay-quota:read", "查看中转配额", "中转站", true),
            p("admin:relay-quota:write", "管理中转配额", "中转站", true),
            p("admin:relay-usage:read", "查看中转用量看板", "中转站", true),
            p("admin:relay-audit:read", "查看中转完整审计（限超级管理员）", "中转站", true),
            p("workbench:access", "工作台访问", "工作台权限", false),
            p("project:create", "创建项目", "项目权限", false),
            p("project:own", "担任项目负责人", "项目权限", false),
            p("project:read", "查看项目", "项目权限", false),
            p("project:write", "编辑项目", "项目权限", false),
            p("project:result:read", "查看项目结果", "项目权限", false),
            p("project:result:remove", "移除项目结果", "项目权限", true),
            p("project:result:restore", "恢复项目结果", "项目权限", true),
            p("project:result:download", "下载项目附件", "项目权限", true),
            p("run:self:read", "查看本人运行记录", "运行记录", false),
            p("run:department:read", "查看部门运行摘要", "运行记录", true),
            p("brand:page:read", "查看品牌 HTML 页面", "品牌页面", false),
            p("admin:brand-page:write", "管理品牌 HTML 页面", "品牌页面", true),
            p("admin:user:read", "查看用户", "用户与组织", true),
            p("admin:user:write", "管理用户", "用户与组织", true),
            p("admin:department:read", "查看组织部门", "用户与组织", false),
            p("admin:department:write", "管理组织部门", "用户与组织", true),
            p("admin:role:read", "查看角色权限", "角色与权限", true),
            p("admin:role:write", "管理角色权限", "角色与权限", true),
            p("admin:tool:read", "查看工具配置", "工具权限", false),
            p("admin:tool:write", "管理工具配置", "工具权限", true),
            p("admin:model:read", "查看模型配置", "模型与 AI 网关", true),
            p("admin:model:write", "管理模型配置", "模型与 AI 网关", true),
            p("admin:upstream-account:read", "查看上游账号", "上游账号池", true),
            p("admin:upstream-account:write", "管理上游账号", "上游账号池", true),
            p("admin:ai-log:read", "查看 AI 调用日志", "日志审计", true),
            p("admin:log:read", "查看操作日志", "日志审计", true),
            p("admin:deploy:read", "查看部署门禁", "部署管理", false),
            p("admin:deploy:approve", "处理部署门禁", "部署管理", true)
    );

    private static PermissionDefinition p(String code, String name, String module, boolean sensitive) {
        return new PermissionDefinition(code, name, module, sensitive);
    }

    public record PermissionDefinition(String code, String name, String module, boolean sensitive) {}
}
