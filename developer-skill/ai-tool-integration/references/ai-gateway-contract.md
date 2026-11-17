# AI API 网关规范

## 规则

所有模型调用必须通过平台 AI API 网关：

- 文本：`POST /api/ai/chat/completions`
- 图片：`POST /api/ai/images/generations`
- 异步任务查询：`GET /api/ai/tasks/{taskId}`
- 向量：`POST /api/ai/embeddings`
- 模型列表：`GET /api/ai/models`

工具不得直接请求模型供应商地址，也不得保存模型供应商 Key。

## 文本调用

```json
{
  "runId": "run_xxx",
  "toolKey": "market-intelligence",
  "userId": "u_001",
  "model": "gpt-4.1",
  "params": {
    "messages": [
      {"role": "system", "content": "你是市场分析助手"},
      {"role": "user", "content": "分析宠物饮水器市场"}
    ],
    "temperature": 0.3
  }
}
```

平台会记录：

- 请求参数和 Prompt
- 返回结果
- 输入 Token、输出 Token、总 Token
- 用户、部门、工具、`runId`
- 模型、耗时、成本、错误信息

## 图片调用

```json
{
  "runId": "run_xxx",
  "toolKey": "promo-image",
  "userId": "u_002",
  "model": "gemini-3.1-flash-lite-image",
  "params": {
    "prompt": "A cat playing on the grass",
    "size": "auto",
    "quality": "1K",
    "image_urls": [],
    "model_params": {
      "thinking_level": "auto"
    }
  }
}
```

Evolink Nanobanana 2 Lite 使用异步任务模式，平台会返回任务 ID。工具必须通过平台查询接口获取任务结果：

```http
GET /api/ai/tasks/{taskId}?modelName=gemini-3.1-flash-lite-image
```

任务完成后，Evolink 返回的图片链接有效期为 24 小时。工具必须及时通过平台文件接口登记或转存对象存储，不能长期依赖供应商临时链接。

## 安全

业务 Prompt 和模型返回按运行记录权限保存。凭证字段必须脱敏或加密，不能明文保存。
