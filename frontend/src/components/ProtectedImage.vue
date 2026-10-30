<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import http from '@/api/request'
const props=defineProps<{mediaId:string}>()
const src=ref(''),error=ref('');let generation=0
async function load(){const run=++generation;if(src.value)URL.revokeObjectURL(src.value);src.value='';error.value='';try{const result=await http.get(`/media/${props.mediaId}/content`,{responseType:'blob'});const blob=result.data as Blob;if(!blob.type.startsWith('image/'))throw new Error('图片暂不可读取');if(run===generation)src.value=URL.createObjectURL(blob)}catch(e){if(run===generation)error.value=e instanceof Error?e.message:'图片加载失败'}}
watch(()=>props.mediaId,load,{immediate:true})
onBeforeUnmount(()=>{generation++;if(src.value)URL.revokeObjectURL(src.value)})
</script>
<template><a v-if="src" :href="src" target="_blank" rel="noopener" aria-label="查看原图"><img :src="src" alt="聊天图片" class="chat-image" /></a><button v-else-if="error" class="image-error" @click="load">{{error}} · 重试</button><span v-else>图片加载中…</span></template>
<style scoped>.chat-image{max-width:min(280px,100%);max-height:260px;object-fit:contain;border-radius:12px;display:block}.image-error{color:inherit;background:transparent;border:1px solid currentColor;border-radius:8px;padding:10px;cursor:pointer}</style>
