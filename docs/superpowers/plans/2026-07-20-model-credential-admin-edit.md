# 模型调用凭证后台编辑实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在后台模型配置列表增加安全的编辑弹窗，使有模型管理权限的管理员能够修改模型名称、中转地址、供应商 Token、超时和重试次数。

**架构：** 沿用现有模型列表和 `PUT /api/admin/model-configs/{configId}` 接口。前端新增一个无副作用的表单策略模块负责初始化、校验和请求体构造，`App.vue` 只负责弹窗状态与接口编排；后端在现有更新入口补充最终参数校验，并继续通过 `SecretCrypto` 加密凭证、仅返回脱敏值。

**技术栈：** Vue 3 Composition API、Element Plus、Node.js `node:test`、Spring Boot 3、MockMvc、JUnit 5、AssertJ、AES-GCM、Maven。

---

## 文件结构

- 创建 `frontend/src/modelConfigEditor.js`：模型编辑表单的默认值、回填、校验和安全请求体构造。
- 创建 `frontend/src/__tests__/modelConfigEditor.test.js`：纯函数边界测试，重点验证 Token 留空不发送、非空保持原值。
- 修改 `frontend/src/App.vue`：列表编辑入口、模型配置弹窗、提交锁、成功/失败反馈与列表刷新。
- 修改 `frontend/src/__tests__/adminManagement.test.js`：验证权限守卫、弹窗结构、敏感字段属性和提交编排。
- 修改 `backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`：模型配置更新参数校验和普通字段规范化。
- 修改 `backend/src/test/java/com/yiqi/aitoolbench/ModelConfigAdminApiTest.java`：验证参数边界、凭证保留、加密和响应脱敏。

## 任务 1：补齐后端模型配置更新安全边界

**文件：**
- 修改：`backend/src/test/java/com/yiqi/aitoolbench/ModelConfigAdminApiTest.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java:928-950`

- [ ] **步骤 1：编写失败的 API 回归测试**

在 `ModelConfigAdminApiTest` 增加普通字段更新、空 Token 保留和非法参数测试。通过反射读取控制器内存状态，避免测试依赖响应中的敏感值：

```java
@Test
void updatesEditableFieldsAndPreservesCredentialWhenApiKeyIsBlank() throws Exception {
    var token = login();
    mvc.perform(put("/api/admin/model-configs/coze-copywriting")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"apiKey\":\"preserve-me-secret\"}"))
            .andExpect(status().isOk());
    var before = modelConfig("coze-copywriting");

    mvc.perform(put("/api/admin/model-configs/coze-copywriting")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"modelName":"  coze-copywriting-v2  ","apiBaseUrl":"https://api.coze.cn/v3",
                             "apiKey":"   ","timeoutSeconds":90,"retryCount":2}
                            """))
            .andExpect(status().isOk());

    var after = modelConfig("coze-copywriting");
    assertThat(after.modelName()).isEqualTo("coze-copywriting-v2");
    assertThat(after.apiBaseUrl()).isEqualTo("https://api.coze.cn/v3");
    assertThat(after.timeoutSeconds()).isEqualTo(90);
    assertThat(after.retryCount()).isEqualTo(2);
    assertThat(after.encryptedApiKey()).isEqualTo(before.encryptedApiKey());
    assertThat(after.encryptedApiKey()).startsWith("enc:v1:").doesNotContain("preserve-me-secret");
}

@Test
void rejectsInvalidEditableModelFields() throws Exception {
    var token = login();
    for (var body : java.util.List.of(
            "{\"modelName\":\"   \"}",
            "{\"apiBaseUrl\":\"ftp://invalid.example\"}",
            "{\"timeoutSeconds\":0}",
            "{\"timeoutSeconds\":601}",
            "{\"retryCount\":-1}",
            "{\"retryCount\":6}")) {
        mvc.perform(put("/api/admin/model-configs/coze-copywriting")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
```

补充测试辅助方法：

