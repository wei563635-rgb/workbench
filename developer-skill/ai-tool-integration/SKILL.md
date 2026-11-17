---
name: ai-tool-integration
description: Use this skill when modifying or building customer AI tools that must be deployed into the Yiqi AI Toolbench. It covers required tool manifest fields, mandatory tool-run reporting APIs, mandatory AI gateway usage, file upload rules, iframe postMessage rules, security redaction, and validation before deployment.
---

# 一起互动 AI 工具接入规范

## 必须遵守

正式部署到一起互动 AI 工作台的工具必须满足：

1. 提供 `tool-manifest.json`。
2. 通过平台运行接口创建、更新、完成运行记录。
3. 所有模型调用必须走平台 AI API 网关。
4. 上传文件、生成图片、报告附件必须通过平台文件接口登记。
5. 不得在浏览器端或工具源码中硬编码大模型 API Key。
6. 凭证字段不得进入业务日志：`apiKey`、`Authorization`、`password`、`token`、`cookie`。

未满足规范的工具只能作为临时外链，不允许部署到正式服务器路径。

## 工作流程

1. 阅读 `references/tool-integration.md` 确认工具类型和接入方式。
2. 阅读 `references/api-contract.md` 接入运行记录、文件、项目关联接口。
3. 阅读 `references/ai-gateway-contract.md` 替换所有模型调用。
4. iframe 工具另读 `references/postmessage-contract.md`。
5. 交付前运行 `scripts/validate-tool-manifest.js path/to/tool-manifest.json`。

## 最小调用顺序

```text
POST /api/tool-runs/start
  ↓
POST /api/ai/chat/completions 或 /api/ai/images/generations
  ↓
POST /api/files/upload
  ↓
POST /api/tool-runs/{runId}/finish
  ↓
可选：POST /api/project-links
```

## 交付判断

如果发现工具直接调用 OpenAI、Claude、Gemini、通义、火山、智谱等外部模型地址，必须改成平台 AI 网关后才能部署。

