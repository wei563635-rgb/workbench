from __future__ import annotations

import json
import shutil
import traceback
import uuid
from pathlib import Path
from typing import Any

from fastapi import BackgroundTasks, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles

from app import config
from app.models import FieldConfirmRequest, GenerateRequest
from app.services.field_mapper import detect_fields, load_aliases
from app.services.file_parser import clean_records, inspect_file, read_table
from app.services.llm_client import analyze, quality_check
from app.services.platform_client import (
    PlatformError,
    finish_run,
    post_event,
    register_file,
    start_run,
)
from app.services.renderer import render_dashboard
from app.services.storage import read_json, update_json, write_json

app = FastAPI(title="问大家消费者洞察看板生成工具", version="1.0.0")
app.mount("/static", StaticFiles(directory=config.ROOT / "app" / "static"), name="static")


def _find_upload(file_id: str) -> Path:
    matches = list(config.UPLOAD_DIR.glob(f"{file_id}__*"))
    if not matches:
        raise HTTPException(status_code=404, detail="上传文件不存在。")
    return matches[0]


def _bearer_token(authorization: str | None) -> str:
    if not authorization:
        return ""
    return authorization.removeprefix("Bearer ").strip()


def _best_effort_event(run_id: str, message: str, progress: int, platform_token: str) -> None:
    if not run_id or not platform_token:
        return
    try:
        post_event(run_id, event_type="progress", message=message, progress=progress, token=platform_token)
    except PlatformError:
        pass


@app.get("/", response_class=HTMLResponse)
def index() -> str:
    return (config.ROOT / "app" / "static" / "index.html").read_text(encoding="utf-8")


