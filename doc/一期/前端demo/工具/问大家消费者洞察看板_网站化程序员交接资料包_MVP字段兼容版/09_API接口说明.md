# API 接口说明

## 1. 上传文件接口

| 项目 | 内容 |
|---|---|
| 接口 | POST /api/files/upload |
| 说明 | 上传问大家 Excel / CSV |
| 请求 | multipart/form-data |
| 参数 | file |
| 返回 | fileId, filename, sheets, columns |
| 错误码 | FILE_TOO_LARGE, UNSUPPORTED_FORMAT, EMPTY_FILE |

## 2. 读取表头接口

| 项目 | 内容 |
|---|---|
| 接口 | GET /api/files/{fileId}/columns |
| 返回 | sheets, columns, rowPreview |

## 3. 字段识别接口

| 项目 | 内容 |
|---|---|
| 接口 | POST /api/fields/detect |
| 请求 | fileId, sheetName |
| 返回 | suggestedMapping, confidence, missingRequiredFields |

## 4. 字段映射确认接口

| 项目 | 内容 |
|---|---|
| 接口 | POST /api/fields/confirm |
| 请求 | fileId, sheetName, fieldMapping |
| 返回 | mappingId, validation |

请求示例：

```json
{
  "fileId": "file_001",
  "sheetName": "Sheet1",
  "fieldMapping": {
    "question": "消费者问题",
    "answer": "他人回答",
    "time": "时间",
    "productName": "商品名称"
  }
}
```

## 5. 生成看板接口

| 项目 | 内容 |
|---|---|
| 接口 | POST /api/dashboards/generate |
| 请求 | fileId, mappingId, options |
| 返回 | taskId |

## 6. 查询生成状态接口

| 项目 | 内容 |
|---|---|
| 接口 | GET /api/tasks/{taskId} |
| 返回 | status, progress, message, dashboardId |

## 7. 获取看板 JSON 接口

| 项目 | 内容 |
|---|---|
| 接口 | GET /api/dashboards/{dashboardId}/json |
| 返回 | 标准 JSON |

## 8. 获取看板 HTML 接口

| 项目 | 内容 |
|---|---|
| 接口 | GET /api/dashboards/{dashboardId}/html |
| 返回 | 单文件 HTML |

## 9. 下载 HTML 接口

| 项目 | 内容 |
|---|---|
| 接口 | GET /api/dashboards/{dashboardId}/download |
| 返回 | attachment HTML 文件 |
