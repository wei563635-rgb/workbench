#!/usr/bin/env python3
import json
import sys
import urllib.error
import urllib.request
import uuid


BASE_URL = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:18080"
ACCESS_TOKEN = None


def request(method: str, path: str, payload=None):
    headers = {"Content-Type": "application/json"}
    if ACCESS_TOKEN:
        headers["Authorization"] = f"Bearer {ACCESS_TOKEN}"
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(
        BASE_URL + path,
        data=body,
        method=method,
        headers=headers,
    )
    try:
        with urllib.request.urlopen(req, timeout=8) as response:
            text = response.read().decode()
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        try:
            payload = json.loads(text)
        except Exception:
            payload = text
        return error.code, payload


def assert_true(name: str, condition: bool, detail=""):
    if not condition:
        raise AssertionError(f"{name} failed {detail}".strip())
    print(f"PASS {name}")


def login_and_change_password(account: str, temporary_password: str, new_password: str):
    global ACCESS_TOKEN
    status, login = request("POST", "/api/auth/login", {
        "account": account,
        "password": temporary_password,
    })
    assert_true(f"login {account}", status == 200 and login.get("accessToken"), login)
    ACCESS_TOKEN = login["accessToken"]
    status, changed = request("POST", "/api/auth/change-password", {
        "oldPassword": temporary_password,
        "newPassword": new_password,
    })
    assert_true(f"change password {account}", status == 200 and changed.get("changed") is True, changed)
    return ACCESS_TOKEN


