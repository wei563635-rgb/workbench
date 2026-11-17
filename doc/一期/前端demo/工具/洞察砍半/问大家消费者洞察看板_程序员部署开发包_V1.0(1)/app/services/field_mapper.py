from __future__ import annotations

import json
import re
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any


def normalize(value: str) -> str:
    return re.sub(r"[\s_\-（）()【】\[\]：:]+", "", value).lower()


def load_aliases(path: Path) -> dict[str, list[str]]:
    return json.loads(path.read_text(encoding="utf-8"))


def detect_fields(
    columns: list[str],
    aliases: dict[str, list[str]],
) -> dict[str, Any]:
    result: dict[str, str] = {}
    confidence: dict[str, float] = {}

    normalized_columns = {column: normalize(column) for column in columns}

    for standard, names in aliases.items():
        best_column = ""
        best_score = 0.0
        for column, normalized_column in normalized_columns.items():
            for alias in [standard, *names]:
                normalized_alias = normalize(alias)
                if normalized_column == normalized_alias:
                    score = 1.0
                elif normalized_alias in normalized_column or normalized_column in normalized_alias:
                    score = 0.88
                else:
                    score = SequenceMatcher(
                        None, normalized_column, normalized_alias
                    ).ratio()
                if score > best_score:
                    best_score = score
                    best_column = column
        if best_score >= 0.62:
            result[standard] = best_column
            confidence[standard] = round(best_score, 3)

    missing = [field for field in ("question", "answer") if field not in result]
    return {
        "suggestedMapping": result,
        "confidence": confidence,
        "missingRequiredFields": missing,
    }
