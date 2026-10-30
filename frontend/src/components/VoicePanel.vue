<script setup lang="ts">
import { computed } from 'vue'
import { useVoiceStore } from '@/stores/voice'
import { useAuthStore } from '@/stores/auth'
const props=defineProps<{roomCode:string;enabled?:boolean}>()
const voice=useVoiceStore(),auth=useAuthStore()
const supported=computed(()=>!!navigator.mediaDevices?.getUserMedia&&typeof RTCPeerConnection!=='undefined')
function join(){if(auth.user)void voice.join(props.roomCode,auth.user.id)}
</script>
<template>
 <section class="voice ww-panel" aria-label="房间语音">
  <div class="voice__top"><strong>房间语音</strong><el-tag :type="voice.state.joined?'success':'info'" size="small">{{ enabled?voice.state.status:'房主尚未开启语音' }}</el-tag></div>
  <p v-if="!supported" role="alert">当前浏览器无法使用麦克风，请通过 HTTPS 或本机 localhost 打开。</p>
  <p v-else-if="!voice.state.joined">加入后与房间内已加入语音的玩家实时通话。</p>
  <p v-if="voice.state.error" class="voice__error" role="alert">{{ voice.state.error }}</p>
  <div class="voice__buttons">
   <el-button v-if="!voice.state.joined" :disabled="!enabled||!supported" :loading="voice.state.busy" @click="join">加入语音</el-button>
   <template v-else><el-button @click="voice.mute">{{ voice.state.muted?'取消静音':'麦克风静音' }}</el-button><el-button @click="voice.leave">退出语音</el-button><el-button text @click="voice.reconnect">重新连接</el-button></template>
   <el-button v-if="voice.state.blockedPlayback" type="warning" @click="voice.playback">点击播放声音</el-button>
  </div>
  <div v-if="voice.state.joined" class="voice__members"><span v-for="member in voice.state.members" :key="member.sessionId">{{ member.nickname }} · {{ member.muted?'已静音':'麦克风开' }}</span><small>已连接 {{ voice.state.connected }} 位玩家</small></div>
 </section>
</template>
<style scoped>
.voice{padding:16px;margin:12px 0;min-width:0}.voice__top,.voice__buttons{display:flex;align-items:center;flex-wrap:wrap;gap:8px}.voice__top{justify-content:space-between}.voice p{font-size:13px;line-height:1.6;color:var(--ww-text-dim)}.voice__error{color:var(--el-color-danger)!important}.voice__members{display:flex;flex-wrap:wrap;gap:10px;margin-top:12px;font-size:12px}.voice__members small{width:100%;color:var(--ww-text-dim)}.voice__buttons :deep(.el-button){margin:0}
</style>
