<template>
  <AppDialog :open="!!account" size="upstream" :title="`编辑账号：${account?.name || ''}`" :close-disabled="saving" @close="$emit('close')">
    <p v-if="error" class="relay-error" role="alert">{{ error }}</p>
    <UpstreamAccountFields v-if="account" v-model="form" :proxies="proxies" :disabled="saving" editing />
    <template #footer><button type="button" class="btn-secondary" :disabled="saving" @click="$emit('close')">取消</button><button type="button" class="btn-primary" :disabled="saving" @click="save">{{saving?'保存中…':'保存并同步至调度池'}}</button></template>
  </AppDialog>
</template>
<script setup>
import {ref,watch} from 'vue'
import AppDialog from './AppDialog.vue'
import UpstreamAccountFields from './UpstreamAccountFields.vue'
import {validateUpstreamForm} from '../upstreamAccountForm.js'
import {putJson} from '../api.js'
import {readableRelayError} from '../relayView.js'
const props=defineProps({account:{type:Object,default:null},proxies:{type:Array,default:()=>[]}})
const emit=defineEmits(['close','saved'])
const saving=ref(false),error=ref(''),form=ref({})
watch(()=>props.account,a=>{error.value='';form.value=a?{name:a.name,platform:a.platform,type:a.type,apiKey:'',priority:a.priority??2,notes:a.notes||'',proxyId:a.proxyId||0,concurrency:a.concurrency||1,threshold5h:a.usage?.workbenchThreshold5h??90,threshold7d:a.usage?.workbenchThreshold7d??95}:{}},{immediate:true})
async function save(){
 if(saving.value)return
 const id=props.account?.id;if(!id)return
 error.value=validateUpstreamForm(form.value,true);if(error.value)return
 saving.value=true
 try{await putJson(`/api/admin/upstream-accounts/${id}`,{...form.value,proxyId:Number(form.value.proxyId)});form.value.apiKey='';emit('saved');emit('close')}
 catch(e){error.value=readableRelayError(e)}finally{saving.value=false}
}
</script>
