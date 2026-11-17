# 工具接入说明

## 工具类型

- 静态部署工具：优先接入方式。工具页面直接调用平台接口。
- iframe 工具：工具无法直接合入平台时使用，结果通过 `postMessage` 交给父页面。
- 外链工具：临时方式，只记录访问，不支持完整结果沉淀。

## tool-manifest.json

```json
{
  "toolKey": "market-intelligence",
  "name": "市场情报",
  "version": "1.0.0",
  "departmentKey": "ai-market",
  "entry": "/tools/market-intelligence/index.html",
  "integrationType": "static",
  "status": "normal",
  "callbackEnabled": true,
  "aiGatewayRequired": true,
  "modelTypes": ["text"],
  "resultTypes": ["json", "file"],
  "owner": "AI 市场部"
}
```

## 字段要求

- `toolKey`：全局唯一，只允许小写字母、数字、短横线。
- `integrationType`：`static`、`iframe`、`external`。
- `status`：`normal`、`maintenance`、`unopened`、`disabled`。
- `callbackEnabled`：正式工具必须为 `true`，未开放和外链工具除外。
- `aiGatewayRequired`：正式工具必须为 `true`。

## 禁止项

- 禁止把大模型 Key 写在前端。
- 禁止绕过 `/api/ai/*` 直接调用模型供应商。
- 禁止只保存截图或摘要而不上报完整 JSON。
- 禁止把密码、Cookie、Session Token 写入日志。

