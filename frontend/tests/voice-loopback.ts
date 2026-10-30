import { VoiceClient, type VoiceJoin, type VoiceSnapshot, type VoiceSignal } from '../src/voice/VoiceClient'
const output=document.querySelector('#result')!,button=document.querySelector<HTMLButtonElement>('#run')!
button.onclick=async()=>{
 button.disabled=true;const lines:string[]=[];const log=(s:string)=>{lines.push(s);output.textContent=lines.join('\n')}
 const contexts:AudioContext[]=[],streams:MediaStream[]=[],connections:RTCPeerConnection[]=[],audios:HTMLAudioElement[]=[],clients=new Map<number,VoiceClient>(),members=new Map<number,{userId:number;sessionId:string;nickname:string;muted:boolean}>()
 const assert=(condition:boolean,text:string)=>{if(!condition)throw new Error(text);log('通过：'+text)}
 const snapshot=():VoiceSnapshot=>({roomCode:'TESTAA',enabled:true,members:[...members.values()]})
 function environment(uid:number){return {
  async capture(){const context=new AudioContext();contexts.push(context);await context.resume();const oscillator=context.createOscillator();oscillator.frequency.value=uid===1?440:660;const destination=context.createMediaStreamDestination();oscillator.connect(destination);oscillator.start();streams.push(destination.stream);return destination.stream},
  connection(config:RTCConfiguration){const pc=new RTCPeerConnection(config);connections.push(pc);return pc},
  audio(){const audio=new Audio();audio.muted=true;audios.push(audio);return audio},id:()=>crypto.randomUUID()
 }}
 function transport(uid:number){return {
  async join(_code:string,_client:string):Promise<VoiceJoin>{const sessionId=crypto.randomUUID();members.set(uid,{userId:uid,sessionId,nickname:'合成音频 '+uid,muted:false});setTimeout(()=>clients.forEach(c=>c.reconcile(snapshot())),0);return {...snapshot(),sessionId,iceServers:[],leaseSeconds:30}},
  async heartbeat(_code:string,session:string,muted:boolean){const m=members.get(uid);if(m?.sessionId===session)m.muted=muted;return snapshot()},
  async leave(_code:string,session:string){if(members.get(uid)?.sessionId===session)members.delete(uid);setTimeout(()=>clients.forEach(c=>c.reconcile(snapshot())),0)},
  async signal(_code:string,data:VoiceSignal){setTimeout(()=>clients.get(data.targetUserId)?.receive({roomCode:'TESTAA',fromUserId:uid,fromSessionId:data.sessionId,targetSessionId:data.targetSessionId,type:data.type,payload:data.payload}),0)}
 }}
 const until=async(check:()=>Promise<boolean>|boolean)=>{const start=Date.now();while(!await check()){if(Date.now()-start>15000)throw new Error('连接或音频接收超时');await new Promise(r=>setTimeout(r,100))}}
 try{
  log('正在创建合成音轨并进行 SDP / ICE 协商…')
  const a=new VoiceClient(transport(1),()=>{},environment(1)),b=new VoiceClient(transport(2),()=>{},environment(2));clients.set(1,a);clients.set(2,b)
  await a.join('TESTAA',1);await b.join('TESTAA',2)
  await until(()=>connections.length===2&&connections.every(pc=>pc.connectionState==='connected'))
  assert(connections.length===2,'两端真实 WebRTC 音频连接已建立')
  let packets=0;await until(async()=>{packets=0;for(const pc of connections){const stats=await pc.getStats();stats.forEach(s=>{if(s.type==='inbound-rtp'&&s.kind==='audio')packets+=s.packetsReceived??0})}return packets>0})
  assert(packets>0,'收到真实 RTP 音频数据包：'+packets)
  a.mute();assert(!streams[0]!.getAudioTracks()[0]!.enabled,'静音停止本地音轨发送');a.mute();assert(streams[0]!.getAudioTracks()[0]!.enabled,'取消静音恢复本地音轨')
  a.leave();b.leave();assert(streams.every(s=>s.getTracks().every(t=>t.readyState==='ended')),'退出后全部音轨结束');assert(connections.every(pc=>pc.connectionState==='closed'),'退出后全部 PeerConnection 关闭');assert(audios.every(audio=>audio.srcObject===null),'退出后清空远端音频引用')
  const denied=new VoiceClient(transport(3),()=>{},{...environment(3),capture:async()=>{throw new DOMException('denied','NotAllowedError')}});await denied.join('TESTAA',3);assert(!denied.state.joined&&denied.state.error.includes('权限被拒绝'),'拒绝麦克风权限时给出可恢复提示')
  let finish:(stream:MediaStream)=>void=()=>{};const lateStream=new MediaStream(),lateContext=new AudioContext();contexts.push(lateContext);const lateDestination=lateContext.createMediaStreamDestination();lateDestination.stream.getTracks().forEach(t=>lateStream.addTrack(t));const late=new VoiceClient(transport(4),()=>{},{...environment(4),capture:()=>new Promise(resolve=>{finish=resolve})});const pending=late.join('TESTAA',4);late.leave();finish(lateStream);await pending;assert(lateStream.getTracks().every(t=>t.readyState==='ended'),'授权未完成时退出，迟到的音轨也会释放')
  log('全部验收通过。范围：本机合成音频与客户端生命周期；公网 TURN 和真实麦克风尚需双设备验收。')
 }catch(e){log('失败：'+(e instanceof Error?e.message:String(e)))}
 finally{clients.forEach(c=>c.leave());for(const context of contexts)await context.close();button.disabled=false}
}