```java
private AiModelConfig modelConfig(String id) {
    @SuppressWarnings("unchecked")
    var configs = (Map<String, AiModelConfig>) ReflectionTestUtils.getField(controller, "modelConfigs");
    assertThat(configs).isNotNull();
    return configs.get(id);
}
```

超长 Token 测试使用 `"x".repeat(8193)` 构造 JSON，预期 400；扩展现有 `acceptsPlaintextCredentialButNeverReturnsItOrCiphertext`，在凭证更新后读取操作日志并断言不含明文或密文：

```java
var operationLogs = mvc.perform(get("/api/admin/operation-logs")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
assertThat(operationLogs).doesNotContain("supplier-test-secret", "enc:v1:");
```

- [ ] **步骤 2：运行目标测试并确认失败**

运行：

```bash
cd backend
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn' -Dtest=ModelConfigAdminApiTest test
```

预期：新增的非法 URL、超时、重试、空模型名或超长 Token 用例至少一项失败，证明现有接口缺少边界校验。

- [ ] **步骤 3：在控制器实现最小后端校验**

在 `ApiController` 增加 `java.net.URI` 导入和专用辅助方法，普通字段先计算再构造 `AiModelConfig`：

```java
private String updatedModelName(String value, String current) {
    if (value == null) return current;
    var normalized = value.trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException("模型名称不能为空");
    return normalized;
}

private String updatedApiBaseUrl(String value, String current) {
    if (value == null || value.isBlank()) return current;
    var normalized = value.trim();
    try {
        var uri = URI.create(normalized);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("API 中转地址必须是有效的 HTTP 或 HTTPS 地址");
        }
    } catch (RuntimeException ex) {
        throw new IllegalArgumentException("API 中转地址必须是有效的 HTTP 或 HTTPS 地址");
    }
    return normalized;
}

private int updatedTimeoutSeconds(Integer value, int current) {
    if (value == null) return current;
    if (value < 1 || value > 600) throw new IllegalArgumentException("超时时间必须在 1 到 600 秒之间");
    return value;
}

private int updatedRetryCount(Integer value, int current) {
    if (value == null) return current;
    if (value < 0 || value > 5) throw new IllegalArgumentException("重试次数必须在 0 到 5 次之间");
    return value;
}

private String updatedEncryptedApiKey(String value, String current) {
    if (value == null || value.isBlank()) return current;
    if (value.length() > 8192) throw new IllegalArgumentException("Token 不能超过 8192 个字符");
    return secretCrypto.encrypt(value);
}
```

构造更新记录时使用上述方法：

```java
var updated = new AiModelConfig(
        current.id(),
        isBlank(request.name()) ? current.name() : request.name(),
        isBlank(request.provider()) ? current.provider() : request.provider(),
        isBlank(request.modelType()) ? current.modelType() : request.modelType(),
        updatedModelName(request.modelName(), current.modelName()),
        updatedApiBaseUrl(request.apiBaseUrl(), current.apiBaseUrl()),
        updatedEncryptedApiKey(request.apiKey(), current.encryptedApiKey()),
        request.providerOptions() == null ? current.providerOptions() : request.providerOptions(),
        updatedTimeoutSeconds(request.timeoutSeconds(), current.timeoutSeconds()),
        updatedRetryCount(request.retryCount(), current.retryCount()),
        request.enabled() == null ? current.enabled() : request.enabled());
```

保持现有 `ProviderOptionsPolicy.rejectSensitiveKeys`、加密存储、脱敏响应和操作日志逻辑不变。

- [ ] **步骤 4：运行后端目标测试并确认通过**

运行同一步骤 2 的 Maven 命令。

预期：`ModelConfigAdminApiTest` 全部通过，0 failures、0 errors；响应和数据库断言均不出现明文或密文泄露。

- [ ] **步骤 5：提交后端安全边界**

```bash
git add backend/src/main/java/com/yiqi/aitoolbench/ApiController.java backend/src/test/java/com/yiqi/aitoolbench/ModelConfigAdminApiTest.java
git commit -m "feat: validate model credential updates"
```

## 任务 2：实现可独立测试的前端模型编辑策略

