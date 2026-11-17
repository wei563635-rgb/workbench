from __future__ import annotations

import json
from typing import Any

from jsonschema import Draft202012Validator

from app import config
from app.services.platform_client import chat_completion


class LLMError(RuntimeError):
    pass


def _render_prompt(template: str, replacements: dict[str, str]) -> str:
    rendered = template
    for key, value in replacements.items():
        rendered = rendered.replace("{{" + key + "}}", value)
    return rendered


def _call_json(
    system_prompt: str,
    user_prompt: str,
    *,
    run_id: str,
    user_id: str,
    platform_token: str,
    schema: dict[str, Any] | None = None,
) -> dict[str, Any]:
    if config.OPENAI_OUTPUT_MODE == "json_schema" and schema:
        response_format: dict[str, Any] = {
            "type": "json_schema",
            "json_schema": {
                "name": "qa_dashboard",
                "strict": False,
                "schema": schema,
            },
        }
    else:
        response_format = {"type": "json_object"}

    response = chat_completion(
        run_id=run_id,
        user_id=user_id,
        model=config.OPENAI_MODEL,
        token=platform_token,
        params={
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "response_format": response_format,
        },
    )

    try:
        message = response["choices"][0]["message"]
    except (KeyError, IndexError, TypeError) as exc:
        raise LLMError("平台 AI 网关返回结构不符合 chat completions 格式。") from exc

    refusal = message.get("refusal")
    if refusal:
        raise LLMError(f"模型拒绝处理：{refusal}")
    content = message.get("content")
    if not content:
        raise LLMError("模型没有返回内容。")

    try:
        return json.loads(content)
    except json.JSONDecodeError as exc:
        raise LLMError(f"模型返回的不是合法 JSON：{exc}") from exc


def validate_json(candidate: dict[str, Any], schema: dict[str, Any]) -> list[str]:
    validator = Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(candidate), key=lambda e: list(e.path))
    return [
        f"{'/'.join(str(v) for v in error.path) or '<root>'}: {error.message}"
        for error in errors
    ]


def analyze(
    details: list[dict[str, Any]],
    *,
    project_name: str,
    product_name: str,
    category: str,
    field_mapping: dict[str, str],
    schema: dict[str, Any],
    core_prompt_template: str,
    run_id: str,
    user_id: str,
    platform_token: str,
) -> dict[str, Any]:
    payload = json.dumps(details, ensure_ascii=False)
    if len(payload) > config.MAX_INPUT_CHARS:
        raise LLMError(
            "输入数据超过当前 MVP 的单次模型输入限制。"
            "请提高 MAX_INPUT_CHARS 或按 docs/05_部署与验收.md 实现分批分析。"
        )

    prompt = _render_prompt(
        core_prompt_template,
        {
            "project_name": project_name,
            "product_name": product_name,
            "category": category,
            "field_mapping": json.dumps(field_mapping, ensure_ascii=False, indent=2),
            "input_data": payload,
            "json_schema": json.dumps(schema, ensure_ascii=False),
        },
    )

    candidate = _call_json(
        "你必须严格执行用户提供的核心业务分析 Prompt，并只输出 JSON。",
        prompt,
        run_id=run_id,
        user_id=user_id,
        platform_token=platform_token,
        schema=schema,
    )

    errors = validate_json(candidate, schema)
    attempts = 0
    while errors and attempts < config.MAX_REPAIR_ATTEMPTS:
        repair_prompt = (
            "请修正以下 JSON，使其严格符合 JSON Schema。只输出修正后的完整 JSON。\n\n"
            f"Schema 错误：\n{json.dumps(errors, ensure_ascii=False, indent=2)}\n\n"
            f"JSON Schema：\n{json.dumps(schema, ensure_ascii=False)}\n\n"
            f"候选 JSON：\n{json.dumps(candidate, ensure_ascii=False)}"
        )
        candidate = _call_json(
            "你是 JSON Schema 修复器，只能输出完整合法 JSON。",
            repair_prompt,
            run_id=run_id,
            user_id=user_id,
            platform_token=platform_token,
            schema=schema,
        )
        errors = validate_json(candidate, schema)
        attempts += 1

    if errors:
        raise LLMError("JSON Schema 校验失败：" + "；".join(errors[:10]))
    return candidate


def quality_check(
    candidate: dict[str, Any],
    *,
    schema: dict[str, Any],
    quality_prompt_template: str,
    source_summary: str,
    run_id: str,
    user_id: str,
    platform_token: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    prompt = _render_prompt(
        quality_prompt_template,
        {
            "candidate_json": json.dumps(candidate, ensure_ascii=False),
            "json_schema": json.dumps(schema, ensure_ascii=False),
            "source_summary": source_summary,
        },
    )
    report = _call_json(
        "你是业务质量质检器，只输出规定的 JSON 质检报告。",
        prompt,
        run_id=run_id,
        user_id=user_id,
        platform_token=platform_token,
    )

    score = int(report.get("score", 0) or 0)
    corrected = report.get("correctedJson")
    if score < config.QUALITY_PASS_SCORE and isinstance(corrected, dict) and corrected:
        errors = validate_json(corrected, schema)
        if not errors:
            candidate = corrected

    return candidate, report
