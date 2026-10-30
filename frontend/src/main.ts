import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { ElMessageBox } from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { onSessionReplaced, onUnauthorized } from './api/request'
import { useAuthStore } from './stores/auth'
import { useRoomStore } from './stores/room'
import { useGameStore } from './stores/game'
import { useChatStore } from './stores/chat'
import ws from './websocket/stomp'
import './styles/main.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

/** 清理登录态并跳回登录页。 */
function forceLogout(): void {
  const auth = useAuthStore(pinia)
  auth.clear()
  useRoomStore(pinia).resetLocal()
  useGameStore(pinia).reset()
  useChatStore(pinia).leaveRoom()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
}

// 后端返回 1001（未登录/过期）时统一跳转登录页
onUnauthorized(forceLogout)

// 后端返回 2005（该账号已在其他设备登录）时给出明确原因，而不是只说"登录过期"
onSessionReplaced((message) => {
  void ElMessageBox.alert(message, '登录状态已失效', {
    confirmButtonText: '重新登录',
    type: 'warning',
    showClose: false
  })
})

// WebSocket 被服务端以 4001 主动关闭，或收到"已在其他设备登录"的 KICKED 报文
// => 本端登录态已作废，停止重连、提示原因、清回登录页
let sessionReplacedNotified = false
ws.onSessionReplaced((message) => {
  if (sessionReplacedNotified) {
    return
  }
  sessionReplacedNotified = true
  void ElMessageBox.alert(message, '登录状态已失效', {
    confirmButtonText: '重新登录',
    type: 'warning',
    showClose: false
  }).finally(() => {
    sessionReplacedNotified = false
  })
  forceLogout()
})

// 已登录用户自动建立 WebSocket 连接
const auth = useAuthStore(pinia)
if (auth.isLoggedIn) {
  ws.connect()
}

app.mount('#app')

// 移除 index.html 里的首屏骨架，避免遮挡应用界面
const boot = document.getElementById('app-boot')
if (boot) {
  boot.classList.add('hidden')
  window.setTimeout(() => boot.remove(), 320)
}
