# 最终 HTML 母版说明

## 1. 母版来源

以当前最终满意版问大家看板 HTML 为唯一页面母版。

## 2. 不允许改动

```text
页面结构
CSS 风格
JS 交互
导航
模块顺序
下钻逻辑
测图矩阵结构
详情页证据链结构
附录明细追溯结构
```

## 3. 唯一可替换区域

只允许替换：

```html
<script id="dashboard-data" type="application/json">
这里是完整分析 JSON
</script>
```

## 4. 禁止外部依赖

导出的最终 HTML 禁止：

```text
引用外部 CSV
引用外部 JSON
fetch 外部数据
依赖本地文件路径
只保留样例数据
```

## 5. 数据内嵌校验

HTML 打开时应检查：

```text
dashboard-data 是否存在
JSON 是否合法
details 是否为空
question / answer 是否存在
核心模块是否为空
是否仍是样例数据
```

校验失败时显示明显提示：

```text
数据内嵌校验未通过：当前 HTML 没有写入完整数据。
```