@app.get("/tool-manifest.json")
def tool_manifest() -> FileResponse:
    path = config.ROOT / "tool-manifest.json"
    if not path.exists():
        raise HTTPException(status_code=404, detail="tool-manifest.json 不存在。")
    return FileResponse(path, media_type="application/json")


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/files/upload")
async def upload_file(
    file: UploadFile = File(...),
    runId: str = Form(""),
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in {".xlsx", ".xls", ".csv"}:
        raise HTTPException(status_code=400, detail="仅支持 .xlsx、.xls、.csv。")

    content = await file.read()
    if len(content) > config.MAX_UPLOAD_MB * 1024 * 1024:
        raise HTTPException(status_code=400, detail="文件超过大小限制。")

    file_id = uuid.uuid4().hex
    safe_name = Path(file.filename or f"upload{suffix}").name
    target = config.UPLOAD_DIR / f"{file_id}__{safe_name}"
    target.write_bytes(content)

    try:
        inspection = inspect_file(target)
    except Exception as exc:
        target.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    platform_token = _bearer_token(authorization)
    if runId and platform_token:
        try:
            register_file(
                runId,
                file_name=safe_name,
                content_type=file.content_type or "application/octet-stream",
                size=len(content),
                token=platform_token,
            )
        except PlatformError as exc:
            raise HTTPException(status_code=502, detail=str(exc)) from exc

    return {
        "fileId": file_id,
        "filename": safe_name,
        **inspection,
    }


@app.post("/api/fields/detect")
def fields_detect(payload: dict[str, Any]) -> dict[str, Any]:
    file_id = str(payload.get("fileId", ""))
    sheet_name = payload.get("sheetName")
    path = _find_upload(file_id)
    inspection = inspect_file(path)
    sheet_key = sheet_name or next(iter(inspection["sheets"]))
    columns = inspection["sheets"].get(sheet_key)
    if columns is None:
        raise HTTPException(status_code=400, detail="Sheet 不存在。")
    aliases = load_aliases(config.ALIASES_PATH)
    return detect_fields(columns, aliases)


@app.post("/api/fields/confirm")
def fields_confirm(request: FieldConfirmRequest) -> dict[str, Any]:
    _find_upload(request.fileId)
    for required in ("question", "answer"):
        if not request.fieldMapping.get(required):
            raise HTTPException(status_code=400, detail=f"缺少必填字段映射：{required}")

    mapping_id = uuid.uuid4().hex
    data = {
        "mappingId": mapping_id,
        "fileId": request.fileId,
        "sheetName": request.sheetName,
        "fieldMapping": request.fieldMapping,
    }
    write_json(config.MAPPING_DIR / f"{mapping_id}.json", data)
    return {"mappingId": mapping_id, "validation": {"isValid": True}}


def _run_generation(task_id: str, request: GenerateRequest, platform_token: str) -> None:
    task_path = config.TASK_DIR / f"{task_id}.json"
    run_id = request.runId
    user_id = request.userId or config.PLATFORM_DEFAULT_USER_ID
    try:
        update_json(task_path, status="running", progress=10, message="读取并清洗数据")
        _best_effort_event(run_id, "读取并清洗数据", 10, platform_token)
        upload_path = _find_upload(request.fileId)
        mapping_path = config.MAPPING_DIR / f"{request.mappingId}.json"
        if not mapping_path.exists():
            raise RuntimeError("字段映射不存在。")
        mapping_data = read_json(mapping_path)

        frame = read_table(upload_path, mapping_data.get("sheetName"))
        records = clean_records(frame, mapping_data["fieldMapping"])

        update_json(task_path, progress=30, message="调用大模型分析")
        _best_effort_event(run_id, "调用大模型分析", 30, platform_token)
        schema = json.loads(config.SCHEMA_PATH.read_text(encoding="utf-8"))
        core_prompt = config.CORE_PROMPT_PATH.read_text(encoding="utf-8")
        candidate = analyze(
            records,
            project_name="问大家消费者洞察与推广图测图策略看板",
            product_name=request.productName or upload_path.stem,
            category=request.category,
            field_mapping=mapping_data["fieldMapping"],
            schema=schema,
            core_prompt_template=core_prompt,
            run_id=run_id,
            user_id=user_id,
            platform_token=platform_token,
        )

        update_json(task_path, progress=65, message="执行业务质量质检")
        _best_effort_event(run_id, "执行业务质量质检", 65, platform_token)
        quality_prompt = config.QUALITY_PROMPT_PATH.read_text(encoding="utf-8")
        source_summary = json.dumps(
            {
                "totalRows": len(records),
                "firstQuestions": [item["question"] for item in records[:20]],
            },
            ensure_ascii=False,
        )
        candidate, quality_report = quality_check(
            candidate,
            schema=schema,
            quality_prompt_template=quality_prompt,
            source_summary=source_summary,
            run_id=run_id,
            user_id=user_id,
            platform_token=platform_token,
        )

        dashboard_id = uuid.uuid4().hex
        dashboard_dir = config.DASHBOARD_DIR / dashboard_id
        dashboard_dir.mkdir(parents=True, exist_ok=True)
        write_json(dashboard_dir / "dashboard.json", candidate)
        write_json(dashboard_dir / "quality_report.json", quality_report)

        update_json(task_path, progress=85, message="生成 HTML 看板")
        _best_effort_event(run_id, "生成 HTML 看板", 85, platform_token)
        render_dashboard(
            config.TEMPLATE_PATH,
            candidate,
            dashboard_dir / "dashboard.html",
        )

        update_json(
            task_path,
            status="success",
            progress=100,
            message="生成完成",
            dashboardId=dashboard_id,
            qualityScore=quality_report.get("score"),
        )
        if run_id and platform_token:
            finish_run(
                run_id,
                status="success",
                output_summary=f"消费者洞察看板生成完成，质量分：{quality_report.get('score')}",
                output_data={
                    "dashboardId": dashboard_id,
                    "qualityScore": quality_report.get("score"),
                    "totalRows": len(records),
                },
                token=platform_token,
            )
    except Exception as exc:
        update_json(
            task_path,
            status="failed",
            progress=100,
            message=str(exc),
            traceback=traceback.format_exc(),
        )
        if run_id and platform_token:
            try:
                finish_run(
                    run_id,
                    status="failed",
                    output_summary=str(exc),
                    output_data={"errorMessage": str(exc), "traceback": traceback.format_exc()},
                    token=platform_token,
                )
            except PlatformError:
                pass


@app.post("/api/dashboards/generate")
def generate_dashboard(
    request: GenerateRequest,
    background_tasks: BackgroundTasks,
    authorization: str | None = Header(default=None),
) -> dict[str, str]:
    _find_upload(request.fileId)
    platform_token = _bearer_token(authorization)
    if not request.runId:
        if not platform_token:
            raise HTTPException(status_code=401, detail="缺少平台登录凭证，无法创建工具运行记录。")
        if not request.projectId:
            raise HTTPException(status_code=400, detail="使用工具前必须选择项目。")
        try:
            started = start_run(
                user_id=request.userId or config.PLATFORM_DEFAULT_USER_ID,
                project_id=request.projectId,
                input_summary=f"生成消费者洞察看板：{request.productName or request.fileId}",
                token=platform_token,
            )
            request.runId = str(started.get("runId", ""))
        except PlatformError as exc:
            raise HTTPException(status_code=502, detail=str(exc)) from exc
    if not platform_token:
        raise HTTPException(status_code=401, detail="缺少平台登录凭证，无法调用统一 AI 网关。")
    task_id = uuid.uuid4().hex
    write_json(
        config.TASK_DIR / f"{task_id}.json",
        {
            "taskId": task_id,
            "status": "pending",
            "progress": 0,
            "message": "等待执行",
            "runId": request.runId,
        },
    )
    background_tasks.add_task(_run_generation, task_id, request, platform_token)
    return {"taskId": task_id, "runId": request.runId}


@app.get("/api/tasks/{task_id}")
def get_task(task_id: str) -> dict[str, Any]:
    path = config.TASK_DIR / f"{task_id}.json"
    if not path.exists():
        raise HTTPException(status_code=404, detail="任务不存在。")
    return read_json(path)


@app.get("/api/dashboards/{dashboard_id}/json")
def dashboard_json(dashboard_id: str) -> dict[str, Any]:
    path = config.DASHBOARD_DIR / dashboard_id / "dashboard.json"
    if not path.exists():
        raise HTTPException(status_code=404, detail="看板不存在。")
    return read_json(path)


@app.get("/api/dashboards/{dashboard_id}/html")
def dashboard_html(dashboard_id: str) -> FileResponse:
    path = config.DASHBOARD_DIR / dashboard_id / "dashboard.html"
    if not path.exists():
        raise HTTPException(status_code=404, detail="看板不存在。")
    return FileResponse(path, media_type="text/html")


@app.get("/api/dashboards/{dashboard_id}/download")
def dashboard_download(dashboard_id: str) -> FileResponse:
    path = config.DASHBOARD_DIR / dashboard_id / "dashboard.html"
    if not path.exists():
        raise HTTPException(status_code=404, detail="看板不存在。")
    return FileResponse(
        path,
        media_type="text/html",
        filename=f"问大家消费者洞察看板_{dashboard_id[:8]}.html",
    )
