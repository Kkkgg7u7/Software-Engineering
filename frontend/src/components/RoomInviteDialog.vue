<script setup lang="ts">
import {ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {socialApi} from '@/api/social'
import {roomApi} from '@/api/room'
import {useConversationsStore} from '@/stores/conversations'
import type {PublicUser} from '@/types/social'
const props=defineProps<{modelValue:boolean;roomCode:string}>(),emit=defineEmits<{ 'update:modelValue':[boolean] }>(),store=useConversationsStore()
const friends=ref<PublicUser[]>([]),mode=ref<'friend'|'group'>('friend'),target=ref<number>(),sending=ref(false),loading=ref(false),error=ref('');let key='',alive=0
function targetChanged(){key=crypto.randomUUID()}
function resetKey(){key=crypto.randomUUID();target.value=undefined}
watch(()=>props.modelValue,async open=>{const run=++alive;if(!open)return;key=crypto.randomUUID();target.value=undefined;error.value='';loading.value=true;try{await store.refresh();let page=1,result=await socialApi.friends(page);const rows=[...result.items];while(rows.length<result.total&&result.items.length){result=await socialApi.friends(++page);rows.push(...result.items)}if(run===alive)friends.value=rows}catch(e){if(run===alive)error.value=e instanceof Error?e.message:'加载失败'}finally{if(run===alive)loading.value=false}})
async function send(){if(!target.value||sending.value)return;sending.value=true;error.value='';try{await roomApi.invite(props.roomCode,{clientMessageId:key,...(mode.value==='friend'?{targetUserId:target.value}:{conversationId:target.value})});ElMessage.success('邀请卡已发送');emit('update:modelValue',false);await store.refresh()}catch(e){error.value=e instanceof Error?e.message:'邀请失败，可重试'}finally{sending.value=false}}
</script>
<template><el-dialog :model-value="modelValue" title="邀请一起开局" width="min(440px,94vw)" @update:model-value="emit('update:modelValue',$event)"><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-radio-group v-model="mode" :disabled="sending" @change="resetKey"><el-radio-button value="friend">发送给好友</el-radio-button><el-radio-button value="group">发送到群聊</el-radio-button></el-radio-group><el-select v-model="target" class="invite-select" :loading="loading" :disabled="sending" :placeholder="mode==='friend'?'选择好友':'选择你所在的群聊'" @change="targetChanged"><template v-if="mode==='friend'"><el-option v-for="friend in friends" :key="friend.id" :label="friend.nickname" :value="friend.id"/></template><template v-else><el-option v-for="group in store.items.filter(c=>c.type==='GROUP')" :key="group.id" :label="group.title" :value="group.id"/></template></el-select><p class="invite-hint">邀请会作为消息卡片发送。朋友点击后仍需通过房间人数、状态和密码验证。</p><template #footer><el-button @click="emit('update:modelValue',false)">取消</el-button><el-button type="primary" :loading="sending" :disabled="!target||loading" @click="send">发送邀请</el-button></template></el-dialog></template>
<style scoped>.invite-select{width:100%;margin-top:20px}.invite-hint{font-size:12px;color:var(--ww-text-dim);line-height:1.9;margin-top:20px}</style>
