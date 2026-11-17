from __future__ import annotations

from typing import Any

import httpx

from app import config


class PlatformError(RuntimeError):
    pass


def _headers(token: str = "") -> dict[str, str]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def _post(path: str, payload: dict[str, Any], token: str = "") -> dict[str, Any]:
    url = f"{config.PLATFORM_BASE_URL}{path}"
    try:
        response = httpx.post(url, json=payload, headers=_headers(token), timeout=120)
    except httpx.HTTPError as exc:
        raise PlatformError(f"平台接口调用失败：{exc}") from exc
    if response.status_code < 200 or response.status_code >= 300:
        raise PlatformError(f"平台接口返回异常 {response.status_code}: {response.text[:500]}")
    if not response.content:
        return {}
    return response.json()


def start_run(*, user_id: str, project_id: str, input_summary: str, token: str) -> dict[str, Any]:
    return _post(
        "/api/tool-runs/start",
        {
            "toolKey": config.PLATFORM_TOOL_KEY,
            "userId": user_id,
            "projectId": project_id,
            "inputSummary": input_summary,
        },
        token,
    )


def finish_run(run_id: str, *, status: str, output_summary: str, output_data: dict[str, Any], token: str) -> None:
    _post(
        f"/api/tool-runs/{run_id}/finish",
        {
            "status": status,
            "outputSummary": output_summary,
            "outputData": output_data,
        },
        token,
    )


def post_event(run_id: str, *, event_type: str, message: str, progress: int, token: str) -> None:
    _post(
        f"/api/tool-runs/{run_id}/events",
        {
            "eventType": event_type,
            "message": message,
            "progress": progress,
        },
        token,
    )


def register_file(run_id: str, *, file_name: str, content_type: str, size: int, token: str) -> None:
    _post(
        "/api/files/upload",
        {
            "runId": run_id,
            "fileName": file_name,
            "contentType": content_type,
            "size": size,
        },
        token,
    )


def chat_completion(
    *,
    run_id: str,
    user_id: str,
    model: str,
    params: dict[str, Any],
    token: str,
) -> dict[str, Any]:
    return _post(
        "/api/ai/chat/completions",
        {
            "runId": run_id,
            "toolKey": config.PLATFORM_TOOL_KEY,
            "userId": user_id,
            "model": model,
            "params": params,
        },
        token,
    )
