# 推广图制作平台接入实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将客户 Next.js 推广图制作工具以 Docker + iframe 方式接入一起互动 AI 工作台，统一平台鉴权、运行记录、文件存储和 Coze/豆包/Evolink 模型网关，同时保持客户业务 UI 和接口响应结构不变。

**架构：** 工作台签发 60 秒单次 Ticket，目标工具服务端兑换为受限工具会话并通过平台内省鉴权。目标工具用 PlatformClient 创建每次业务运行、上传文件并调用平台 AI 网关；工作台以供应商适配器翻译 Coze、OpenAI 兼容豆包和 Evolink 协议。两个仓库分别在隔离分支实现，先完成平台契约，再用 mock 平台完成目标工具适配，最后联合验证。

**技术栈：** Java 21、Spring Boot 3.3、JdbcTemplate、JUnit 5、Vue 3、Node test runner、Next.js 14.2、React 18、TypeScript、better-sqlite3、Node 20、Docker、Nginx。

---

## 仓库与安全边界

- 工作台：`/private/tmp/workbench-promo-image-integration`，分支 `feat/promo-image-integration`，基线 `323a4f9`。
- 目标工具：`/private/tmp/my-ai-workbench-cloud-yiqi-integration`，分支 `feat/yiqi-toolbench-integration`，基线 `c91823f`。
- 供应商 Token、Key、服务凭证和加密主密钥只进入被 Git 忽略的本地环境文件或进程环境，不得进入源码、测试夹具、提交信息和日志。
- 工作台先提供可独立测试的契约；目标工具测试使用 mock 平台；最后才做真实低成本冒烟。

## 文件结构

### 工作台

- 创建 `backend/src/main/java/com/yiqi/aitoolbench/toolsession/ToolSessionRepository.java`：Ticket 与工具会话持久化、原子消费和撤销。
- 创建 `backend/src/main/java/com/yiqi/aitoolbench/toolsession/ToolSessionService.java`：签发、兑换、内省和权限校验。
- 创建 `backend/src/main/java/com/yiqi/aitoolbench/toolsession/ToolSessionController.java`：工具会话 HTTP 接口。
- 修改 `backend/src/main/java/com/yiqi/aitoolbench/security/PermissionService.java`：识别受限工具会话 Bearer Token。
- 创建 `backend/src/main/java/com/yiqi/aitoolbench/security/SecretCrypto.java`：AES-GCM 凭证加解密。
- 创建 `backend/src/main/java/com/yiqi/aitoolbench/ai/AiModelConfig.java` 和供应商适配器文件。
- 修改 `AiGatewayService.java`、`ApiController.java`、`WorkbenchRepository.java`、`StorageService.java`、`WebConfig.java` 和配置文件。
- 修改 `frontend/src/api.js`、`frontend/src/App.vue`、`frontend/src/styles.css` 并新增测试。

### 目标工具

- 创建 `lib/platform/config.ts`、`types.ts`、`client.ts`、`auth.ts`、`run.ts`、`outbox.ts`、`provider-compat.ts`。
- 创建 `app/platform-entry/route.ts` 和 `app/api/platform/outbox/flush/route.ts`。
- 修改鉴权边界、三个业务 API route、SQLite schema 和图片任务/历史存储。
- 创建 `tool-manifest.json`、`Dockerfile`、`.dockerignore`、`docker-compose.platform.yml`、`deploy/nginx-promo-image.conf`。
- 增加 `tsx --test` 测试框架与 `tests/platform/*.test.ts`。

## 任务 1：依赖与干净基线

**文件：** 无代码修改。

- [ ] **步骤 1：验证分支**

~~~bash
git -C /private/tmp/workbench-promo-image-integration status --short --branch
git -C /private/tmp/my-ai-workbench-cloud-yiqi-integration status --short --branch
~~~

预期：工作台只有本计划文档变更；目标工具工作区干净。

- [ ] **步骤 2：安装目标工具依赖**

~~~bash
cd /private/tmp/my-ai-workbench-cloud-yiqi-integration
npm ci
~~~

预期：退出码 0，`node_modules/next/dist/docs/` 存在。

- [ ] **步骤 3：遵守目标仓库 AGENTS.md，阅读本地 Next.js 指南**

~~~bash
rg -n "basePath|Route Handlers|cookies|middleware" node_modules/next/dist/docs -g '*.md' -g '*.mdx'
~~~

完整阅读命中的 basePath、Route Handler、Cookie 和 Middleware 指南后才允许写 Next.js 代码。

- [ ] **步骤 4：运行两边基线**

