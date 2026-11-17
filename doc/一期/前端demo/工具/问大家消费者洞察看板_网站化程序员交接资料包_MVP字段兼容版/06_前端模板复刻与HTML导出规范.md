# 前端模板复刻与 HTML 导出规范

## 1. 核心原则

```text
最终确认版 HTML 是唯一页面母版
页面结构不能重做
CSS 风格不能重写
JS 交互不能删改
只允许替换 dashboard-data 中的 JSON
```

## 2. 固定页面能力

必须保留最终满意版中的：

```text
导航结构
总览模块
核心决策点模块
人群分类 / 关注点差异模块
推广图表达方向模块
推广图测图矩阵模块
详情页证据链模块
附录明细追溯模块
筛选 / 搜索 / 下钻 / tab / 分页 / 下载交互
```

## 3. 数据读取方式

HTML 中必须包含：

```html
<script id="dashboard-data" type="application/json">
这里是完整分析 JSON
</script>
```

前端只能读取 dashboard-data：

```javascript
const DATA = JSON.parse(document.getElementById('dashboard-data').textContent);
```

禁止：

```text
fetch 外部 JSON
读取外部 CSV
依赖本地文件路径
保留样例数据作为最终数据
```

## 4. HTML 导出要求

最终导出的 HTML 必须是单文件，能在断网状态下打开。

导出前必须检查：

```text
dashboard-data 是否存在
JSON 是否合法
details 是否为空
question / answer 是否存在
核心模块是否为空
是否仍是样例数据
是否存在 fetch 外部数据
```
