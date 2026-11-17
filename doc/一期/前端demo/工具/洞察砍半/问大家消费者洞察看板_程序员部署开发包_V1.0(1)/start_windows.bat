@echo off
chcp 65001 >nul
if not exist .env (
  copy .env.example .env >nul
  echo 已创建 .env，请先填写 OPENAI_API_KEY 后重新运行。
  pause
  exit /b 1
)
python -m venv .venv
call .venv\Scripts\activate
python -m pip install --upgrade pip
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
