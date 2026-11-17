<template>
  <div class="usage-image">
    <button type="button" class="image-preview" :aria-label="`放大查看${alt}`" @click="open"><img :src="src" :alt="alt" loading="lazy" referrerpolicy="no-referrer" @error="failed=true" /><span>{{ failed ? '图片无法预览' : '点击放大' }}</span></button>
    <dialog ref="dialog" class="image-dialog" @click.self="close" @keydown.stop>
      <header><span>{{ alt }}</span><div><a :href="src" :download="inlineName" :target="inlineName ? undefined : '_blank'" rel="noopener noreferrer">{{ inlineName ? '下载图片' : '打开原图' }}</a><button type="button" aria-label="关闭图片预览" @click="close">×</button></div></header>
      <img :src="src" :alt="alt" referrerpolicy="no-referrer" />
    </dialog>
  </div>
</template>
<script setup>
import { ref, computed } from 'vue'
const props=defineProps({src:{type:String,required:true},alt:{type:String,default:'AI 图片结果'}})
const dialog=ref(null),failed=ref(false)
const inlineName=computed(()=>{const match=props.src.match(/^data:image\/(png|jpeg|gif|webp);base64,/);return match?`AI图片.${match[1]==='jpeg'?'jpg':match[1]}`:null})
function open(){if(!failed.value)dialog.value?.showModal()}
function close(){dialog.value?.close()}
</script>
<style scoped>
.usage-image{min-width:0}.image-preview{position:relative;display:block;width:180px;height:180px;max-width:100%;padding:0;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;overflow:hidden;cursor:zoom-in}.image-preview img{display:block;width:100%;height:100%;object-fit:contain}.image-preview span{position:absolute;bottom:0;left:0;right:0;background:#334155d9;color:white;padding:5px;font-size:11px}.image-dialog{box-sizing:border-box;width:fit-content;max-width:94vw;max-height:94vh;padding:0;border:0;border-radius:10px;background:white;color:#334155;overflow:auto}.image-dialog::backdrop{background:#0f172ab3}.image-dialog header{position:sticky;top:0;display:flex;align-items:center;justify-content:space-between;gap:24px;padding:12px 16px;background:white;font-size:13px}.image-dialog header div{display:flex;align-items:center;gap:18px}.image-dialog a{color:#4f46e5}.image-dialog button{border:0;background:#f1f5f9;font-size:24px;width:32px;height:32px;border-radius:6px;cursor:pointer}.image-dialog>img{display:block;max-width:90vw;max-height:80vh;width:auto;height:auto;object-fit:contain;margin:auto}.image-preview:focus-visible,.image-dialog button:focus-visible,.image-dialog a:focus-visible{outline:2px solid #4f46e5;outline-offset:3px}@media(max-width:600px){.image-preview{width:140px;height:140px}}
</style>
