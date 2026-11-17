from __future__ import annotations

from pathlib import Path
from typing import Any

import chardet
import pandas as pd


SUPPORTED = {".xlsx", ".xls", ".csv"}


class FileParseError(ValueError):
    pass


def _read_csv(path: Path, nrows: int | None = None) -> pd.DataFrame:
    raw = path.read_bytes()[:100000]
    detected = chardet.detect(raw).get("encoding") or "utf-8"
    candidates = [detected, "utf-8-sig", "utf-8", "gbk"]
    last_error: Exception | None = None
    for encoding in dict.fromkeys(candidates):
        try:
            return pd.read_csv(path, encoding=encoding, nrows=nrows)
        except Exception as exc:
            last_error = exc
    raise FileParseError(f"CSV 解析失败：{last_error}")


def inspect_file(path: Path) -> dict[str, Any]:
    suffix = path.suffix.lower()
    if suffix not in SUPPORTED:
        raise FileParseError(f"不支持的文件格式：{suffix}")

    if suffix in {".xlsx", ".xls"}:
        excel = pd.ExcelFile(path)
        sheets: dict[str, list[str]] = {}
        previews: dict[str, list[dict[str, Any]]] = {}
        for sheet in excel.sheet_names:
            frame = pd.read_excel(path, sheet_name=sheet, nrows=5)
            frame.columns = [str(c).strip() for c in frame.columns]
            sheets[sheet] = list(frame.columns)
            previews[sheet] = frame.fillna("").astype(str).to_dict(orient="records")
        return {"sheets": sheets, "previews": previews}

    frame = _read_csv(path, nrows=5)
    frame.columns = [str(c).strip() for c in frame.columns]
    return {
        "sheets": {"CSV": list(frame.columns)},
        "previews": {"CSV": frame.fillna("").astype(str).to_dict(orient="records")},
    }


def read_table(path: Path, sheet_name: str | None = None) -> pd.DataFrame:
    suffix = path.suffix.lower()
    if suffix in {".xlsx", ".xls"}:
        frame = pd.read_excel(path, sheet_name=sheet_name or 0)
    elif suffix == ".csv":
        frame = _read_csv(path)
    else:
        raise FileParseError(f"不支持的文件格式：{suffix}")
    frame.columns = [str(c).strip() for c in frame.columns]
    return frame


def clean_records(
    frame: pd.DataFrame,
    mapping: dict[str, str],
) -> list[dict[str, Any]]:
    required = {"question", "answer"}
    missing = [key for key in required if not mapping.get(key)]
    if missing:
        raise FileParseError(f"缺少必填字段映射：{', '.join(missing)}")

    for standard, source in mapping.items():
        if source and source not in frame.columns:
            raise FileParseError(f"字段不存在：{source}（映射到 {standard}）")

    records: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()

    for index, row in frame.iterrows():
        question = str(row.get(mapping["question"], "") or "").strip()
        answer = str(row.get(mapping["answer"], "") or "").strip()

        if question.lower() == "nan":
            question = ""
        if answer.lower() == "nan":
            answer = ""
        if not question and not answer:
            continue

        duplicate_key = (question, answer)
        if duplicate_key in seen:
            continue
        seen.add(duplicate_key)

        item: dict[str, Any] = {
            "id": len(records) + 1,
            "question": question,
            "answer": answer,
        }
        for optional in (
            "time", "productName", "sku", "platform", "likes", "answerCount"
        ):
            source = mapping.get(optional)
            if source:
                value = row.get(source, "")
                if pd.isna(value):
                    value = ""
                item[optional] = str(value).strip()
        records.append(item)

    if not records:
        raise FileParseError("清洗后没有有效问答数据。")
    return records