~~~bash
cd /private/tmp/workbench-promo-image-integration/backend && mvn test
cd /private/tmp/workbench-promo-image-integration/frontend && npm test && npm run build
cd /private/tmp/my-ai-workbench-cloud-yiqi-integration && npm run lint && npm run build
~~~

预期：全部退出码 0。若已有失败，停止实现并报告。

## 任务 2：平台 Ticket 与工具会话

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/toolsession/ToolSessionRepository.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/toolsession/ToolSessionService.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/toolsession/ToolSessionController.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/WebConfig.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/security/PermissionService.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/toolsession/ToolSessionServiceTest.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/toolsession/ToolSessionControllerTest.java`

- [ ] **步骤 1：编写失败测试**

覆盖 60 秒过期、一次消费、跨工具拒绝、项目归档、工具停用、来源平台会话失效，以及数据库不保存原始 Token：

~~~java
var issued = service.issue(context, "promo-image", "proj_001", now);
var exchanged = service.exchange(issued.rawTicket(), "promo-image", now.plusSeconds(1));
assertThat(exchanged.userId()).isEqualTo(context.userId());
assertThatThrownBy(() -> service.exchange(issued.rawTicket(), "promo-image", now.plusSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class);
assertThat(repository.findStoredTicket(issued.id()).tokenHash())
        .doesNotContain(issued.rawTicket());
~~~

- [ ] **步骤 2：验证测试失败**

~~~bash
cd /private/tmp/workbench-promo-image-integration/backend
mvn -Dtest=ToolSessionServiceTest,ToolSessionControllerTest test
~~~

预期：FAIL，相关类型尚不存在。

- [ ] **步骤 3：实现最小服务**

~~~java
public record IssuedTicket(String id, String rawTicket, Instant expiresAt) {}
public record ToolSessionContext(
        String userId, String account, String name, List<String> roleIds,
        List<String> departmentKeys, String toolKey, String projectId, Instant expiresAt) {}

public IssuedTicket issue(AuthContext context, String toolKey, String projectId, Instant now);
public ExchangedSession exchange(String rawTicket, String toolKey, Instant now);
public ToolSessionContext introspect(String rawSessionToken, Instant now);
~~~

Repository 创建 `tool_session_ticket`、`tool_session` 表；用 SecureRandom 生成 32 字节不透明 Token，只保存 SHA-256；交换在事务中以 `consumed_at is null` 条件更新并检查更新行数为 1。索引查询复杂度标为均摊 O(1)。

- [ ] **步骤 4：实现三个接口**

~~~java
@PostMapping("/api/tool-sessions/tickets")
TicketResponse issue(@RequestHeader("Authorization") String authorization,
                     @RequestBody TicketRequest request);

@PostMapping("/api/tool-sessions/exchange")
ExchangeResponse exchange(@RequestHeader("Authorization") String serviceAuthorization,
                          @RequestBody ExchangeRequest request);

@PostMapping("/api/tool-sessions/introspect")
IntrospectionResponse introspect(@RequestHeader("Authorization") String serviceAuthorization,
                                 @RequestHeader("X-Tool-Session") String toolSession);
~~~

`tickets` 继续受平台用户拦截；仅 exchange/introspect 排除通用拦截，并在 Controller 内恒定时间校验服务凭证。

兑换返回的工具会话 Token 使用 `tool_session_` 前缀。服务凭证只允许调用 exchange/introspect；目标工具后续调用运行、AI 和文件接口时使用 `Authorization: Bearer tool_session_xxx`。`PermissionService.requireContext` 识别该前缀，通过 `ToolSessionService` 映射为平台 `AuthContext`，同时把 `toolKey`、`projectId` 作为受限上下文保留供 Controller 做资源归属校验。工具会话不能访问后台管理接口。

- [ ] **步骤 5：测试并提交**

~~~bash
mvn -Dtest=ToolSessionServiceTest,ToolSessionControllerTest test
mvn test
git add backend/src/main/java/com/yiqi/aitoolbench/toolsession backend/src/main/java/com/yiqi/aitoolbench/WebConfig.java backend/src/main/java/com/yiqi/aitoolbench/security/PermissionService.java backend/src/test/java/com/yiqi/aitoolbench/toolsession
git commit -m "feat: add iframe tool sessions"
~~~

预期：全部 PASS。

## 任务 3：工作台 iframe 入口与回传校验

**文件：**
- 修改：`frontend/src/api.js`、`frontend/src/App.vue`、`frontend/src/styles.css`
- 测试：`frontend/src/__tests__/promoImageIframe.test.js`

- [ ] **步骤 1：编写失败测试**

~~~js
assert.equal(buildPromoImageEntry({
  entryUrl: '/tools/promo-image/platform-entry?ticket=x'
}), '/tools/promo-image/platform-entry?ticket=x');

assert.equal(isTrustedToolMessage({
  origin: 'https://workbench.internal',
  data: { type: 'AI_TOOL_RUN_RESULT', toolKey: 'promo-image', runId: 'run_1' }
}, 'https://workbench.internal'), true);
~~~

再断言错误 Origin、toolKey 或空 runId 均为 false；结构校验通过后，测试必须 mock `GET /api/tool-runs/run_1`，确认服务端返回的 userId、projectId 和 toolKey 与当前上下文一致才刷新页面。不存在或无权限的 runId 不刷新。

- [ ] **步骤 2：运行测试确认失败**

~~~bash
cd /private/tmp/workbench-promo-image-integration/frontend && npm test
~~~

预期：FAIL，函数不存在。

- [ ] **步骤 3：实现 Ticket API 与 iframe**

~~~js
export function createToolSessionTicket(toolKey, projectId) {
  return postJson('/api/tool-sessions/tickets', { toolKey, projectId });
}
~~~

`openTool` 对 `promo-image` 不再创建打开型运行，只签发 Ticket 并展示同域 iframe；其他工具不变。iframe 设置 `referrerpolicy="no-referrer"`，关闭时清空 src。

- [ ] **步骤 4：实现消息校验并验证**

只接受当前 Origin、`promo-image` 和非空运行 ID；随后通过受平台鉴权保护的运行详情接口确认 runId 属于当前用户、当前项目和当前工具。消息仅触发刷新，不能直接落库。

~~~bash
npm test
npm run build
git add src/api.js src/App.vue src/styles.css src/__tests__/promoImageIframe.test.js
git commit -m "feat: embed promo image tool"
~~~

## 任务 4：模型配置加密与供应商字段

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/security/SecretCrypto.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/ai/AiModelConfig.java`
- 修改：`WorkbenchRepository.java`、`ApiController.java`、`application.yml`、`backend/.env.example`
- 测试：`SecretCryptoTest.java`、`AiModelConfigRepositoryTest.java`

