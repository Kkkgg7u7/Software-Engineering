<script setup lang="ts">
import {ref,computed} from 'vue'
import {useRouter} from 'vue-router'
import {useRoomStore} from '@/stores/room'
const props=defineProps<{roomCode:string}>(),router=useRouter(),room=useRoomStore(),password=ref(''),busy=ref(false),error=ref('')
const code=computed(()=>props.roomCode.toUpperCase()),valid=computed(()=>/^[A-Z0-9]{6}$/.test(code.value))
async function join(){if(busy.value)return;busy.value=true;error.value='';try{const result=await room.joinRoom(code.value,password.value||undefined);password.value='';await router.replace({name:'room',params:{roomCode:result.roomCode}})}catch(e){error.value=e instanceof Error?e.message:'加入失败，请重试'}finally{busy.value=false}}
</script>
<template><section class="join-card ww-panel ww-panel--pad"><span class="join-moon">☾</span><h1>朋友邀你一起开局</h1><p>房间码 <strong>{{code}}</strong></p><el-alert v-if="!valid" title="房间码格式不正确" type="error" :closable="false"/><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-input v-model="password" type="password" show-password maxlength="32" placeholder="房间密码（无密码可留空）" :disabled="busy" @keyup.enter="join"/><el-button type="primary" :loading="busy" :disabled="!valid" @click="join">加入房间</el-button><el-button text @click="router.push('/lobby')">返回狼人杀大厅</el-button><small>房间可能已满、已开局或已解散；邀请不会跳过房间密码验证。</small></section></template>
<style scoped>.join-card{max-width:440px;margin:60px auto;text-align:center;display:flex;flex-direction:column;gap:20px}.join-moon{font-size:60px;color:var(--ww-moon)}h1{font-size:24px;margin:0}.join-card p{font-size:14px;color:var(--ww-text-dim)}strong{letter-spacing:3px;color:var(--ww-moon);margin-left:8px}.join-card small{font-size:11px;line-height:1.9;color:var(--ww-text-dim)}.el-button+.el-button{margin-left:0}@media(max-width:680px){.join-card{margin:24px auto}}</style>
