# API 接口

| 方法 | 路径 | 作用 |
|---|---|---|
| POST | /api/files/upload | 上传文件并返回 Sheet、表头和预览 |
| POST | /api/fields/detect | 自动识别字段 |
| POST | /api/fields/confirm | 确认字段映射 |
| POST | /api/dashboards/generate | 创建生成任务 |
| GET | /api/tasks/{taskId} | 查询任务状态 |
| GET | /api/dashboards/{id}/json | 获取标准 JSON |
| GET | /api/dashboards/{id}/html | 在线预览 HTML |
| GET | /api/dashboards/{id}/download | 下载 HTML |

生产环境建议增加登录、权限、记录列表和删除接口。
