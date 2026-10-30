import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types'
import { AUTH_ERROR_CODES } from '@/types'

/** Token 在 localStorage 中的键名。 */
export const TOKEN_KEY = 'werewolf_token'
export const USER_KEY = 'werewolf_user'

/**
 * API 基础路径。三种取值方式，覆盖三种部署形态：
 *
 * <ul>
 *   <li><b>不设置</b>（默认）：走 <code>/api</code> 相对路径 ——
 *       开发期由 Vite 代理转发；生产由 Nginx / 后端同源托管时同样有效。<b>推荐</b>。</li>
 *   <li><b>绝对地址</b>，如 <code>http://192.168.1.23:8080/api</code> ——
 *       前端与后端分开部署、且中间没有反向代理时使用（需要后端 CORS 放行）。</li>
 *   <li><b>空字符串</b>：直接打到当前源，不追加任何前缀。</li>
 * </ul>
 */
function resolveApiBase(): string {
  const raw = import.meta.env.VITE_API_BASE
  if (raw === undefined || raw === null || raw === '') {
    return '/api'
  }
  return raw
}

export const API_BASE = resolveApiBase()

/**
 * 业务异常。所有非 0 code 都会抛出它，调用方可以 catch 后决定提示方式。
 */
export class ApiError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
    this.name = 'ApiError'
  }
}

/** 401 时由 main.ts 注册的回调（跳转登录页）。 */
let unauthorizedHandler: (() => void) | null = null

/** 会话被顶下线时的回调（由 main.ts 注册，用于弹出明确提示）。 */
let sessionReplacedHandler: ((message: string) => void) | null = null

export function onUnauthorized(handler: () => void) {
  unauthorizedHandler = handler
}

export function onSessionReplaced(handler: (message: string) => void) {
  sessionReplacedHandler = handler
}

function redirectToLogin(message?: string) {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  if (unauthorizedHandler) {
    unauthorizedHandler()
  }
  if (message && sessionReplacedHandler) {
    sessionReplacedHandler(message)
  }
}

const http: AxiosInstance = axios.create({
  baseURL: API_BASE,
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' }
})

/** 请求拦截：自动携带 JWT。 */
http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 响应拦截：把 { code, message, data } 解包成 data。
 * code !== 0 时抛 ApiError；1001 额外触发跳转登录。
 */
http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>
    if (!body || typeof body.code !== 'number') {
      // 非标准响应（例如 Nginx 返回的 HTML），直接放行原始数据
      return response
    }
    if (body.code === 0) {
      return response
    }
    if (AUTH_ERROR_CODES.includes(body.code)) {
      // 2005 = 该账号已在其他设备登录：提示要区分于普通过期，否则用户不明白为什么被踢
      const replaced = body.code === 2005
      redirectToLogin(
        replaced
          ? body.message || '该账号已在其他设备登录，你已下线。多人游戏请各自注册账号。'
          : undefined
      )
      throw new ApiError(body.code, body.message || '登录已过期，请重新登录')
    }
    throw new ApiError(body.code, body.message || '请求失败')
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401 || status === 403) {
      redirectToLogin()
      throw new ApiError(1001, '登录已过期，请重新登录')
    }
    const message =
      error?.response?.data?.message ||
      (error?.code === 'ECONNABORTED' ? '请求超时，请检查后端服务是否启动' : '网络异常，请稍后重试')
    throw new ApiError(-1, message)
  }
)

/** 统一请求方法：返回 data 字段。 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResponse<T>>(config)
  const body = response.data as ApiResponse<T>
  return body?.data as T
}

/** 带自动错误提示的请求（页面里大多数场景用这个）。 */
export async function requestWithToast<T>(
  config: AxiosRequestConfig,
  options: { silent?: boolean } = {}
): Promise<T | null> {
  try {
    return await request<T>(config)
  } catch (error) {
    const err = error as ApiError
    if (!options.silent && err.code !== 1001) {
      ElMessage.error(err.message)
    }
    return null
  }
}

export default http