- [ ] **步骤 1：编写失败测试**

~~~java
var encrypted = crypto.encrypt("supplier-secret");
assertThat(encrypted).startsWith("enc:v1:").doesNotContain("supplier-secret");
assertThat(crypto.decrypt(encrypted)).isEqualTo("supplier-secret");
assertThat(repository.loadModelConfigs().get("coze-copywriting").provider()).isEqualTo("coze");
~~~

管理接口测试必须断言 JSON 不含明文 `apiKey`。

- [ ] **步骤 2：运行失败测试**

~~~bash
mvn -Dtest=SecretCryptoTest,AiModelConfigRepositoryTest test
~~~

预期：FAIL，类型和字段不存在。

- [ ] **步骤 3：实现模型类型与 AES-GCM**

~~~java
public record AiModelConfig(
        String id, String name, String provider, String modelType, String modelName,
        String apiBaseUrl, String encryptedApiKey, Map<String, Object> providerOptions,
        int timeoutSeconds, int retryCount, boolean enabled) {}
~~~

SecretCrypto 使用环境中的 256 位主密钥、随机 12 字节 IV、AES/GCM/NoPadding。HTTP 网关模式缺少主密钥时启动失败；测试显式提供测试密钥。

- [ ] **步骤 4：迁移并验证**

`model_config` 新增 `provider`、`provider_options`、`api_key_encrypted`；已有 api_key 首次读取时加密迁移并清空旧值。

~~~bash
mvn test
git add backend/src/main/java/com/yiqi/aitoolbench/security/SecretCrypto.java backend/src/main/java/com/yiqi/aitoolbench/ai/AiModelConfig.java backend/src/main/java/com/yiqi/aitoolbench/WorkbenchRepository.java backend/src/main/java/com/yiqi/aitoolbench/ApiController.java backend/src/main/resources/application.yml backend/.env.example backend/src/test
git commit -m "feat: secure provider model configs"
~~~

