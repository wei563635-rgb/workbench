from pathlib import Path
import json

from app.services.field_mapper import detect_fields
from app.services.renderer import render_dashboard


def test_field_detection():
    aliases = {
        "question": ["问题", "消费者问题"],
        "answer": ["回答", "他人回答"],
    }
    result = detect_fields(["消费者问题", "他人回答"], aliases)
    assert result["suggestedMapping"]["question"] == "消费者问题"
    assert result["suggestedMapping"]["answer"] == "他人回答"


def test_template_injection(tmp_path: Path):
    template = tmp_path / "template.html"
    output = tmp_path / "output.html"
    template.write_text(
        '<script id="dashboard-data" type="application/json">{"old":true}</script>',
        encoding="utf-8",
    )
    data = {"meta": {"sampleData": False}, "details": [{"question": "Q", "answer": "A"}]}
    render_dashboard(template, data, output)
    content = output.read_text(encoding="utf-8")
    assert '"question": "Q"' in content
