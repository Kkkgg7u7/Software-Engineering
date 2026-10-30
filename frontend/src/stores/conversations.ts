import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { conversationApi } from '@/api/conversation'
import type { Conversation } from '@/types/conversation'
import ws from '@/websocket/stomp'

export const useConversationsStore = defineStore('conversations',()=>{
 const items=ref<Conversation[]>([]), error=ref(''), loading=ref(false)
 let epoch=0, sequence=0, off:(()=>void)|undefined, offStatus:(()=>void)|undefined
 const unread=computed(()=>items.value.reduce((sum,c)=>sum+c.unreadCount,0))
 async function refresh(){const run=++sequence, generation=epoch;loading.value=true;try{let page=1;let result=await conversationApi.list(page);const rows=[...result.items];while(rows.length<result.total&&result.items.length){result=await conversationApi.list(++page);if(generation!==epoch||run!==sequence)return;rows.push(...result.items)}if(generation===epoch&&run===sequence){items.value=rows;error.value=''}}catch(e){if(generation===epoch&&run===sequence)error.value=e instanceof Error?e.message:'会话加载失败'}finally{if(generation===epoch&&run===sequence)loading.value=false}}
 function start(){reset();off=ws.subscribeWith('/user/queue/social',e=>{if(['CHAT_MESSAGE','CONVERSATION_UPDATE','FRIEND_UPDATE'].includes(e.type))void refresh()});offStatus=ws.onStatus(connected=>{if(connected)void refresh()});void refresh()}
 function reset(){epoch++;sequence++;off?.();offStatus?.();off=undefined;offStatus=undefined;items.value=[];error.value='';loading.value=false}
 return {items,error,loading,unread,refresh,start,reset}
})