**文件：**
- 创建：`frontend/src/modelConfigEditor.js`
- 创建：`frontend/src/__tests__/modelConfigEditor.test.js`

- [ ] **步骤 1：编写失败的纯函数测试**

创建测试，覆盖回填、边界和 Token 请求规则：

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildModelConfigUpdatePayload,
  emptyModelConfigForm,
  modelConfigFormFrom,
  validateModelConfigForm
} from '../modelConfigEditor.js'

const config = {
  id: 'coze-copywriting', provider: 'coze', modelType: 'text',
  modelName: 'coze-copywriting', apiBaseUrl: 'https://api.coze.cn/v3',
  apiKeyMasked: 'pat-****1234', timeoutSeconds: 60, retryCount: 1
}

test('模型编辑表单只回填脱敏凭证且新 Token 为空', () => {
  assert.deepEqual(modelConfigFormFrom(config), {
    ...config,
    apiKey: ''
  })
})

test('空白 Token 不进入更新请求且普通字段被规范化', () => {
  const payload = buildModelConfigUpdatePayload({
    ...modelConfigFormFrom(config), modelName: '  coze-v2  ', apiKey: '   ', timeoutSeconds: '90', retryCount: '2'
  })
  assert.deepEqual(payload, {
    modelName: 'coze-v2', apiBaseUrl: 'https://api.coze.cn/v3', timeoutSeconds: 90, retryCount: 2
  })
  assert.equal('apiKey' in payload, false)
})

test('非空 Token 原样进入本次请求', () => {
  const payload = buildModelConfigUpdatePayload({ ...modelConfigFormFrom(config), apiKey: ' token-with-spaces ' })
  assert.equal(payload.apiKey, ' token-with-spaces ')
})

test('模型编辑校验返回明确中文错误', () => {
  assert.equal(validateModelConfigForm({ ...modelConfigFormFrom(config), modelName: ' ' }), '模型名称不能为空')
  assert.equal(validateModelConfigForm({ ...modelConfigFormFrom(config), apiBaseUrl: 'ftp://invalid' }), 'API 中转地址必须是有效的 HTTP 或 HTTPS 地址')
  assert.equal(validateModelConfigForm({ ...modelConfigFormFrom(config), timeoutSeconds: 0 }), '超时时间必须在 1 到 600 秒之间')
  assert.equal(validateModelConfigForm({ ...modelConfigFormFrom(config), retryCount: 6 }), '重试次数必须在 0 到 5 次之间')
  assert.equal(validateModelConfigForm({ ...modelConfigFormFrom(config), apiKey: 'x'.repeat(8193) }), 'Token 不能超过 8192 个字符')
})
```

- [ ] **步骤 2：运行纯函数测试并确认失败**

运行：

```bash
cd frontend
node --test src/__tests__/modelConfigEditor.test.js
```

预期：FAIL，报错找不到 `modelConfigEditor.js` 或导出函数。

- [ ] **步骤 3：实现最小表单策略模块**

创建 `frontend/src/modelConfigEditor.js`：

```js
export function emptyModelConfigForm() {
  return {
    id: '', provider: '', modelType: '', modelName: '', apiBaseUrl: '',
    apiKeyMasked: '', apiKey: '', timeoutSeconds: 60, retryCount: 0
  }
}

export function modelConfigFormFrom(config = {}) {
  return { ...emptyModelConfigForm(), ...config, apiKey: '' }
}

export function validateModelConfigForm(form) {
  if (!String(form.modelName ?? '').trim()) return '模型名称不能为空'
  const apiBaseUrl = String(form.apiBaseUrl ?? '').trim()
  if (apiBaseUrl) {
    try {
      const url = new URL(apiBaseUrl)
      if (!['http:', 'https:'].includes(url.protocol)) return 'API 中转地址必须是有效的 HTTP 或 HTTPS 地址'
    } catch {
      return 'API 中转地址必须是有效的 HTTP 或 HTTPS 地址'
    }
  }
  const timeout = Number(form.timeoutSeconds)
  if (!Number.isInteger(timeout) || timeout < 1 || timeout > 600) return '超时时间必须在 1 到 600 秒之间'
  const retry = Number(form.retryCount)
  if (!Number.isInteger(retry) || retry < 0 || retry > 5) return '重试次数必须在 0 到 5 次之间'
  if (String(form.apiKey ?? '').length > 8192) return 'Token 不能超过 8192 个字符'
  return ''
}

