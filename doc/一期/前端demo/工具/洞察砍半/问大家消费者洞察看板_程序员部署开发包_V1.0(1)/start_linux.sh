#!/usr/bin/env bash
set -e
if [ ! -f .env ]; then
  cp .env.example .env
  echo "已创建 .env，请先填写 OPENAI_API_KEY 后重新运行。"
  exit 1
fi
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
