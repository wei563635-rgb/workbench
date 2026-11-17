# 问大家消费者洞察看板｜程序员部署开发包 V1.0

## 1. 这个包是什么

这是一个可运行的 MVP 开发骨架，用于实现：

```text
上传 Excel / CSV 问大家数据
→ 自动识别“消费者问题”和“他人回答”
→ 人工确认字段映射
→ 调用大模型 API 生成 V31 标准 JSON
→ JSON Schema 校验
→ 业务质检与自动修正
→ 注入 V31 HTML 母版
→ 在线预览 / 下载单文件 HTML
```

本包包含：

- 可运行的 FastAPI 后端；
- 简单上传和生成页面；
- 字段识别与人工映射；
- OpenAI API 调用封装；
- 核心业务分析 Prompt；
- JSON 质检 Prompt；
- V31 JSON Schema；
- V31 参数化 HTML 母版；
- Docker 和本地启动脚本；
- 程序员交接文档与测试用例。

## 2. 第一版边界

第一版只做“上传数据 → 大模型分析 → 生成固定样式看板”。

不包含：

```text
黄金样例库
复杂权限
多人协作
模板管理后台
历史版本对比
投放结果回填
```

## 3. 快速启动

### Windows

1. 安装 Python 3.11 或以上版本。
2. 复制 `.env.example` 为 `.env`。
3. 在 `.env` 中填写 `OPENAI_API_KEY`。
4. 双击 `start_windows.bat`。
5. 浏览器打开 `http://127.0.0.1:8000`。

### Docker

```bash
cp .env.example .env
# 编辑 .env 填入 OPENAI_API_KEY
docker compose up --build
```

浏览器打开：

```text
http://127.0.0.1:8000
```

## 4. 部署前必须确认

- `OPENAI_API_KEY` 已配置；
- `OPENAI_MODEL` 是账号可用模型；
- `templates/v31_dashboard_template.html` 是最终参数化母版；
- `schemas/dashboard.schema.json` 与母版字段保持一致；
- `prompts/core_business_analysis.md` 已由业务负责人确认；
- 上传文件必须能映射到 `question` 和 `answer`；
- 最终 HTML 内必须存在完整 `dashboard-data`。

## 5. 推荐阅读顺序

```text
docs/00_工程可实施性验证.md
docs/01_程序员先读.md
docs/02_PRD.md
docs/03_输入数据与字段映射.md
prompts/core_business_analysis.md
schemas/dashboard.schema.json
prompts/json_quality_check.md
docs/04_API接口.md
docs/05_部署与验收.md
```

## 6. 当前实现说明

本包是可部署的 MVP 骨架，不是完整生产系统。

已实现：

```text
文件上传
Excel / CSV 解析
字段自动识别
字段映射确认
后台分析任务
OpenAI API 调用
JSON 本地校验
质量质检
HTML 注入
在线预览
HTML 下载
```

生产环境仍建议补充：

```text
数据库
对象存储
登录权限
任务队列
监控告警
大文件分批分析
调用成本统计
```
