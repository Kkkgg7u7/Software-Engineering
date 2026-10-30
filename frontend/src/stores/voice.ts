import { defineStore } from 'pinia'
import { ref } from 'vue'
import ws from '@/websocket/stomp'
import { voiceApi } from '@/api/voice'
import { VoiceClient, type VoiceState } from '@/voice/VoiceClient'
export const useVoiceStore=defineStore('voice',()=>{
 const state=ref<VoiceState>({joined:false,busy:false,muted:false,status:'未加入语音',error:'',blockedPlayback:false,members:[],connected:0})
 const client=new VoiceClient(voiceApi,()=>{state.value={...client.state,members:[...client.state.members]}})
 ws.on('/user/queue/voice',message=>{if(message.type==='VOICE_SIGNAL')client.receive(message.data);else if(message.type==='VOICE_MEMBERS')client.reconcile(message.data);else if(message.type==='VOICE_CLOSED')client.closed(message.data)})
 ws.onStatus((connected,reconnected)=>{if(connected&&reconnected&&state.value.joined)void client.reconnect()})
 if(typeof window!=='undefined')window.addEventListener('pagehide',()=>client.leave())
 return {state,join:(code:string,uid:number)=>client.join(code,uid),leave:()=>client.leave(),mute:()=>client.mute(),playback:()=>client.playback(),reconnect:()=>client.reconnect()}
})
