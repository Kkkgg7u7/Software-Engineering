import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'
import { TOKEN_KEY, USER_KEY } from '@/api/request'
import ws from '@/websocket/stomp'
import type { LoginPayload, RegisterPayload, UserInfo } from '@/types'

/**
 * 用户/登录状态（成员 B 负责模块）。
 *
 * Token 与用户信息同时写入 localStorage，刷新页面后可恢复登录态；
 * 登出时一并断开 WebSocket，避免用旧 token 重连。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<UserInfo | null>(readStoredUser())
  const loading = ref(false)

  const isLoggedIn = computed(() => !!token.value)
  const displayName = computed(() => user.value?.nickname || user.value?.username || '未登录')

  function readStoredUser(): UserInfo | null {
    const raw = localStorage.getItem(USER_KEY)
    if (!raw) {
      return null
    }
    try {
      return JSON.parse(raw) as UserInfo
    } catch {
      return null
    }
  }

  function persist(nextToken: string, nextUser: UserInfo) {
    token.value = nextToken
    user.value = nextUser
    localStorage.setItem(TOKEN_KEY, nextToken)
    localStorage.setItem(USER_KEY, JSON.stringify(nextUser))
  }

  async function login(payload: LoginPayload) {
    loading.value = true
    try {
      const result = await authApi.login(payload)
      persist(result.token, result.user)
      ws.connect()
      return result
    } finally {
      loading.value = false
    }
  }

  async function register(payload: RegisterPayload) {
    loading.value = true
    try {
      const result = await authApi.register(payload)
      persist(result.token, result.user)
      ws.connect()
      return result
    } finally {
      loading.value = false
    }
  }

  /** 刷新当前用户资料（战绩等）。 */
  async function refreshProfile() {
    if (!token.value) {
      return null
    }
    const info = await authApi.me()
    user.value = info
    localStorage.setItem(USER_KEY, JSON.stringify(info))
    return info
  }

  async function updateProfile(payload: { nickname?: string; avatar?: string; signature?: string }) {
    const info = await authApi.updateProfile(payload)
    user.value = info
    localStorage.setItem(USER_KEY, JSON.stringify(info))
    return info
  }

  async function logout() {
    try {
      if (token.value) {
        await authApi.logout()
      }
    } catch {
      /* 登出失败不阻塞本地清理 */
    }
    clear()
  }

  function clear() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    ws.disconnect()
  }

  return {
    token,
    user,
    loading,
    isLoggedIn,
    displayName,
    login,
    register,
    refreshProfile,
    updateProfile,
    logout,
    clear
  }
})
