<script setup lang="ts">
/**
 * 应用根组件：只负责全局骨架（背景层 + 顶部导航 + 路由出口）。
 * 具体的游戏内界面见 views/GameView.vue。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useConversationsStore } from '@/stores/conversations'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'
import { useGameStore } from '@/stores/game'
import { useChatStore } from '@/stores/chat'
import ws from '@/websocket/stomp'
import { useVoiceStore } from '@/stores/voice'

const auth = useAuthStore()
const conversations = useConversationsStore()
watch(() => auth.token, token => { if (token) conversations.start(); else conversations.reset() }, { immediate: true, flush: 'sync' })
const room = useRoomStore()
const game = useGameStore()
const chat = useChatStore()
const voice=useVoiceStore()
watch([()=>auth.token,()=>room.current?.roomCode,()=>room.current?.voiceEnabled],([token,code,enabled],[oldToken,oldCode])=>{if(!token||token!==oldToken||!code||code!==oldCode||!enabled)voice.leave()},{flush:'sync'})
const route = useRoute()
const router = useRouter()

const wsConnected = ref(false)
let offStatus: (() => void) | null = null

const showNav = computed(() => route.name !== 'login' && route.name !== 'not-found')
const inGame = computed(() => game.state?.phase && game.state.phase !== 'WAITING')

onMounted(() => {
  offStatus = ws.onStatus((connected) => {
    wsConnected.value = connected
  })
  wsConnected.value = ws.connected
})

onUnmounted(() => {
  offStatus?.()
})

async function handleLeave() {
  const code = room.current?.roomCode
  if (!code) {
    return
  }
  try {
    await ElMessageBox.confirm(
      inGame.value ? '对局尚未结束，确定要退出房间吗？' : '确定要退出当前房间吗？',
      '退出房间',
      { confirmButtonText: '确定退出', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }
  chat.leaveRoom()
  game.reset()
  await room.leaveRoom(code)
  ElMessage.success('已退出房间')
  router.push({ name: 'lobby' })
}

async function handleLogout() {
  chat.leaveRoom()
  game.reset()
  room.resetLocal()
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="app-shell">
    <!-- 背景层：月夜渐变 + 星点，全站共用 -->
    <div class="app-bg" aria-hidden="true">
      <div class="app-bg__moon" />
      <div class="app-bg__stars" />
      <div class="app-bg__vignette" />
    </div>

    <header v-if="showNav" class="app-nav">
      <div class="app-nav__brand" @click="router.push({ name: 'lobby' })">
        <img src="/assets/brand/jiujiu-256.png" alt="啾啾 JiuJiu" class="app-nav__logo" />
        <div class="app-nav__title">
          <strong>啾啾 <small>JiuJiu</small></strong>
          <span>月夜开局，和朋友一起玩</span>
        </div>
      </div>

      <div class="app-nav__right">
        <el-tag :type="wsConnected ? 'success' : 'info'" size="small" effect="dark" round>
          <span class="ws-dot" :class="{ 'ws-dot--on': wsConnected }" />
          {{ wsConnected ? '实时已连接' : '连接中…' }}
        </el-tag>

        <el-tag v-if="room.current" type="warning" size="small" effect="plain" round>
          房间 {{ room.current.roomCode }}
        </el-tag>

        <el-button
          v-if="room.current"
          size="small"
          type="danger"
          plain
          @click="handleLeave"
        >
          退出房间
        </el-button>

        <el-dropdown v-if="auth.isLoggedIn" trigger="click">
          <span class="app-nav__user">
            <el-avatar :size="28" :src="auth.user?.avatar" />
            <span class="app-nav__nickname">{{ auth.displayName }}</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="router.push({ name: 'profile' })">个人中心</el-dropdown-item>
              <el-dropdown-item @click="router.push({ name: 'friends' })">好友管理</el-dropdown-item>
              <el-dropdown-item @click="router.push({ name: 'records' })">我的战绩</el-dropdown-item>
              <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <nav v-if="showNav && route.name !== 'game'" class="platform-nav" aria-label="平台导航">
      <router-link :to="{ name: 'conversations' }" :class="{ active: route.name === 'conversations' }">聊天 <small v-if="conversations.unread">{{ conversations.unread }}</small></router-link>
      <router-link :to="{ name: 'games' }" :class="{ active: ['games', 'lobby', 'room', 'room-join'].includes(String(route.name)) }">游戏</router-link>
      <router-link :to="{ name: 'wall' }" :class="{ active: route.name === 'wall' }">墙</router-link>
      <router-link :to="{ name: 'profile' }" :class="{ active: ['profile', 'records', 'friends'].includes(String(route.name)) }">我的</router-link>
    </nav>

    <main class="app-main">
      <router-view v-slot="{ Component }">
        <transition name="fade-slide" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<style scoped>
.platform-nav { position: relative; z-index: 3; display: flex; gap: 6px; padding: 10px 24px 0; max-width: 1400px; width: 100%; margin: auto; box-sizing: border-box; }
.platform-nav a { text-decoration: none; color: var(--ww-text-dim); padding: 10px 24px; border-radius: 12px; font-weight: 600; }
.platform-nav a.active { background: rgba(251,191,36,.14); color: var(--ww-moon); }
.app-nav__title small { font-size: 12px; font-weight: 400; opacity: .65; margin-left: 5px; }
@media (max-width: 680px) {
  .platform-nav { position: fixed; bottom: 0; left: 0; padding: 8px 16px calc(8px + env(safe-area-inset-bottom)); background: #101626; border-top: 1px solid var(--ww-border); justify-content: space-around; }
  .platform-nav a { text-align: center; flex: 1; padding: 10px; }
  .app-main { padding-bottom: 88px !important; }
}

.app-shell {
  position: relative;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  color: var(--ww-text);
}

.app-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  background: var(--ww-gradient-bg);
  overflow: hidden;
}

.app-bg__moon {
  position: absolute;
  top: -140px;
  right: -100px;
  width: 460px;
  height: 460px;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 35%, #fff8e1 0%, #fbbf24 45%, rgba(251, 191, 36, 0) 72%);
  opacity: 0.35;
  filter: blur(2px);
}

.app-bg__stars {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(1.6px 1.6px at 12% 18%, #fff 45%, transparent 46%),
    radial-gradient(1.2px 1.2px at 28% 42%, #e0e7ff 45%, transparent 46%),
    radial-gradient(1.8px 1.8px at 46% 12%, #fff 45%, transparent 46%),
    radial-gradient(1.3px 1.3px at 68% 34%, #c7d2fe 45%, transparent 46%),
    radial-gradient(1.5px 1.5px at 84% 22%, #fff 45%, transparent 46%),
    radial-gradient(1.2px 1.2px at 92% 58%, #e0e7ff 45%, transparent 46%),
    radial-gradient(1.4px 1.4px at 16% 72%, #fff 45%, transparent 46%),
    radial-gradient(1.1px 1.1px at 58% 78%, #c7d2fe 45%, transparent 46%);
  opacity: 0.75;
}

.app-bg__vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at 50% 0%, rgba(124, 58, 237, 0.18), transparent 60%),
    radial-gradient(ellipse at 50% 120%, rgba(15, 23, 42, 0.9), transparent 55%);
}

.app-nav {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: rgba(9, 12, 24, 0.72);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(124, 58, 237, 0.28);
}

.app-nav__brand {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.app-nav__logo {
  object-fit: contain;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  box-shadow: 0 4px 18px rgba(124, 58, 237, 0.45);
}

.app-nav__title {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.app-nav__title strong {
  font-size: 17px;
  letter-spacing: 1px;
  background: linear-gradient(90deg, #fde68a, #c4b5fd);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.app-nav__title span {
  font-size: 11px;
  color: var(--ww-text-dim);
  letter-spacing: 0.5px;
}

.app-nav__right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.app-nav__user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 999px;
  transition: background 0.2s;
}

.app-nav__user:hover {
  background: rgba(124, 58, 237, 0.18);
}

.app-nav__nickname {
  font-size: 13px;
  color: var(--ww-text);
}

.ws-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 5px;
  border-radius: 50%;
  background: #94a3b8;
  vertical-align: middle;
}

.ws-dot--on {
  background: #22c55e;
  box-shadow: 0 0 8px #22c55e;
}

.app-main {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
}

.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
@media (max-width: 680px) {
  .app-nav { padding: 12px 16px; gap: 10px; }
  .app-nav__brand { gap: 8px; flex-shrink: 0; }
  .app-nav__logo {
  object-fit: contain; width: 32px; height: 32px; }
  .app-nav__title strong { white-space: nowrap; font-size: 16px; }
  .app-nav__title small, .app-nav__title span, .app-nav__nickname { display: none; }
  .app-nav__right { flex: 1; min-width: 0; gap: 6px; flex-wrap: wrap; justify-content: flex-end; }
  .app-nav__user { padding: 3px; }
}
</style>