## 任务 5：Coze、豆包与 Evolink 网关适配器

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/ai/AiProviderAdapter.java`
- 创建：`CozeProviderAdapter.java`、`OpenAiCompatibleProviderAdapter.java`、`EvolinkProviderAdapter.java`
- 修改：`AiGatewayService.java`、`ApiController.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/ai/ProviderAdapterTest.java`

- [ ] **步骤 1：编写失败的协议测试**

用本地 HttpServer 捕获请求：

~~~java
assertThat(capturedCozeBody).containsEntry("bot_id", "bot-from-options");
assertThat(capturedCozeHeaders.getFirst("Authorization")).isEqualTo("Bearer decrypted-key");
assertThat(doubaoBody.get("messages")).isEqualTo(originalMessages);
assertThat(evolinkBody).containsEntry("image_urls", List.of("https://files/input.png"));
~~~

并断言标准化响应包含 choices/usage 或 image task ID。

- [ ] **步骤 2：确认失败**

~~~bash
mvn -Dtest=ProviderAdapterTest test
~~~

预期：FAIL，适配器不存在。

- [ ] **步骤 3：实现统一接口**

~~~java
public interface AiProviderAdapter {
    boolean supports(AiModelConfig config);
    GatewayResult chat(AiGatewayRequest request, AiModelConfig config);
    GatewayResult image(AiGatewayRequest request, AiModelConfig config);
    Map<String, Object> task(String taskId, AiModelConfig config);
}
~~~

Coze 翻译 query/conversation_id/Bot ID；豆包保留 messages 和采样参数；Evolink 保留 prompt、image_urls、尺寸、质量、数量并实现任务查询。AiGatewayService 只负责选择唯一适配器和统一状态。

- [ ] **步骤 4：绑定真实上下文**

Controller 从 `Authorization` 中的普通平台会话或受限工具会话获取 userId、toolKey、projectId，拒绝请求体冒充；工具会话只能创建 `promo-image` 且只能绑定票据中的 projectId。后续 AI、文件、event 和 finish 接口确认 runId 属于同一受限上下文后才执行。

- [ ] **步骤 5：测试并提交**

~~~bash
mvn test
git add backend/src/main/java/com/yiqi/aitoolbench/ai backend/src/main/java/com/yiqi/aitoolbench/AiGatewayService.java backend/src/main/java/com/yiqi/aitoolbench/ApiController.java backend/src/test/java/com/yiqi/aitoolbench/ai
git commit -m "feat: add customer model gateway adapters"
~~~

## 任务 6：平台文件持久化与远端转存

**文件：**
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/StorageService.java`、`ApiController.java`、`application.yml`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/StorageServiceTest.java`

- [ ] **步骤 1：编写失败测试**

~~~java
var stored = storage.save("run_1", "../input.png", "image/png", bytes.length,
        new ByteArrayInputStream(bytes));
assertThat(stored.fileName()).isEqualTo("input.png");
assertThat(stored.downloadUrl())
        .isEqualTo("https://workbench.internal/api/files/demo-download/" + stored.fileId());
~~~

另测远端图片流式转存、非法 fileId、文件超限和 SSRF 内网地址拒绝。

- [ ] **步骤 2：运行确认失败**

~~~bash
mvn -Dtest=StorageServiceTest test
~~~

- [ ] **步骤 3：实现并验证**

增加 public-origin、最大字节数和允许 MIME；远端下载流式处理，复杂度 O(n) 时间、O(1) 额外内存。

~~~bash
mvn test
git add backend/src/main/java/com/yiqi/aitoolbench/StorageService.java backend/src/main/java/com/yiqi/aitoolbench/ApiController.java backend/src/main/resources/application.yml backend/src/test/java/com/yiqi/aitoolbench/StorageServiceTest.java
git commit -m "feat: persist tool images through platform storage"
~~~

## 任务 7：目标工具测试框架与 PlatformClient

**文件：**
- 修改：`package.json`、`package-lock.json`
- 创建：`lib/platform/config.ts`、`types.ts`、`client.ts`
- 测试：`tests/platform/client.test.ts`

- [ ] **步骤 1：增加测试运行器**

~~~json
{
  "test": "tsx --test tests/**/*.test.ts"
}
~~~

运行 `npm install --save-dev tsx` 更新 lockfile。

- [ ] **步骤 2：编写失败测试**

~~~ts
const run = await client.startRun({ inputSummary: '文案生成' });
assert.equal(run.toolKey, 'promo-image');
assert.equal(capturedBody.userId, undefined);
await assert.rejects(
  () => client.chat({ runId: run.runId, model: 'coze-copywriting', params: {} }),
  /平台 AI 网关请求失败/
);
~~~

- [ ] **步骤 3：运行确认失败**

~~~bash
npm test
~~~

- [ ] **步骤 4：实现服务端客户端**

~~~ts
export type PlatformContext = {
  userId: string; account: string; name: string; role: string;
  departmentKeys: string[]; toolKey: 'promo-image'; projectId: string; expiresAt: string;
};

export type PlatformCookieSpec = {
  name: 'promo_platform_session'; value: string; httpOnly: true;
  secure: boolean; sameSite: 'lax'; path: '/tools/promo-image';
};

export class PlatformClient {
  startRun(input: { inputSummary: string }): Promise<PlatformRun>;
  reportEvent(runId: string, event: PlatformRunEvent): Promise<void>;
  finishRun(runId: string, result: PlatformRunResult): Promise<void>;
  chat(request: PlatformAiRequest): Promise<PlatformChatResponse>;
  image(request: PlatformAiRequest): Promise<PlatformImageResponse>;
  task(taskId: string, modelName: string): Promise<PlatformTaskResponse>;
  upload(runId: string, file: Blob, fileName: string): Promise<PlatformFile>;
}
~~~

服务凭证只能由 platform-entry 的 Ticket 兑换/内省读取，不得使用 `NEXT_PUBLIC_*`。普通 PlatformClient 请求只携带 HttpOnly Cookie 中对应的受限工具会话 Token 作为 Bearer Token，不携带服务凭证，也不在请求体提交 userId、toolKey 或 projectId。

- [ ] **步骤 5：验证并提交**

~~~bash
npm test && npm run lint && npm run build
git add package.json package-lock.json lib/platform tests/platform/client.test.ts
git commit -m "feat: add platform service client"
~~~

## 任务 8：目标工具平台登录与鉴权

**文件：**
- 创建：`lib/platform/auth.ts`、`app/platform-entry/route.ts`
- 修改：`middleware.ts`、`lib/require-user.ts`、`lib/session.ts`、`lib/api-tool-guard.ts`、`app/api/auth/me/route.ts`
- 测试：`tests/platform/auth.test.ts`

- [ ] **步骤 1：编写失败测试**

~~~ts
assert.match(setCookie, /promo_platform_session=/);
assert.match(setCookie, /HttpOnly/);
assert.match(setCookie, /Secure/);
assert.match(setCookie, /SameSite=Lax/);
assert.match(setCookie, /Path=\/tools\/promo-image/);
assert.equal(platformUser.credits, null);
~~~

另测 Ticket 失败 401、内省失败不回退本地、standalone 本地登录仍可用。

- [ ] **步骤 2：确认失败**

~~~bash
npm test
~~~

- [ ] **步骤 3：实现入口与鉴权边界**

~~~ts
export async function getPlatformContext(): Promise<PlatformContext | null>;
export async function requirePlatformContext(): Promise<PlatformContext>;
export function buildPlatformSessionCookie(token: string, secure: boolean): PlatformCookieSpec;
~~~

platform-entry 服务端兑换 Ticket，设置 Cookie 后 303 跳转到 basePath 根页。platform 模式下 requireUser/session/guard 使用内省结果，toolAccess 全 true、credits null；401 时不查本地用户。standalone 保留本地登录和积分。

- [ ] **步骤 4：验证并提交**

~~~bash
npm test && npm run lint && npm run build
git add lib/platform/auth.ts app/platform-entry/route.ts middleware.ts lib/require-user.ts lib/session.ts lib/api-tool-guard.ts app/api/auth/me/route.ts tests/platform/auth.test.ts
git commit -m "feat: authenticate iframe users through platform"
~~~

## 任务 9：运行生命周期、outbox 与文件封装

**文件：**
- 创建：`lib/platform/run.ts`、`lib/platform/outbox.ts`、`app/api/platform/outbox/flush/route.ts`
- 修改：`lib/db.ts`
- 测试：`tests/platform/run.test.ts`、`tests/platform/outbox.test.ts`

- [ ] **步骤 1：编写失败测试**

~~~ts
const value = await withPlatformRun({ inputSummary: '提示词反推' }, async (run) => {
  await run.event({ eventType: 'progress', message: '视觉分析', progress: 30 });
  return { value: 'ok', outputSummary: '完成', outputData: { finalPrompt: 'x' } };
});
assert.equal(value, 'ok');
assert.deepEqual(calls.map((c) => c.kind), ['start', 'event', 'finish-success']);
~~~

另测业务抛错触发 finish-failed、finish 网络失败写 outbox、重试成功删除、payload 敏感字段脱敏。

- [ ] **步骤 2：确认失败**

~~~bash
npm test
~~~

- [ ] **步骤 3：实现 schema 与封装**

~~~ts
export async function withPlatformRun<T>(
  input: { inputSummary: string },
  execute: (run: PlatformRunHandle) => Promise<{
    value: T; outputSummary: string; outputData: object
  }>
): Promise<T>;
~~~

新增 `platform_outbox(id, kind, run_id, payload_json, attempts, next_attempt_at, created_at)` 和 next_attempt_at 索引。指数退避上限 15 分钟；单批最多 50 条；条件删除避免并发重复。

- [ ] **步骤 4：验证并提交**

~~~bash
npm test && npm run lint && npm run build
git add lib/platform/run.ts lib/platform/outbox.ts app/api/platform/outbox/flush/route.ts lib/db.ts tests/platform/run.test.ts tests/platform/outbox.test.ts
git commit -m "feat: track platform run lifecycle"
~~~

## 任务 10：文案生成改走 Coze 网关

**文件：**
- 创建：`lib/platform/provider-compat.ts`
- 修改：`app/api/copywriting/route.ts`
- 测试：`tests/platform/copywriting.test.ts`

- [ ] **步骤 1：编写失败兼容测试**

~~~ts
assert.equal(body.success, true);
assert.equal(body.reply, '生成文案');
assert.equal(body.conversation_id, 'conv_1');
assert.ok(Array.isArray(body.messages));
assert.deepEqual(platformCalls.map((x) => x.kind),
  ['start', 'chat', 'finish-success']);
~~~

- [ ] **步骤 2：确认失败**

~~~bash
npm test
~~~

预期：route 仍直连 Coze。

- [ ] **步骤 3：替换边界**

保留输入校验、Prompt、conversation_id 和响应拼装；删除 Coze Token、Bot ID、URL 和 Authorization。平台模型名固定 `coze-copywriting`，params 只含业务字段。

- [ ] **步骤 4：验证并提交**

~~~bash
npm test && npm run lint && npm run build
git add lib/platform/provider-compat.ts app/api/copywriting/route.ts tests/platform/copywriting.test.ts
git commit -m "feat: route copywriting through platform gateway"
~~~

## 任务 11：提示词反推改走豆包网关

**文件：**
- 修改：`app/api/prompt-reverse/route.ts`
- 测试：`tests/platform/prompt-reverse.test.ts`

- [ ] **步骤 1：编写失败的三调用测试**

~~~ts
assert.deepEqual(platformCalls.map((x) => x.model), [
  'doubao-vision-customer',
  'doubao-vision-customer',
  'doubao-chat-customer'
]);
assert.equal(body.data.productPrompt, '产品描述');
assert.equal(body.data.stylePrompt, '风格描述');
assert.equal(body.data.finalPrompt, '规则\n融合结果');
assert.equal(new Set(platformCalls.map((x) => x.runId)).size, 1);
~~~

- [ ] **步骤 2：确认失败**

~~~bash
npm test
~~~

- [ ] **步骤 3：替换边界**

保留两次视觉 Prompt、融合 Prompt、采样参数、data URI 和原响应字段；删除豆包 Key、URL 和 Authorization。三个调用共享一个 runId，阶段进度依次 20、50、80。

- [ ] **步骤 4：验证并提交**

~~~bash
npm test && npm run lint && npm run build
git add app/api/prompt-reverse/route.ts tests/platform/prompt-reverse.test.ts
git commit -m "feat: route prompt reverse through platform gateway"
~~~

## 任务 12：Evolink 生图、轮询与文件转存

**文件：**
- 修改：`app/api/image-generation/route.ts`、`app/api/image-generation/[taskId]/route.ts`
- 修改：`lib/image-generation-task-store.ts`、`lib/image-history-store.ts`
- 测试：`tests/platform/image-generation.test.ts`

- [ ] **步骤 1：编写失败的异步测试**

~~~ts
assert.deepEqual(callOrder, [
  'start-run', 'upload-input', 'submit-image', 'poll-task',
  'persist-output', 'finish-success'
]);
assert.match(resultImages[0].url,
  /^https:\/\/workbench\.internal\/api\/files\//);
assert.equal(savedTask.platformRunId, 'run_1');
~~~

另测远端任务失败、文件转存失败和轮询超时都结束失败运行。

- [ ] **步骤 2：确认失败**

~~~bash
npm test
~~~

- [ ] **步骤 3：替换输入、提交与轮询**

参考图先上传平台，以永久/签名 URL 作为 image_urls；模型名 `evolink-customer-image`。保存 platformRunId；通过平台 task 接口轮询；生成结果流式上传平台，再把永久 URL 写入原 history。文件复杂度 O(n) 时间、O(1) 额外内存。

- [ ] **步骤 4：验证并提交**

~~~bash
npm test && npm run lint && npm run build
git add app/api/image-generation/route.ts 'app/api/image-generation/[taskId]/route.ts' lib/image-generation-task-store.ts lib/image-history-store.ts tests/platform/image-generation.test.ts
git commit -m "feat: route image generation through platform"
~~~

## 任务 13：移除目标仓库旧供应商直连

**文件：**
- 删除：`lib/evolink-image.ts`、`lib/openai-image.ts`、`lib/image-upload.ts`、`lib/comfy-main-image.ts`、`lib/wildcard-image.ts`、`lib/proxy.ts`
- 修改或删除：`lib/server-ai-config.ts`、`lib/coze.ts`
- 修改：`lib/admin-api-access.ts`、`lib/public-ai-config.ts`
- 测试：`tests/platform/no-direct-provider.test.ts`

- [ ] **步骤 1：编写失败的源码门禁测试**

测试递归读取目标仓库受 Git 跟踪的 TypeScript/JavaScript 文件，断言不存在供应商域名、供应商凭证环境变量和直接 Authorization 组装：

~~~ts
const forbidden = [
  'api.coze.cn', 'ark.cn-beijing.volces.com', 'api.evolink.ai',
  'api.imgbb.com', 'COZE_API_TOKEN', 'DOUBAO_API_KEY',
  'EVOLINK_API_KEY', 'IMGBB_API_KEY'
];
for (const token of forbidden) assert.equal(sourceText.includes(token), false, token);
~~~

- [ ] **步骤 2：运行确认失败**

~~~bash
npm test
~~~

预期：FAIL，并准确列出当前旧直连文件。

- [ ] **步骤 3：删除无调用方的旧客户端**

删除已经被 PlatformClient 替代且无调用方的 Evolink、OpenAI、ImgBB、ComfyUI、wildcard 和代理模块。`server-ai-config.ts` 只保留非敏感显示默认值，若无调用方则删除；`coze.ts` 在文案 route 不再使用后删除。

- [ ] **步骤 4：把目标工具后台状态改为平台托管**

`admin-api-access.ts` 不再读取供应商环境变量或访问供应商账务接口，只返回“由一起互动 AI 工作台统一管理”的非敏感状态。`public-ai-config.ts` 只展示平台模型别名，不包含供应商 URL 或 Key 名称。页面结构不变。

- [ ] **步骤 5：验证并提交**

~~~bash
npm test && npm run lint && npm run build
git add -A lib tests/platform/no-direct-provider.test.ts
git commit -m "refactor: remove direct provider clients"
~~~

预期：源码门禁测试 PASS，业务页面构建不变。

## 任务 14：manifest、basePath、Docker 与 Nginx

**文件：**
- 创建：`tool-manifest.json`、`Dockerfile`、`.dockerignore`、`docker-compose.platform.yml`、`deploy/nginx-promo-image.conf`
- 修改：`next.config.js`、`README.md`
- 测试：`tests/platform/manifest.test.ts`

- [ ] **步骤 1：编写失败的 manifest 测试**

~~~ts
assert.equal(manifest.toolKey, 'promo-image');
assert.equal(manifest.integrationType, 'iframe');
assert.equal(manifest.callbackEnabled, true);
assert.equal(manifest.aiGatewayRequired, true);
assert.deepEqual(manifest.directModelEndpoints, []);
~~~

- [ ] **步骤 2：确认失败**

~~~bash
npm test
~~~

- [ ] **步骤 3：实现 manifest 与 basePath**

~~~js
const basePath = process.env.NEXT_PUBLIC_BASE_PATH || '';
module.exports = {
  basePath,
  output: 'standalone',
  webpack: (config) => {
    config.resolve.alias = { ...config.resolve.alias, '@': path.resolve(__dirname) };
    return config;
  },
};
~~~

- [ ] **步骤 4：创建容器交付**

使用 `node:20-bookworm-slim` 多阶段构建；builder 执行 `npm ci && npm run build`；runner 只复制 standalone/static/public 和运行目录，以非 root 用户运行，挂载 `/app/data`，健康检查 basePath 下 `/api/health`。Compose 只引用变量名。Nginx 将 `/tools/promo-image/` 转发到容器 3000 并设置转发头。

- [ ] **步骤 5：验证并提交**

~~~bash
node /Users/quspacedragon/IdeaProjects/spacedragon/workbench/developer-skill/ai-tool-integration/scripts/validate-tool-manifest.js tool-manifest.json
npm test && npm run lint && npm run build
docker build -t promo-image:integration .
git add tool-manifest.json Dockerfile .dockerignore docker-compose.platform.yml deploy/nginx-promo-image.conf next.config.js README.md tests/platform/manifest.test.ts
git commit -m "build: add promo image container deployment"
~~~

预期：manifest、Node 检查和 Docker build 全部成功。

## 任务 15：联合验证、真实低成本冒烟与安全扫描

**文件：**
- 创建：`scripts/integration-smoke.mjs`
- 创建：`docs/platform-integration-acceptance.md`
- 修改：联合验证发现的本规格范围内缺陷文件。

- [ ] **步骤 1：编写 mock 联合冒烟**

脚本固定执行：平台登录、创建项目、签发 Ticket、兑换、内省、文案运行、反推运行、生图任务、文件转存、finish、父页面消息校验。每步只打印状态名和 PASS/FAIL。

- [ ] **步骤 2：启动 mock 模式并执行**

~~~bash
cd /private/tmp/workbench-promo-image-integration/backend
mvn spring-boot:run -Dspring-boot.run.arguments='--server.port=18080 --app.ai.gateway-mode=mock'

cd /private/tmp/my-ai-workbench-cloud-yiqi-integration
INTEGRATION_MODE=platform PLATFORM_BASE_URL=http://127.0.0.1:18080 NEXT_PUBLIC_BASE_PATH=/tools/promo-image npm run dev

node /private/tmp/my-ai-workbench-cloud-yiqi-integration/scripts/integration-smoke.mjs
~~~

预期：全部 PASS；iframe 无二次登录；每次业务提交产生独立运行。

- [ ] **步骤 3：安全导入真实供应商配置**

使用用户在当前会话提供的凭证，通过平台管理接口写入加密配置。执行命令只从进程环境读取 `COZE_API_TOKEN`、`COZE_BOT_ID`、`DOUBAO_API_KEY`、`EVOLINK_API_KEY`，不得把值写入脚本、命令回显或 Git。数据库只检查 `enc:v1:` 前缀，管理接口只检查脱敏值。

- [ ] **步骤 4：一次低成本真实冒烟**

分别执行一次短文案、一次最小图片反推和一次单图低质量生图。预期：供应商调用成功、运行记录完成、图片转存平台。若鉴权或额度失败，记录 HTTP 状态但不输出凭证，并停止该供应商的重复调用。

- [ ] **步骤 5：全量验证**

~~~bash
cd /private/tmp/workbench-promo-image-integration/backend && mvn test
cd /private/tmp/workbench-promo-image-integration/frontend && npm test && npm run build
cd /private/tmp/my-ai-workbench-cloud-yiqi-integration && npm test && npm run lint && npm run build
docker build -t promo-image:integration /private/tmp/my-ai-workbench-cloud-yiqi-integration
node /Users/quspacedragon/IdeaProjects/spacedragon/workbench/developer-skill/ai-tool-integration/scripts/validate-tool-manifest.js /private/tmp/my-ai-workbench-cloud-yiqi-integration/tool-manifest.json
~~~

预期：全部退出码 0。

- [ ] **步骤 6：敏感信息与直连扫描**

~~~bash
cd /private/tmp/my-ai-workbench-cloud-yiqi-integration
rg -n -i 'api\.coze\.cn|ark\.cn-beijing\.volces\.com|api\.evolink\.ai|api\.imgbb\.com|COZE_API_TOKEN|DOUBAO_API_KEY|EVOLINK_API_KEY|IMGBB_API_KEY' --glob '!package-lock.json' .
git grep -n -E 'pat_[A-Za-z0-9]|sk-[A-Za-z0-9]|Authorization: Bearer [A-Za-z0-9]'
~~~

预期：第一条只允许 README 中“已移除变量”的迁移说明；第二条无输出。对工作台仓库执行同类 git grep，真实凭证无输出。

- [ ] **步骤 7：记录验收并提交**

~~~bash
git add scripts/integration-smoke.mjs docs/platform-integration-acceptance.md
git commit -m "test: verify platform integration"
~~~

如果工作台有联合修复，在工作台分支单独提交：

~~~bash
git add backend frontend
git commit -m "fix: close promo image integration gaps"
~~~

- [ ] **步骤 8：最终分支检查**

~~~bash
git -C /private/tmp/workbench-promo-image-integration status --short --branch
git -C /private/tmp/my-ai-workbench-cloud-yiqi-integration status --short --branch
git -C /private/tmp/workbench-promo-image-integration log --oneline feature/ai-toolbench-full-delivery..HEAD
git -C /private/tmp/my-ai-workbench-cloud-yiqi-integration log --oneline main..HEAD
~~~

预期：两个工作区干净，提交历史仅包含设计、计划和本规格范围内的实现/测试提交。
