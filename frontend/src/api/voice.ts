import { request } from './request'
import type { VoiceSnapshot, VoiceJoin, VoiceSignal } from '@/voice/VoiceClient'
export const voiceApi = {
 state: (code:string)=>request<VoiceSnapshot>({url:`/rooms/${code}/voice`,method:'GET'}),
 join: (code:string,clientId:string)=>request<VoiceJoin>({url:`/rooms/${code}/voice/join`,method:'POST',data:{clientId}}),
 heartbeat: (code:string,sessionId:string,muted:boolean)=>request<VoiceSnapshot>({url:`/rooms/${code}/voice/heartbeat`,method:'POST',data:{sessionId,muted}}),
 leave: (code:string,sessionId:string)=>request({url:`/rooms/${code}/voice/leave`,method:'POST',data:{sessionId}}),
 signal: (code:string,data:VoiceSignal)=>request({url:`/rooms/${code}/voice/signal`,method:'POST',data})
}
