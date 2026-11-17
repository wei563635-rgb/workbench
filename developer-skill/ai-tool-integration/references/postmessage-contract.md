# iframe postMessage 规范

iframe 工具无法直接调用平台接口时，必须向父页面发送标准消息。

## 运行结果消息

```js
window.parent.postMessage({
  type: 'AI_TOOL_RUN_RESULT',
  toolKey: 'market-intelligence',
  runId: 'run_xxx',
  status: 'success',
  inputSummary: '宠物饮水器市场分析',
  outputSummary: '发现大容量、静音、易清洗机会点',
  outputData: {
    insights: [],
    recommendations: []
  },
  uploadedFiles: [],
  generatedFiles: [],
  modelName: 'gpt-4.1',
  tokenUsage: {
    promptTokens: 1200,
    completionTokens: 800,
    totalTokens: 2000
  },
  costEstimate: 0.4,
  startedAt: '2026-07-05T10:00:00+08:00',
  finishedAt: '2026-07-05T10:01:00+08:00'
}, 'https://平台域名')
```

## 父页面要求

父页面必须校验：

- `event.origin` 是否在允许域名内。
- `type` 是否为 `AI_TOOL_RUN_RESULT`。
- `toolKey` 是否与当前工具一致。
- `runId` 是否属于当前登录用户打开的运行。

校验通过后由父页面调用平台接口保存结果。

