import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import { ElMessage } from 'element-plus'
import SockJS from 'sockjs-client/dist/sockjs.min.js'
import { TOKEN_KEY } from '@/api/request'
import type { WsEnvelope } from '@/types'

/**
 * STOMP over WebSocket 客户端封装（成员 C 负责模块）。
 *
 * 设计要点：
 * 1. JWT 放在 STOMP CONNECT 的 Authorization 头里，避免握手 URL 出现在代理访问日志时泄漏 token
 *    （后端 WsAuthChannelInterceptor 负责鉴权，见 docs/WEBSOCKET.md）；
 * 2. 自动重连 + 重连后重新订阅 + 主动 resync，保证刷新/断线后状态不丢；
 * 3. 用「目的地 -> 回调集合」的路由表分发消息，避免各页面自己解析报文。
 */

/** 服务器推送地址常量。 */
export const WsTopic = {
  rooms: '/topic/rooms',
  room: (code: string) => `/topic/room/${code}`,
  game: (code: string) => `/topic/room/${code}/game`,
  event: (code: string) => `/topic/room/${code}/event`,
  chat: (code: string) => `/topic/room/${code}/chat`
} as const

/**
 * 私有队列目的地。
 *
 * Spring 按已认证的 STOMP 会话把 /user/queue/* 解析为当前用户的私有目的地。
 * 客户端不能指定其他用户 ID；服务端拒绝直接订阅 /user/{id}/queue/* 或 /queue/*。
 */
export const WsUserQueue = {
  game: '/user/queue/game',
  event: '/user/queue/event',
  error: '/user/queue/error',
  lobby: '/user/queue/lobby',
  room: '/user/queue/room',
  /**
   * 点对点聊天（私聊 + 狼人频道）。
   *
   * <p>这两类消息走用户私有队列，而**不是**公共主题 {@code /topic/room/{code}/chat}。
   * 早期前端只订阅了公共主题，导致私聊与狼人频道的消息<b>永远收不到</b>（F-15）。
   */
  chat: '/user/queue/chat'
} as const

/** 服务器推送地址常量。 */
export const WsSend = {
  action: (code: string) => `/app/room/${code}/action`,
  ready: (code: string) => `/app/room/${code}/ready`,
  sync: (code: string) => `/app/room/${code}/sync`,
  heartbeat: (code: string) => `/app/room/${code}/heartbeat`,
  leave: (code: string) => `/app/room/${code}/leave`,
  me: (code: string) => `/app/room/${code}/me`,
  chat: (code: string) => `/app/chat/${code}/send`,
  voteSkip: (code: string) => `/app/room/${code}/vote-skip`,
  lobbyRooms: '/app/lobby/rooms',
  resync: '/app/game/resync'
} as const

type Handler = (envelope: WsEnvelope) => void
type StatusHandler = (connected: boolean, reconnected: boolean) => void

/**
 * 服务端主动踢下线的关闭码（与后端 {@code WsSessionCloser.SESSION_REPLACED} 一致）。
 *
 * <p>收到这个码说明"该账号已在其他设备登录"，旧登录态已作废，
 * 必须停止自动重连并引导用户重新登录，否则会出现
 * "多台设备抢同一个账号、所有人共用一个座位"的现象。
 */
export const WS_CLOSE_SESSION_REPLACED = 4001

