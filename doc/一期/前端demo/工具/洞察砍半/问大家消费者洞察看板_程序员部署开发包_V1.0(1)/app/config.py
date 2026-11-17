from __future__ import annotations

import os
from pathlib import Path
from dotenv import load_dotenv

load_dotenv()

ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data"
UPLOAD_DIR = DATA_DIR / "uploads"
MAPPING_DIR = DATA_DIR / "mappings"
TASK_DIR = DATA_DIR / "tasks"
DASHBOARD_DIR = DATA_DIR / "dashboards"

for directory in (UPLOAD_DIR, MAPPING_DIR, TASK_DIR, DASHBOARD_DIR):
    directory.mkdir(parents=True, exist_ok=True)

PLATFORM_BASE_URL = os.getenv("PLATFORM_BASE_URL", "http://127.0.0.1:18080").rstrip("/")
PLATFORM_TOOL_KEY = os.getenv("PLATFORM_TOOL_KEY", "consumer-insight")
PLATFORM_DEFAULT_USER_ID = os.getenv("PLATFORM_DEFAULT_USER_ID", "u_admin")
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-5.1-chat")
OPENAI_OUTPUT_MODE = os.getenv("OPENAI_OUTPUT_MODE", "json_object")
MAX_UPLOAD_MB = int(os.getenv("MAX_UPLOAD_MB", "50"))
MAX_INPUT_CHARS = int(os.getenv("MAX_INPUT_CHARS", "800000"))
MAX_REPAIR_ATTEMPTS = int(os.getenv("MAX_REPAIR_ATTEMPTS", "1"))
QUALITY_PASS_SCORE = int(os.getenv("QUALITY_PASS_SCORE", "90"))

SCHEMA_PATH = ROOT / "schemas" / "dashboard.schema.json"
TEMPLATE_PATH = ROOT / "templates" / "v31_dashboard_template.html"
CORE_PROMPT_PATH = ROOT / "prompts" / "core_business_analysis.md"
QUALITY_PROMPT_PATH = ROOT / "prompts" / "json_quality_check.md"
ALIASES_PATH = ROOT / "config" / "field_aliases.json"
