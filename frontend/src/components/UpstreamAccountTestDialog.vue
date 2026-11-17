<template>
  <AppDialog :open="!!account" :title="`测试账号：${account?.name || ''}`" :close-disabled="busy" @close="$emit('close')">
    <label class="drawer-field">测试模型（可选）<input v-model="model" :disabled="busy" maxlength="200" placeholder="输入该账号支持的模型名称" /></label>
    <p class="relay-caption">留空使用已启用的同平台中转文本模型；测试会向上游发送一次真实请求。</p>
    <p v-if="message" :class="success?'check-result':'relay-error'" role="status">{{ message }}</p>
    <template #footer><button class="btn-secondary" :disabled="busy" @click="$emit('close')">关闭</button><button class="btn-primary" :disabled="busy" @click="test">{{busy?'测试中…':'开始测试'}}</button></template>
  </AppDialog>
</template>
<script setup>
import {ref,watch} from 'vue'
import AppDialog from './AppDialog.vue'
import {postJson} from '../api.js'
import {readableRelayError} from '../relayView.js'
const props=defineProps({account:{type:Object,default:null}})
const emit=defineEmits(['close','tested'])
const model=ref(''),message=ref(''),busy=ref(false),success=ref(false)
watch(()=>props.account,()=>{model.value='';message.value='';success.value=false})
async function test(){if(busy.value||!props.account)return;busy.value=true;message.value='';success.value=false
 try{await postJson(`/api/admin/upstream-accounts/${props.account.id}/test`,{model:model.value.trim()||undefined});success.value=true;message.value='账号连通性测试通过';emit('tested')}
 catch(e){message.value=readableRelayError(e)}finally{busy.value=false}
}
</script>