function resolveWsUrl(): string {
  const configured = import.meta.env.VITE_WS_URL
  if (configured && configured.trim()) {
    return configured.trim()
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws`
}

class WerewolfWsClient {
  private client: Client | null = null
  private handlers = new Map<string, Set<Handler>>()
  private statusHandlers = new Set<StatusHandler>()
  /** 被服务端顶下线（关闭码 4001）的回调。 */
  private sessionReplacedHandlers = new Set<(message: string) => void>()
  private pendingSends: Array<{ destination: string; body: unknown }> = []
  private everConnected = false
  private token: string | null = null

  get connected(): boolean {
    return !!this.client?.connected
  }

  /**
   * 注册"被顶下线"回调。
   *
   * <p>触发条件：服务端以关闭码 {@link WS_CLOSE_SESSION_REPLACED} 关闭连接，
   * 或收到 {@code KICKED} 报文。此时本端的登录态已作废，
   * 应提示用户并清理登录态，而不是继续重连。
   */
  onSessionReplaced(handler: (message: string) => void): () => void {
    this.sessionReplacedHandlers.add(handler)
    return () => this.sessionReplacedHandlers.delete(handler)
  }

  private notifySessionReplaced(message: string): void {
    this.sessionReplacedHandlers.forEach((h) => {
      try {
        h(message)
      } catch (error) {
        console.error('[ws] sessionReplaced handler error', error)
      }
    })
  }

  /** 注册某个目的地的消息处理器，返回取消注册函数。 */
  on(destination: string, handler: Handler): () => void {
    if (!this.handlers.has(destination)) {
      this.handlers.set(destination, new Set())
    }
    this.handlers.get(destination)!.add(handler)
    return () => {
      this.handlers.get(destination)?.delete(handler)
    }
  }

  /** 连接状态变化（含重连标记，用于触发状态 resync）。 */
  onStatus(handler: StatusHandler): () => void {
    this.statusHandlers.add(handler)
    return () => this.statusHandlers.delete(handler)
  }

  /** 建立连接（幂等）。 */
  connect(): void {
    const token = localStorage.getItem(TOKEN_KEY)
    if (!token) {
      return
    }
    if (this.client?.active && this.token === token) {
      return
    }
    if (this.client) {
      this.client.deactivate()
      this.client = null
    }
    this.token = token
    const useSockJs = String(import.meta.env.VITE_USE_SOCKJS).toLowerCase() === 'true'
    const url = resolveWsUrl()

    const client = new Client({
      // 原生 WebSocket；SockJS 模式下由 webSocketFactory 提供传输
      brokerURL: useSockJs ? undefined : url,
      webSocketFactory: useSockJs ? () => new SockJS(resolveWsUrl()) : undefined,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      debug: () => {
        /* 需要排查时改为 console.debug */
      }
    })

    client.onConnect = () => {
      const reconnected = this.everConnected
      this.everConnected = true
      // 重新订阅所有已注册的目的地
      this.handlers.forEach((_, destination) => this.subscribe(destination))
      // 补发离线期间积压的发送
      const queued = [...this.pendingSends]
      this.pendingSends = []
      queued.forEach((item) => this.publish(item.destination, item.body))
      this.statusHandlers.forEach((h) => h(true, reconnected))
    }
    client.onDisconnect = () => {
      this.statusHandlers.forEach((h) => h(false, false))
    }
    client.onStompError = (frame) => {
      const message = frame.headers['message'] || 'WebSocket 服务端错误'
      console.warn('[ws] STOMP error:', message)
    }
    client.onWebSocketClose = (event) => {
      // 4001 = 服务端主动踢下线（该账号已在其他设备登录）。
      // 此时必须停止自动重连，否则会陷入"重连 -> 被踢 -> 再重连"的死循环，
      // 而且旧登录态已经作废，重连也没有意义。
      const code = (event as CloseEvent | undefined)?.code
      if (code === 4002) {
        this.stopReconnect()
        this.notifySessionReplaced('密码已修改，请使用新密码重新登录。')
        return
      }
      if (code === WS_CLOSE_SESSION_REPLACED) {
        console.warn('[ws] 该账号已在其他设备登录，已停止重连')
        this.stopReconnect()
        this.notifySessionReplaced('该账号已在其他设备登录，你已下线。多人游戏请各自注册账号。')
        return
      }
      this.statusHandlers.forEach((h) => h(false, false))
    }

    this.client = client
    client.activate()
  }

  /** 订阅目的地（重复订阅会被忽略）。 */
  subscribe(destination: string): void {
    if (!this.client?.connected) {
      return
    }
    const client = this.client
    const existing = clientSubscriptions.get(client)
    if (existing?.has(destination)) {
      return
    }
    const sub: StompSubscription = client.subscribe(destination, (message: IMessage) => {
      let envelope: WsEnvelope
      try {
        envelope = JSON.parse(message.body) as WsEnvelope
      } catch {
        console.warn('[ws] 无法解析报文:', message.body)
        return
      }
      this.dispatch(destination, envelope)
    })
    if (!existing) {
      clientSubscriptions.set(client, new Map([[destination, sub]]))
    } else {
      existing.set(destination, sub)
    }
  }

  /** 订阅并直接绑定处理器（页面常用写法）。 */
  subscribeWith(destination: string, handler: Handler): () => void {
    const off = this.on(destination, handler)
    this.subscribe(destination)
    return () => {
      off()
      // 不主动退订：目的地可能是共享的（例如游戏状态），由全局管理
    }
  }

  /** 发送消息；未连接时排队，连接后自动补发。 */
  publish(destination: string, body: unknown): void {
    if (!this.client?.connected) {
      this.pendingSends.push({ destination, body })
      this.connect()
      return
    }
    this.client.publish({ destination, body: JSON.stringify(body ?? {}) })
  }

  /** 断开连接（退出登录 / 离开房间）。 */
  disconnect(): void {
    this.pendingSends = []
    this.handlers.clear()
    if (this.client) {
      const subs = clientSubscriptions.get(this.client)
      subs?.forEach((sub) => {
        try {
          sub.unsubscribe()
        } catch {
          /* ignore */
        }
      })
      clientSubscriptions.delete(this.client)
      this.client.deactivate()
      this.client = null
    }
    this.everConnected = false
    this.token = null
    this.statusHandlers.forEach((h) => h(false, false))
  }

  /**
   * 停止自动重连（但不清理处理器）。
   *
   * <p>用于"被服务端踢下线"的场景：连接已无意义，继续重连只会不断被拒。
   * 调用方（main.ts）会在这之后清理登录态并跳转登录页。
   */
  stopReconnect(): void {
    this.pendingSends = []
    if (this.client) {
      // reconnectDelay = 0 表示不再自动重连
      this.client.reconnectDelay = 0
      this.client.deactivate()
      this.client = null
    }
    this.everConnected = false
    this.statusHandlers.forEach((h) => h(false, false))
  }

  private dispatch(destination: string, envelope: WsEnvelope): void {
    if (envelope?.type === 'ERROR') {
      const data = envelope.data as { code?: number; message?: string }
      if (data?.code !== 1001) {
        ElMessage.error(data?.message || '操作失败')
      }
    }
    if (envelope?.type === 'KICKED') {
      const data = envelope.data as { message?: string }
      const message = data?.message || '你已被移出房间'
      ElMessage.warning(message)
      // 若服务端明确说明是"账号在别处登录"，则进一步通知上层清理登录态
      if (message.includes('其他设备登录') || message.includes('已下线')) {
        this.notifySessionReplaced(message)
      }
    }
    this.handlers.get(destination)?.forEach((handler) => {
      try {
        handler(envelope)
      } catch (error) {
        console.error('[ws] handler error on', destination, error)
      }
    })
  }
}

const clientSubscriptions = new WeakMap<Client, Map<string, StompSubscription>>()

export const ws = new WerewolfWsClient()
export default ws