export function buildModelConfigUpdatePayload(form) {
  const payload = {
    modelName: String(form.modelName).trim(),
    apiBaseUrl: String(form.apiBaseUrl ?? '').trim(),
    timeoutSeconds: Number(form.timeoutSeconds),
    retryCount: Number(form.retryCount)
  }
  const apiKey = String(form.apiKey ?? '')
  if (apiKey.trim()) payload.apiKey = apiKey
  return payload
}
```

- [ ] **步骤 4：运行纯函数测试并确认通过**

运行同一步骤 2 的命令。

预期：全部测试通过，0 failures。

- [ ] **步骤 5：提交前端表单策略**

```bash
git add frontend/src/modelConfigEditor.js frontend/src/__tests__/modelConfigEditor.test.js
git commit -m "feat: add model config editor policy"
```

## 任务 3：接入模型配置编辑弹窗

**文件：**
- 修改：`frontend/src/__tests__/adminManagement.test.js`
- 修改：`frontend/src/App.vue:612-634`
- 修改：`frontend/src/App.vue:912-1005`
- 修改：`frontend/src/App.vue:2381-2394`

- [ ] **步骤 1：为权限、弹窗和提交锁编写失败的结构测试**

在 `adminManagement.test.js` 增加：

```js
test('模型配置用写权限保护编辑入口和居中弹窗', () => {
  assert.match(template, /<button\b[^>]*@click="openModelConfigEditor\(config\)"[^>]*>编辑<\/button>/)
  assert.match(template, /<AppDialog\b[^>]*v-if="canWriteAdminSection\(currentUser\.permissions, 'models'\)"[^>]*:open="modelConfigDialogVisible"[^>]*:close-disabled="modelConfigSaving"/)
  assert.match(template, /type="password"[^>]*autocomplete="off"[^>]*:disabled="modelConfigSaving"/)
  assert.match(template, /当前 Token[\s\S]*modelConfigForm\.apiKeyMasked/)
})

