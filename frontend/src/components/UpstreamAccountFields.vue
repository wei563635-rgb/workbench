<template>
  <div class="upstream-prototype-fields">
    <label class="drawer-field">账号标识 / 订阅邮箱<input v-model="form.name" :disabled="disabled" maxlength="100" placeholder="例如：openai-team-01@example.com" /></label>
    <div class="upstream-fields-two">
      <div class="drawer-field"><span>上游平台类型</span><UiSelect aria-label="上游平台类型" v-model="form.platform" :options="platforms" :disabled="disabled || editing" /></div>
      <div class="drawer-field"><span>调度优先级</span><UiSelect aria-label="调度优先级" v-model="form.priority" :options="priorities" :disabled="disabled" /></div>
    </div>
    <div class="upstream-fields-credentials">
      <div class="drawer-field"><span>接入凭证方式</span><UiSelect aria-label="接入凭证方式" v-model="form.type" :options="[{label:'OAuth 订阅授权',value:'oauth'},{label:'官方 API Key',value:'apikey'}]" :disabled="disabled || editing" /></div>
      <label v-if="form.type==='apikey'" class="drawer-field">API Key 密钥<input v-model="form.apiKey" type="password" autocomplete="new-password" :disabled="disabled" :placeholder="editing?'留空保持原密钥不变':'请输入该平台的官方 API Key'" /></label>
      <div v-else class="drawer-field"><span>订阅授权凭证</span><div class="upstream-credential-hint">{{ editing?'已授权 · 使用列表中的“刷新凭据”续期':'使用下方按钮获取授权链接，完成登录后返回保存' }}</div></div>
    </div>
    <div class="upstream-fields-three">
      <label class="drawer-field">最大并发槽位<input v-model.number="form.concurrency" type="number" min="1" max="100" :disabled="disabled" /></label>
      <label class="drawer-field">5h 自动熔断水位 (%)<input v-if="supportsWindow(form)" v-model.number="form.threshold5h" type="number" min="1" max="100" :disabled="disabled" /><input v-else value="不适用" disabled /></label>
      <label class="drawer-field">7Day 自动熔断水位 (%)<input v-if="supportsWindow(form)" v-model.number="form.threshold7d" type="number" min="1" max="100" :disabled="disabled" /><input v-else value="不适用" disabled /></label>
    </div>
    <div class="upstream-window-note">{{ supportsWindow(form)?'自动熔断说明：任一订阅窗口消耗率达到设定水位时，暂停向该账号分发新请求，窗口重置后自动恢复。':'此类账号没有可用于本规则的 5h / 7Day 订阅窗口；员工 Token 限额和并发规则仍正常生效。' }}</div>
    <div class="upstream-fields-two">
      <div class="drawer-field"><span>代理出口</span><UiSelect aria-label="代理出口" v-model="form.proxyId" :options="[{label:'直连',value:0},...proxies.filter(p=>p.value!==0)]" :disabled="disabled" /></div>
      <label class="drawer-field">备注（可选）<input v-model="form.notes" maxlength="1000" :disabled="disabled" placeholder="用途或账号说明" /></label>
    </div>
    <div v-if="!Number(form.proxyId)" class="upstream-window-note" role="note">当前选择直连：授权和模型调用由服务器访问上游，服务器必须能直接连接 OpenAI / Google；若出现连接超时，请选择可用代理后重新生成授权链接。</div>
    <div v-if="!editing && form.platform==='gemini' && form.type==='oauth'" class="upstream-fields-two">
      <div class="drawer-field"><span>Google 订阅类型</span><UiSelect aria-label="Google 订阅类型" v-model="form.oauthType" :options="[{label:'Google One / Gemini 订阅',value:'google_one'},{label:'Gemini Code Assist',value:'code_assist'}]" :disabled="disabled" /></div>
      <label class="drawer-field">Google Project ID（可选）<input v-model="form.projectId" :disabled="disabled" placeholder="按授权账号要求填写" /></label>
    </div>
  </div>
</template>
<script setup>
import UiSelect from './UiSelect.vue'
import {platforms,priorities,supportsWindow} from '../upstreamAccountForm.js'
const form=defineModel({required:true})
defineProps({editing:Boolean,disabled:Boolean,proxies:{type:Array,default:()=>[]}})
</script>
<style>
.app-dialog-shell.size-upstream{width:min(540px,92vw);border-radius:24px;box-shadow:0 24px 60px rgba(0,0,0,.18)}.size-upstream .app-dialog-header{padding:22px 28px}.size-upstream .app-dialog-header h3{font-size:18px}.size-upstream .app-dialog-body{padding:24px 28px}.size-upstream .app-dialog-footer{padding:18px 28px;background:#f9fafb}.size-upstream .drawer-field{font-weight:600;color:#374151}.size-upstream .drawer-field input{font-weight:400;border-radius:10px;padding:10px 14px;font-size:14px}

.upstream-prototype-fields{display:grid;gap:18px;grid-column:1 / -1}.upstream-prototype-fields .drawer-field{margin:0;min-width:0;font-size:13px;gap:8px}.upstream-fields-two,.upstream-fields-three,.upstream-fields-credentials{display:grid;gap:14px;grid-template-columns:1fr 1fr}.upstream-fields-three{grid-template-columns:1fr 1fr 1fr;gap:12px}.upstream-fields-credentials{grid-template-columns:1fr 1.5fr}.upstream-window-note,.upstream-credential-hint{font-size:12px;color:#6b7280;background:#f9fafb;padding:10px 14px;border-radius:10px;border:1px solid #e5e7eb;line-height:1.7}.upstream-credential-hint{min-height:42px;box-sizing:border-box}.upstream-prototype-fields input:disabled{color:#9ca3af;background:#f9fafb}@media(max-width:640px){.upstream-fields-two,.upstream-fields-three,.upstream-fields-credentials{grid-template-columns:1fr}}
</style>
