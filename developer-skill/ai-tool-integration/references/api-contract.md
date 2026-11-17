# 平台接口契约

## 创建运行

`POST /api/tool-runs/start`

```json
{
  "toolKey": "market-intelligence",
  "userId": "u_001",
  "inputSummary": "宠物饮水器市场分析"
}
```

返回 `runId`。后续模型调用、文件上传、结果上报都必须带上该 `runId`。

## 上报事件

`POST /api/tool-runs/{runId}/events`

用于进度、阶段结果、导出、异常等中间事件。

```json
{
  "eventType": "progress",
  "message": "正在生成 B 看板",
  "progress": 60
}
```

## 完成运行

`POST /api/tool-runs/{runId}/finish`

```json
{
  "status": "success",
  "outputSummary": "发现大容量、静音、易清洗机会点",
  "outputData": {
    "insights": [],
    "recommendations": []
  }
}
```

失败也必须调用该接口：

```json
{
  "status": "failed",
  "outputSummary": "API Timeout",
  "outputData": {
    "errorCode": "AI_GATEWAY_TIMEOUT",
    "errorMessage": "模型调用超时"
  }
}
```

## 文件登记

`POST /api/files/upload`

上传文件、生成图片、报告附件都必须登记到平台，并绑定 `runId`。

```json
{
  "runId": "run_xxx",
  "fileName": "result.xlsx",
  "contentType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "size": 10240
}
```

## 关联项目

`POST /api/project-links`

```json
{
  "projectId": "proj_001",
  "runId": "run_xxx",
  "remark": "华东地区投放分析",
  "category": "地域优化"
}
```

关联时平台会保存结果快照。

