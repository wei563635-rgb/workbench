from __future__ import annotations

import html
import json
import re
from pathlib import Path
from typing import Any


DATA_PATTERN = re.compile(
    r'(<script\s+id=["\']dashboard-data["\']\s+type=["\']application/json["\']\s*>)(.*?)(</script>)',
    re.IGNORECASE | re.DOTALL,
)


class RenderError(RuntimeError):
    pass


def render_dashboard(
    template_path: Path,
    data: dict[str, Any],
    output_path: Path,
) -> None:
    template = template_path.read_text(encoding="utf-8")
    payload = json.dumps(data, ensure_ascii=False, indent=2).replace("</script>", "<\\/script>")

    if not DATA_PATTERN.search(template):
        raise RenderError("HTML 母版中未找到 dashboard-data。")

    rendered = DATA_PATTERN.sub(
        lambda match: match.group(1) + "\n" + payload + "\n" + match.group(3),
        template,
        count=1,
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(rendered, encoding="utf-8")


def verify_embedded_html(path: Path) -> dict[str, Any]:
    content = path.read_text(encoding="utf-8")
    match = DATA_PATTERN.search(content)
    if not match:
        raise RenderError("未找到 dashboard-data。")
    try:
        data = json.loads(match.group(2))
    except json.JSONDecodeError as exc:
        raise RenderError(f"dashboard-data 不是合法 JSON：{exc}") from exc

    details = data.get("details") or []
    if not details:
        raise RenderError("dashboard-data.details 为空。")
    if data.get("meta", {}).get("sampleData") is True:
        raise RenderError("当前仍是样例数据，不允许作为正式结果导出。")

    return {
        "detailCount": len(details),
        "hasQuestionAnswer": all(
            isinstance(item, dict) and "question" in item and "answer" in item
            for item in details
        ),
    }