def main():
    global ACCESS_TOKEN
    status, unauthorized = request("GET", "/api/admin/ai-call-logs")
    assert_true("admin auth required", status == 401, unauthorized)

    status, login = request("POST", "/api/auth/login", {"account": "admin", "password": "admin123"})
    assert_true("login", status == 200 and login.get("accessToken"), login)
    ACCESS_TOKEN = login["accessToken"]

    tmp_account = "tmp_user_" + uuid.uuid4().hex[:8]
    status, created = request("POST", "/api/admin/users", {
        "username": tmp_account,
        "displayName": "临时用户",
        "roleIds": ["member"],
    })
    assert_true("create user temp password once", status == 200 and created.get("temporaryPassword"), created)

    status, temp_login = request("POST", "/api/auth/login", {
        "account": tmp_account,
        "password": created["temporaryPassword"],
    })
    assert_true("must change password", status == 200 and temp_login.get("mustChangePassword") is True, temp_login)
    admin_token = ACCESS_TOKEN
    ACCESS_TOKEN = temp_login["accessToken"]
    status, blocked = request("GET", "/api/bootstrap")
    assert_true("password change blocks business api", status == 403 and blocked.get("code") == "PASSWORD_CHANGE_REQUIRED", blocked)
    status, changed = request("POST", "/api/auth/change-password", {
        "oldPassword": created["temporaryPassword"],
        "newPassword": "Member1234",
    })
    assert_true("change temp password", status == 200 and changed.get("changed") is True, changed)
    member_token = ACCESS_TOKEN
    status, forbidden_admin = request("GET", "/api/admin/ai-call-logs")
    assert_true("member cannot access admin", status == 403 and forbidden_admin.get("code") == "FORBIDDEN", forbidden_admin)
    status, member_bootstrap = request("GET", "/api/bootstrap")
    assert_true("member bootstrap hides admin data", status == 200
                and "users" not in member_bootstrap
                and "aiLogs" not in member_bootstrap
                and "modelConfigs" not in member_bootstrap
                and "operationLogs" not in member_bootstrap
                and "deploymentRequests" not in member_bootstrap,
                member_bootstrap)
    ACCESS_TOKEN = admin_token

    limited_role = "tmp_no_tool_" + uuid.uuid4().hex[:8]
    status, role = request("POST", "/api/admin/roles", {
        "code": limited_role,
        "name": "无工具权限角色",
        "description": "回归验证角色",
        "dataScope": "SELF",
        "permissionCodes": ["workbench:access", "project:create", "project:read", "project:write"],
        "toolKeys": [],
    })
    assert_true("create limited role", status == 200 and role.get("code") == limited_role, role)

    switch_account = "tmp_switch_" + uuid.uuid4().hex[:8]
    status, switch_user = request("POST", "/api/admin/users", {
        "username": switch_account,
        "displayName": "角色切换用户",
        "roleIds": ["member"],
    })
    assert_true("create role-switch user", status == 200 and switch_user.get("temporaryPassword"), switch_user)
    switch_token = login_and_change_password(switch_account, switch_user["temporaryPassword"], "Switch1234")
    status, switch_tools_before = request("GET", "/api/tools")
    assert_true("role switch user initially has member tools", status == 200
                and any(item.get("key") == "market-intelligence" for item in switch_tools_before),
                switch_tools_before)
    ACCESS_TOKEN = admin_token
    status, switched_user = request("PUT", f"/api/admin/users/{switch_user['id']}/roles", {
        "roleIds": [limited_role],
    })
    assert_true("update user roles", status == 200 and switched_user.get("roleIds") == [limited_role], switched_user)
    ACCESS_TOKEN = switch_token
    status, switch_tools_after = request("GET", "/api/tools")
    assert_true("updated user roles take effect immediately", status == 200
                and all(item.get("key") != "market-intelligence" for item in switch_tools_after),
                switch_tools_after)
    ACCESS_TOKEN = admin_token

    disabled_role = "tmp_disable_" + uuid.uuid4().hex[:8]
    status, role = request("POST", "/api/admin/roles", {
        "code": disabled_role,
        "name": "可禁用角色",
        "description": "回归验证角色",
        "dataScope": "SELF",
        "permissionCodes": ["workbench:access"],
        "toolKeys": ["market-intelligence"],
    })
    assert_true("create disable-test role", status == 200 and role.get("code") == disabled_role, role)

    disabled_account = "tmp_disabled_" + uuid.uuid4().hex[:8]
    status, disabled_user = request("POST", "/api/admin/users", {
        "username": disabled_account,
        "displayName": "禁用角色用户",
        "roleIds": [disabled_role],
    })
    assert_true("create disable-test user", status == 200 and disabled_user.get("temporaryPassword"), disabled_user)
    disabled_token = login_and_change_password(disabled_account, disabled_user["temporaryPassword"], "Disable1234")
    status, allowed_bootstrap = request("GET", "/api/bootstrap")
    assert_true("role permission works before disable", status == 200 and "departments" in allowed_bootstrap, allowed_bootstrap)
    ACCESS_TOKEN = admin_token
    status, disabled_status = request("POST", f"/api/admin/roles/{disabled_role}/status", {"enabled": False})
    assert_true("disable role", status == 200 and disabled_status.get("enabled") is False, disabled_status)
    ACCESS_TOKEN = disabled_token
    status, disabled_forbidden = request("GET", "/api/bootstrap")
    assert_true("disabled role takes effect", status == 403 and disabled_forbidden.get("code") == "FORBIDDEN", disabled_forbidden)
    ACCESS_TOKEN = admin_token

    status, bootstrap = request("GET", "/api/bootstrap")
    assert_true("bootstrap", status == 200
                and "departments" in bootstrap
                and "organizationDepartments" in bootstrap
                and "deploymentRequests" in bootstrap,
                bootstrap)

    status, org_created = request("POST", "/api/admin/organization-departments", {
        "name": "回归组织部门",
        "parentId": None,
    })
    assert_true("create organization department", status == 200
                and org_created.get("id")
                and org_created.get("name") == "回归组织部门"
                and org_created.get("enabled") is True,
                org_created)
    status, org_updated = request("PUT", f"/api/admin/organization-departments/{org_created['id']}", {
        "name": "回归组织部门-更新",
        "parentId": None,
    })
    assert_true("update organization department", status == 200 and org_updated.get("name") == "回归组织部门-更新", org_updated)
    status, org_disabled = request("POST", f"/api/admin/organization-departments/{org_created['id']}/status", {
        "enabled": False,
    })
    assert_true("disable organization department", status == 200 and org_disabled.get("enabled") is False, org_disabled)

    status, project = request("POST", "/api/projects", {
        "name": "回归验证项目",
        "owner": "张三",
        "members": ["张三", "李四"],
        "summary": "自动回归创建",
    })
    assert_true("create project", status == 200 and project.get("id"), project)
    project_id = project["id"]

    status, tasks = request("GET", f"/api/projects/{project_id}/tasks")
    assert_true("default project tasks", status == 200
                and len(tasks) >= 6
                and any(task.get("toolKey") == "market-intelligence" and task.get("status") == "TODO" for task in tasks),
                tasks)

    status, custom_task = request("POST", f"/api/projects/{project_id}/tasks", {
        "title": "回归自定义任务",
        "toolKey": "project-dashboard",
        "assigneeUserId": "u_admin",
    })
    assert_true("create project task", status == 200 and custom_task.get("title") == "回归自定义任务", custom_task)

    status, updated_task = request("POST", f"/api/projects/{project_id}/tasks/{custom_task['id']}/status", {
        "status": "IN_PROGRESS",
    })
    assert_true("update project task status", status == 200 and updated_task.get("status") == "IN_PROGRESS", updated_task)

    ACCESS_TOKEN = member_token
    status, denied_project_run = request("POST", "/api/tool-runs/start", {
        "toolKey": "market-intelligence",
        "userId": "member",
        "projectId": project_id,
        "inputSummary": "无项目权限验证",
    })
    assert_true("project access required for start run", status == 403 and denied_project_run.get("code") == "FORBIDDEN", denied_project_run)
    ACCESS_TOKEN = admin_token

    limited_account = "tmp_limited_" + uuid.uuid4().hex[:8]
    status, limited_user = request("POST", "/api/admin/users", {
        "username": limited_account,
        "displayName": "无工具权限用户",
        "roleIds": [limited_role],
    })
    assert_true("create limited user", status == 200 and limited_user.get("temporaryPassword"), limited_user)
    limited_token = login_and_change_password(limited_account, limited_user["temporaryPassword"], "Limited1234")
    status, limited_tools = request("GET", "/api/tools")
    assert_true("tool list filters by permission", status == 200
                and all(item.get("key") != "market-intelligence" for item in limited_tools),
                limited_tools)
    status, denied_run = request("POST", "/api/tool-runs/start", {
        "toolKey": "market-intelligence",
        "userId": "u_limited",
        "projectId": project_id,
        "inputSummary": "无工具权限验证",
    })
    assert_true("tool permission required", status == 403 and denied_run.get("code") == "FORBIDDEN", denied_run)
    ACCESS_TOKEN = admin_token

    status, run = request("POST", "/api/tool-runs/start", {
        "toolKey": "market-intelligence",
        "userId": "spoofed_user",
        "projectId": project_id,
        "inputSummary": "回归验证输入",
    })
    assert_true("start run", status == 200 and run.get("runId") and run.get("userId") != "spoofed_user", run)

    ACCESS_TOKEN = limited_token
    status, denied_finish = request("POST", f"/api/tool-runs/{run['runId']}/finish", {
        "status": "success",
        "outputSummary": "越权完成",
        "outputData": {"source": "regression"},
    })
    assert_true("tool permission required for finish", status == 403 and denied_finish.get("code") == "FORBIDDEN", denied_finish)
    status, denied_ai = request("POST", "/api/ai/chat/completions", {
        "runId": run["runId"],
        "toolKey": "market-intelligence",
        "userId": "u_limited",
        "model": "gpt-4.1",
        "params": {"messages": [{"role": "user", "content": "越权验证"}]},
    })
    assert_true("tool permission required for ai gateway", status == 403 and denied_ai.get("code") == "FORBIDDEN", denied_ai)
    ACCESS_TOKEN = admin_token

    status, mismatched_ai = request("POST", "/api/ai/images/generations", {
        "runId": run["runId"],
        "toolKey": "promo-image",
        "userId": "u_001",
        "model": "gemini-3.1-flash-lite-image",
        "params": {"prompt": "tool mismatch", "size": "auto", "quality": "1K"},
    })
    assert_true("ai gateway requires run tool key", status == 403 and mismatched_ai.get("code") == "FORBIDDEN", mismatched_ai)

    status, finished = request("POST", f"/api/tool-runs/{run['runId']}/finish", {
        "status": "success",
        "outputSummary": "回归验证输出",
        "outputData": {"source": "regression"},
    })
    assert_true("finish run", status == 200 and finished.get("status") == "success", finished)

    status, done_tasks = request("GET", f"/api/projects/{project_id}/tasks")
    assert_true("finish updates matching project task", status == 200
                and any(task.get("toolKey") == "market-intelligence"
                        and task.get("status") == "DONE"
                        and task.get("runId") == run["runId"] for task in done_tasks),
                done_tasks)

    status, ai = request("POST", "/api/ai/chat/completions", {
        "runId": run["runId"],
        "toolKey": "market-intelligence",
        "userId": "u_001",
        "model": "gpt-4.1",
        "params": {"messages": [{"role": "user", "content": "回归验证"}]},
    })
    assert_true("ai gateway", status == 200 and ai.get("usage", {}).get("totalTokens") == 384, ai)

    status, logs = request("GET", "/api/admin/ai-call-logs")
    assert_true("ai logs", status == 200 and any(item.get("id") == ai.get("id") for item in logs), logs[:1])

    status, image = request("POST", "/api/ai/images/generations", {
        "runId": run["runId"],
        "toolKey": "market-intelligence",
        "userId": "u_001",
        "model": "gemini-3.1-flash-lite-image",
        "params": {"prompt": "A cat playing on the grass", "size": "auto", "quality": "1K"},
    })
    assert_true("evolink image task", status == 200 and image.get("id") and image.get("platformLogId"), image)

    status, task = request("GET", f"/api/ai/tasks/{image['id']}?modelName=gemini-3.1-flash-lite-image")
    assert_true("evolink task detail", status == 200 and task.get("status") in ("pending", "processing", "completed", "failed"), task)

    status, deployment = request("POST", "/api/admin/deployments/request", {
        "toolKey": "regression-tool",
        "name": "回归验证工具",
        "departmentKey": "ai-market",
        "entry": "/tools/regression/index.html",
        "integrationType": "static",
        "status": "normal",
        "callbackEnabled": True,
        "aiGatewayRequired": True,
        "requiredApis": ["/api/tool-runs/start", "/api/tool-runs/{runId}/finish", "/api/ai/chat/completions"],
        "directModelEndpoints": [],
        "version": "0.1.0",
        "owner": "regression",
    })
    assert_true("deployment gate approved", status == 200 and deployment.get("status") == "approved", deployment)

    status, rejected = request("POST", "/api/admin/deployments/request", {
        "toolKey": "bad-regression-tool",
        "name": "违规回归工具",
        "departmentKey": "ai-market",
        "entry": "https://example.com",
        "integrationType": "external",
        "status": "normal",
        "callbackEnabled": False,
        "aiGatewayRequired": False,
        "requiredApis": [],
        "directModelEndpoints": ["https://api.openai.com/v1/chat/completions"],
        "version": "0.1.0",
        "owner": "regression",
    })
    assert_true("deployment gate rejected", status == 200 and rejected.get("status") == "rejected", rejected)

    status, link = request("POST", "/api/project-links", {
        "projectId": project_id,
        "runId": run["runId"],
        "category": "回归验证",
        "remark": "自动回归",
    })
    assert_true("project link", status == 200 and link.get("projectId") == project_id, link)

    ACCESS_TOKEN = member_token
    status, denied_remove = request("DELETE", f"/api/project-links/{link['id']}")
    assert_true("project result remove requires permission", status == 403 and denied_remove.get("code") == "FORBIDDEN", denied_remove)
    ACCESS_TOKEN = admin_token

    print("All regression checks passed.")


if __name__ == "__main__":
    main()