test('模型配置保存具有前置校验、目标锁定和 finally 解锁', () => {
  const source = functionSource('submitModelConfigEdit')
  assert.match(source, /^function submitModelConfigEdit\(\) \{\s*if \(modelConfigSaving\.value\) return/)
  const validation = source.indexOf('validateModelConfigForm(modelConfigForm.value)')
  const target = source.indexOf('const targetConfigId = modelConfigForm.value.id')
  const locked = source.indexOf('modelConfigSaving.value = true')
  const request = source.indexOf('await updateModelConfig(')
  assert.ok(validation >= 0 && validation < target && target < locked && locked < request)
  assert.match(source, /finally \{\s*modelConfigSaving\.value = false\s*\}/)
})

test('模型配置弹窗关闭和成功保存会清空敏感表单', () => {
  assert.match(functionSource('resetModelConfigEditor'), /modelConfigForm\.value = emptyModelConfigForm\(\)/)
  assert.match(functionSource('closeModelConfigEditor'), /if \(modelConfigSaving\.value\) return/)
  assert.match(functionSource('closeModelConfigEditor'), /resetModelConfigEditor\(\)/)
  assert.match(functionSource('syncAdminEditorDialogs'), /if \(!modelConfigSaving\.value && \(mode !== 'admin' \|\| section !== 'models'\)\) resetModelConfigEditor\(\)/)
})
```

- [ ] **步骤 2：运行管理界面目标测试并确认失败**

运行：

```bash
cd frontend
node --test src/__tests__/adminManagement.test.js
```

预期：FAIL，缺少编辑按钮、模型弹窗及其状态函数。

- [ ] **步骤 3：在列表增加编辑入口并添加弹窗**

在模型操作列把“编辑”放在启停按钮之前：

```vue
<button class="icon-btn" type="button" @click="openModelConfigEditor(config)">编辑</button>
<button class="icon-btn" type="button" @click="toggleModelConfig(config)">{{ config.enabled ? '停用' : '启用' }}</button>
```

在后台其他管理弹窗附近新增：

```vue
<AppDialog
  v-if="canWriteAdminSection(currentUser.permissions, 'models')"
  :open="modelConfigDialogVisible"
  title="编辑模型配置"
  description="更新模型连接信息。新 Token 留空时保留当前凭证。"
  :close-disabled="modelConfigSaving"
  :close-on-backdrop="true"
  @close="closeModelConfigEditor"
>
  <div class="admin-dialog-form">
    <label class="drawer-field">配置 ID<input :value="modelConfigForm.id" disabled /></label>
    <label class="drawer-field">供应商<input :value="modelConfigForm.provider" disabled /></label>
    <label class="drawer-field">模型类型<input :value="modelConfigForm.modelType" disabled /></label>
    <label class="drawer-field">模型名称<input v-model="modelConfigForm.modelName" :disabled="modelConfigSaving" /></label>
    <label class="drawer-field">API 中转地址<input v-model="modelConfigForm.apiBaseUrl" type="url" :disabled="modelConfigSaving" /></label>
    <label class="drawer-field">当前 Token<input :value="modelConfigForm.apiKeyMasked" disabled /></label>
    <label class="drawer-field">新 Token
      <input v-model="modelConfigForm.apiKey" type="password" autocomplete="off" spellcheck="false"
        :disabled="modelConfigSaving" placeholder="留空则保留现有 Token" />
    </label>
    <label class="drawer-field">超时时间（秒）<input v-model.number="modelConfigForm.timeoutSeconds" type="number" min="1" max="600" :disabled="modelConfigSaving" /></label>
    <label class="drawer-field">重试次数<input v-model.number="modelConfigForm.retryCount" type="number" min="0" max="5" :disabled="modelConfigSaving" /></label>
    <div v-if="modelConfigMessage" class="check-result">{{ modelConfigMessage }}</div>
  </div>
  <template #footer>
    <button class="btn-secondary" type="button" :disabled="modelConfigSaving" @click="closeModelConfigEditor">取消</button>
    <button class="btn-primary" type="button" :disabled="modelConfigSaving" @click="submitModelConfigEdit">{{ modelConfigSaving ? '保存中...' : '保存修改' }}</button>
  </template>
</AppDialog>
```

- [ ] **步骤 4：接入状态、权限守卫和提交编排**

导入任务 2 的函数并新增状态：

```js
import { buildModelConfigUpdatePayload, emptyModelConfigForm, modelConfigFormFrom, validateModelConfigForm } from './modelConfigEditor'

const modelConfigDialogVisible = ref(false)
const modelConfigSaving = ref(false)
const modelConfigMessage = ref('')
const modelConfigForm = ref(emptyModelConfigForm())
```

实现弹窗生命周期：

```js
function openModelConfigEditor(config) {
  if (!canWriteAdminSection(currentUser.value.permissions, 'models')) return
  modelConfigMessage.value = ''
  modelConfigForm.value = modelConfigFormFrom(config)
  modelConfigDialogVisible.value = true
}

function resetModelConfigEditor() {
  modelConfigDialogVisible.value = false
  modelConfigMessage.value = ''
  modelConfigForm.value = emptyModelConfigForm()
}

function closeModelConfigEditor() {
  if (modelConfigSaving.value) return
  resetModelConfigEditor()
}

async function submitModelConfigEdit() {
  if (modelConfigSaving.value) return
  const validationMessage = validateModelConfigForm(modelConfigForm.value)
  if (validationMessage) {
    modelConfigMessage.value = validationMessage
    return
  }
  const targetConfigId = modelConfigForm.value.id
  const payload = buildModelConfigUpdatePayload(modelConfigForm.value)
  modelConfigSaving.value = true
  let updated = null
  try {
    updated = await updateModelConfig(targetConfigId, payload)
    modelConfigs.value = modelConfigs.value.map((item) => item.id === targetConfigId ? updated : item)
    adminPages.value.models.items = adminPages.value.models.items.map((item) => item.id === targetConfigId ? updated : item)
  } catch (error) {
    modelConfigMessage.value = readableError(error, '模型配置保存失败')
  } finally {
    modelConfigSaving.value = false
  }
  if (!updated) return
  resetModelConfigEditor()
  showNotice('模型连接信息已更新', '保存成功', 'success')
  try {
    await loadAdminPage('models', adminPages.value.models.page)
  } catch (error) {
    showNotice(readableError(error, '模型配置已保存，但列表刷新失败'), '刷新失败')
  }
}
```

在现有 `syncAdminEditorDialogs` 中加入精确清理条件。保存期间保留遮罩弹窗；请求结束后成功分支关闭，失败分支继续展示错误：

```js
function syncAdminEditorDialogs(mode = routeMode.value, section = adminSection.value) {
  if (mode !== 'admin' || section !== 'organization') organizationDialogVisible.value = false
  if (mode !== 'admin' || section !== 'roles') roleDialogVisible.value = false
  if (!modelConfigSaving.value && (mode !== 'admin' || section !== 'models')) resetModelConfigEditor()
}
```

- [ ] **步骤 5：运行前端目标测试并确认通过**

运行：

```bash
cd frontend
node --test src/__tests__/modelConfigEditor.test.js src/__tests__/adminManagement.test.js
```

预期：两组测试全部通过，0 failures。

- [ ] **步骤 6：提交模型编辑弹窗**

```bash
git add frontend/src/App.vue frontend/src/__tests__/adminManagement.test.js
git commit -m "feat: add model credential edit dialog"
```

## 任务 4：完整回归、构建与安全检查

**文件：**
- 验证：`frontend/src/**/*.js`
- 验证：`frontend/src/App.vue`
- 验证：`backend/src/main/**/*.java`
- 验证：`backend/src/test/**/*.java`

- [ ] **步骤 1：运行前端完整测试**

运行：

```bash
cd frontend
npm test
```

预期：全部测试通过，0 failures。

- [ ] **步骤 2：运行前端生产构建**

运行：

```bash
cd frontend
npm run build
```

预期：Vite 构建退出码 0，`frontend/dist/index.html` 和哈希资源文件生成。

- [ ] **步骤 3：运行后端完整测试与打包**

运行：

```bash
cd backend
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn' package
```

预期：全部测试 0 failures、0 errors，生成 `backend/target/ai-toolbench-0.1.0.jar`。

- [ ] **步骤 4：执行敏感信息与差异检查**

运行：

```bash
rg -n "supplier-test-secret|token-with-spaces" frontend/src backend/src/main --glob '!**/test/**'
git diff --check
git status --short
```

预期：生产代码不包含测试凭证；`git diff --check` 无输出；状态只包含本计划范围内的预期文件。

- [ ] **步骤 5：检查需求清单**

逐项确认：

- 编辑入口受 `admin:model:write` 控制。
- 普通字段可回填和修改。
- 当前 Token 仅脱敏展示，新 Token 不回填。
- 空 Token 不覆盖，非空 Token 加密保存。
- URL、超时、重试和长度边界前后端一致。
- 保存锁、失败保留、成功清空和当前页刷新均已覆盖。
- 未改动模型调用、供应商适配器和业务 UI。

- [ ] **步骤 6：提交必要的测试修正**

如果完整回归只产生本任务所需的测试或格式修正，单独提交：

```bash
git add frontend/src/__tests__ backend/src/test
git commit -m "test: cover model credential editing"
```

如果没有新增改动，不创建空提交。

## 实施后的交付边界

完成上述任务后，先提交代码审查和测试证据。生产部署会替换线上前端资源和后端 JAR，属于独立外部变更，必须在用户明确确认部署后执行。
