from __future__ import annotations

from typing import Any
from pydantic import BaseModel, Field


class FieldConfirmRequest(BaseModel):
    fileId: str
    sheetName: str | None = None
    fieldMapping: dict[str, str]


class GenerateRequest(BaseModel):
    fileId: str
    mappingId: str
    productName: str = ""
    category: str = ""
    runId: str = ""
    userId: str = ""
    projectId: str = ""
    options: dict[str, Any] = Field(default_factory=dict)
