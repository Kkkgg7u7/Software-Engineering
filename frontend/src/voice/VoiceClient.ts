export interface VoiceMember { userId:number; sessionId:string; nickname:string; muted:boolean }
export interface VoiceSnapshot { roomCode:string; enabled:boolean; members:VoiceMember[] }
export interface VoiceJoin extends VoiceSnapshot { sessionId:string; iceServers:RTCIceServer[]; leaseSeconds:number }
export interface VoiceSignal { sessionId:string; targetUserId:number; targetSessionId:string; type:'OFFER'|'ANSWER'|'ICE'; payload:RTCSessionDescriptionInit|RTCIceCandidateInit }
export interface IncomingSignal { roomCode:string; fromUserId:number; fromSessionId:string; targetSessionId:string; type:VoiceSignal['type']; payload:VoiceSignal['payload'] }
export interface VoiceTransport {
 join(code:string,clientId:string):Promise<VoiceJoin>
 heartbeat(code:string,sessionId:string,muted:boolean):Promise<VoiceSnapshot>
 leave(code:string,sessionId:string):Promise<unknown>
 signal(code:string,data:VoiceSignal):Promise<unknown>
}
export interface VoiceState { joined:boolean; busy:boolean; muted:boolean; status:string; error:string; blockedPlayback:boolean; members:VoiceMember[]; connected:number }
interface Peer { member:VoiceMember; pc:RTCPeerConnection; audio:HTMLAudioElement; candidates:RTCIceCandidateInit[]; chain:Promise<void> }
interface Environment {
 capture():Promise<MediaStream>
 connection(config:RTCConfiguration):RTCPeerConnection
 audio():HTMLAudioElement
 id():string
}
const browserEnvironment:Environment = {
 capture:()=>navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false}),
 connection:config=>new RTCPeerConnection(config), audio:()=>new Audio(), id:()=>crypto.randomUUID()
}
/** Audio lifetime is independent from RoomView/GameView navigation. All async work is generation guarded. */
export class VoiceClient {
 readonly state:VoiceState={joined:false,busy:false,muted:false,status:'未加入语音',error:'',blockedPlayback:false,members:[],connected:0}
 private stream:MediaStream|null=null
 private session=''
 private code=''
 private uid=0
 private generation=0
 private peers=new Map<number,Peer>()
 private ice:RTCIceServer[]=[]
 private pending:IncomingSignal[]=[]
 private timer:ReturnType<typeof setInterval>|null=null
 private heartbeatBusy=false
 private retrying=false
 private failures=0
 private retryWindow=0
 private retries=0
 constructor(private transport:VoiceTransport,private changed:()=>void,private env:Environment=browserEnvironment){}
 private emit(){this.state.connected=[...this.peers.values()].filter(p=>p.pc.connectionState==='connected').length;this.changed()}
 async join(code:string,uid:number){
  if(this.state.busy||this.state.joined)return
  this.code=code;this.uid=uid;this.state.busy=true;this.state.error='';this.state.status='等待麦克风授权';this.emit()
  const generation=++this.generation
  try {
   const stream=await this.env.capture()
   if(generation!==this.generation){stream.getTracks().forEach(t=>t.stop());return}
   this.stream=stream;this.state.status='正在连接语音';this.emit()
   const result=await this.transport.join(code,this.env.id())
   if(generation!==this.generation){void this.transport.leave(code,result.sessionId).catch(()=>{});return}
   this.session=result.sessionId;this.ice=result.iceServers;this.state.joined=true;this.state.muted=false;this.failures=0
   this.state.status='已加入语音';this.reconcile(result)
   const buffered=this.pending.splice(0);for(const signal of buffered)this.receive(signal)
   this.timer=setInterval(()=>void this.heartbeat(),8000)
   for(const track of stream.getAudioTracks())track.onended=()=>{if(this.state.joined){this.leave();this.state.error='麦克风已断开，请重新加入';this.emit()}}
  }catch(error){if(generation===this.generation){this.leave();this.state.error=this.message(error);this.emit()}}
  finally{if(generation===this.generation){this.state.busy=false;this.emit()}}
 }
 private message(error:unknown){
  const name=error instanceof Error?error.name:''
  if(name==='NotAllowedError'||name==='SecurityError')return '麦克风权限被拒绝，请在浏览器中允许后重新加入'
  if(name==='NotFoundError')return '没有找到可用的麦克风'
  if(name==='NotReadableError')return '麦克风被占用或无法读取'
  return error instanceof Error?error.message:'语音连接失败，请重试'
 }
 leave(){
  const oldSession=this.session,oldCode=this.code;this.generation++;this.session='';this.pending=[]
  if(this.timer)clearInterval(this.timer);this.timer=null
  this.closePeers();const stream=this.stream;this.stream=null
  stream?.getTracks().forEach(track=>{track.onended=null;track.stop()})
  this.state.joined=false;this.state.busy=false;this.state.muted=false;this.state.members=[];this.state.blockedPlayback=false;this.state.status='未加入语音';this.emit()
  if(oldSession)void this.transport.leave(oldCode,oldSession).catch(()=>{})
 }
 mute(){if(!this.stream||!this.state.joined)return;this.state.muted=!this.state.muted;this.stream.getAudioTracks().forEach(t=>t.enabled=!this.state.muted);this.emit();void this.heartbeat()}
 private closePeer(id:number){const peer=this.peers.get(id);if(!peer)return;this.peers.delete(id);peer.pc.onicecandidate=null;peer.pc.ontrack=null;peer.pc.onconnectionstatechange=null;peer.pc.close();peer.audio.pause();peer.audio.srcObject=null}
 private closePeers(){for(const id of [...this.peers.keys()])this.closePeer(id)}
 reconcile(snapshot:VoiceSnapshot){
  if(snapshot.roomCode!==this.code||!this.state.joined)return
  if(!snapshot.enabled){this.leave();this.state.error='房主已关闭语音';this.emit();return}
  this.state.members=snapshot.members
  const me=snapshot.members.find(m=>m.userId===this.uid)
  if(!me||me.sessionId!==this.session){this.leave();this.state.error='语音会话已被替换，请重新加入';this.emit();return}
  for(const [id,peer]of this.peers){const current=snapshot.members.find(m=>m.userId===id);if(!current||current.sessionId!==peer.member.sessionId)this.closePeer(id)}
  for(const member of snapshot.members){
   if(member.userId===this.uid||this.peers.has(member.userId))continue
   const peer=this.makePeer(member)
   if(this.uid<member.userId)this.enqueue(peer,async()=>{const offer=await peer.pc.createOffer();await peer.pc.setLocalDescription(offer);await this.send(peer,'OFFER',peer.pc.localDescription!.toJSON())})
  }
  this.emit()
 }
 private makePeer(member:VoiceMember):Peer {
  const pc=this.env.connection({iceServers:this.ice});const audio=this.env.audio();audio.autoplay=true
  const peer:Peer={member,pc,audio,candidates:[],chain:Promise.resolve()};this.peers.set(member.userId,peer)
  for(const track of this.stream!.getAudioTracks())pc.addTrack(track,this.stream!)
  pc.onicecandidate=event=>{if(event.candidate)void this.send(peer,'ICE',event.candidate.toJSON()).catch(()=>this.peerError(peer))}
  pc.ontrack=event=>{if(!this.isCurrent(peer))return;audio.srcObject=event.streams[0]??new MediaStream([event.track]);void audio.play().catch(()=>{this.state.blockedPlayback=true;this.emit()})}
  pc.onconnectionstatechange=()=>{if(!this.isCurrent(peer))return;if(pc.connectionState==='failed')this.peerError(peer);this.emit()}
  return peer
 }
 private isCurrent(peer:Peer){return this.state.joined&&this.peers.get(peer.member.userId)===peer}
 private enqueue(peer:Peer,task:()=>Promise<void>){const generation=this.generation;peer.chain=peer.chain.then(async()=>{if(generation===this.generation&&this.isCurrent(peer))await task()}).catch(()=>{if(generation===this.generation&&this.isCurrent(peer))this.peerError(peer)})}
 private async send(peer:Peer,type:VoiceSignal['type'],payload:VoiceSignal['payload']){if(!this.isCurrent(peer))return;await this.transport.signal(this.code,{sessionId:this.session,targetUserId:peer.member.userId,targetSessionId:peer.member.sessionId,type,payload})}
 receive(signal:IncomingSignal){
  if(signal.roomCode!==this.code)return
  if(!this.session){if(this.state.busy&&this.pending.length<100)this.pending.push(signal);return}
  if(signal.targetSessionId!==this.session||!this.state.joined)return
  const member=this.state.members.find(m=>m.userId===signal.fromUserId&&m.sessionId===signal.fromSessionId)
  if(!member)return
  const peer=this.peers.get(member.userId)??this.makePeer(member)
  this.enqueue(peer,async()=>{
   if(signal.type==='ICE'){
    if(peer.pc.remoteDescription)await peer.pc.addIceCandidate(signal.payload as RTCIceCandidateInit)
    else if(peer.candidates.length<100)peer.candidates.push(signal.payload as RTCIceCandidateInit)
    return
   }
   // Exactly the lower user id offers; reject unexpected glare instead of replacing a local offer.
   if(signal.type==='OFFER'&&this.uid<member.userId)return
   await peer.pc.setRemoteDescription(signal.payload as RTCSessionDescriptionInit)
   for(const candidate of peer.candidates.splice(0))await peer.pc.addIceCandidate(candidate)
   if(signal.type==='OFFER'){const answer=await peer.pc.createAnswer();await peer.pc.setLocalDescription(answer);await this.send(peer,'ANSWER',peer.pc.localDescription!.toJSON())}
  })
 }
 closed(data:{sessionId:string;reason:string}){if(data.sessionId===this.session){this.leave();this.state.error=data.reason;this.emit()}}
 async playback(){this.state.blockedPlayback=false;for(const peer of this.peers.values())try{await peer.audio.play()}catch{this.state.blockedPlayback=true}this.emit()}
 private peerError(peer:Peer){if(!this.isCurrent(peer))return;this.state.status='音频连接失败，正在重连';this.emit();void this.reconnect()}
 async heartbeat(){
  if(!this.state.joined||this.heartbeatBusy||this.retrying)return
  this.heartbeatBusy=true;const generation=this.generation
  try{const snapshot=await this.transport.heartbeat(this.code,this.session,this.state.muted);if(generation===this.generation){this.failures=0;this.reconcile(snapshot)}}
  catch{if(generation===this.generation){this.state.status='语音网络中断，正在重连';this.emit();if(++this.failures>=3){this.leave();this.state.error='语音网络持续不可用，已释放麦克风，请重新加入';this.emit()}else await this.reconnect()}}
  finally{this.heartbeatBusy=false}
 }
 async reconnect(){
  if(this.retrying||!this.state.joined)return
  const now=Date.now();if(now-this.retryWindow>60000){this.retryWindow=now;this.retries=0}
  if(++this.retries>3){this.leave();this.state.error='音频连接多次失败，请检查网络或联系房主检查语音服务';this.emit();return}
  this.retrying=true;this.state.busy=true;const generation=++this.generation;this.closePeers();this.session='';this.state.status='正在重新建立语音连接';this.emit()
  try{const result=await this.transport.join(this.code,this.env.id());if(generation!==this.generation){void this.transport.leave(this.code,result.sessionId).catch(()=>{});return}this.session=result.sessionId;this.ice=result.iceServers;this.reconcile(result);for(const signal of this.pending.splice(0))this.receive(signal);this.state.status='已加入语音';this.state.error='';this.emit();void this.heartbeat()}
  catch(error){if(generation===this.generation){this.leave();this.state.error=this.message(error);this.emit()}}
  finally{this.retrying=false;if(generation===this.generation){this.state.busy=false;this.emit()}}
 }
}
