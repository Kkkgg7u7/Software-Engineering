import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { chatApi } from '@/api/chat'
import ws, { WsSend, WsTopic, WsUserQueue } from '@/websocket/stomp'
import { useAuthStore } from './auth'
import type { ChatMessage, ChatSendPayload, MessageType, RoomView } from '@/types'

/** 聊天频道。 */
export type ChatChannel = 'PUBLIC' | 'PRIVATE' | 'WOLF'

export interface ChatTarget {
  userId: number
  nickname: string
}

/**
 * 局内聊天状态（成员 C 负责模块）。
 *
 * 三种频道与后端一一对应：
 * - PUBLIC：广播到 /topic/room/{code}/chat
 * - PRIVATE：后端只投递给发送者与接收者（/user/queue 通道）
 * - WOLF：后端只投递给狼人（含 AI 狼）
 */
export const useChatStore = defineStore('chat', () => {
  const auth = useAuthStore()

  const messages = ref<ChatMessage[]>([])
  /** 每个频道未读数。 */
  const unread = ref<Record<ChatChannel, number>>({ PUBLIC: 0, PRIVATE: 0, WOLF: 0 })
  const activeChannel = ref<ChatChannel>('PUBLIC')
  /** 私聊对象。 */
  const privateTarget = ref<ChatTarget | null>(null)
  const wolfChatEnabled = ref(false)
  const sending = ref(false)

  /** 当前房间的订阅取消函数（公共主题 + 私有聊天队列）。 */
  let offs: Array<() => void> = []
  let currentCode = ''

  function channelOf(message: ChatMessage): ChatChannel {
    if (message.messageType === 'WOLF') {
      return 'WOLF'
    }
    if (message.messageType === 'PRIVATE') {
      return 'PRIVATE'
    }
    return 'PUBLIC'
  }

  /** 判断一条消息是否应该展示在当前会话里。 */
  function belongsToCurrentView(message: ChatMessage): boolean {
    const channel = channelOf(message)
    if (channel !== 'PRIVATE') {
      return true
    }
    const me = auth.user?.id
    if (message.messageType === 'SYSTEM') {
      return true
    }
    return message.senderId === me || message.receiverId === me
  }

  function appendMessage(message: ChatMessage) {
    if (!belongsToCurrentView(message)) {
      return
    }
    messages.value = [...messages.value, message].slice(-300)
    const channel = channelOf(message)
    const isMine = message.senderId === auth.user?.id
    if (!isMine && channel !== activeChannel.value) {
      unread.value = { ...unread.value, [channel]: unread.value[channel] + 1 }
    }
  }

  /** 进入房间：拉历史 + 订阅实时消息。 */
  async function enterRoom(room: RoomView) {
    leaveRoom()
    currentCode = room.roomCode
    try {
      const history = await chatApi.history(room.roomCode, 150)
      messages.value = history
    } catch {
      messages.value = []
    }
    const handleChat = (envelope: { type: string; data: unknown }) => {
      if (envelope.type === 'CHAT_MESSAGE') {
        appendMessage(envelope.data as ChatMessage)
      }
    }
    // 1) 公共频道：房间公共主题
    offs.push(ws.subscribeWith(WsTopic.chat(room.roomCode), handleChat))
    // 2) 私聊 + 狼人频道：走用户私有队列。
    //    必须订阅，否则这两类消息永远收不到（F-15）。
    const myUserId = auth.user?.id
    if (myUserId == null) {
      console.warn('[chat] 未取到 userId，私聊与狼人频道将收不到实时消息')
    } else {
      offs.push(ws.subscribeWith(WsUserQueue.chat, handleChat))
    }
  }

  function leaveRoom() {
    offs.forEach((off) => off())
    offs = []
    currentCode = ''
    messages.value = []
    unread.value = { PUBLIC: 0, PRIVATE: 0, WOLF: 0 }
    privateTarget.value = null
    wolfChatEnabled.value = false
    activeChannel.value = 'PUBLIC'
  }

  function switchChannel(channel: ChatChannel, target?: ChatTarget) {
    activeChannel.value = channel
    if (channel === 'PRIVATE') {
      privateTarget.value = target ?? privateTarget.value
    } else {
      privateTarget.value = null
    }
    unread.value = { ...unread.value, [channel]: 0 }
  }

  /** 当前频道应展示的消息。 */
  function visibleMessages(): ChatMessage[] {
    const me = auth.user?.id
    return messages.value.filter((m) => {
      const channel = channelOf(m)
      if (m.messageType === 'SYSTEM') {
        return activeChannel.value === 'PUBLIC'
      }
      if (channel !== activeChannel.value) {
        return false
      }
      if (channel === 'PRIVATE' && privateTarget.value) {
        const other = privateTarget.value.userId
        return (
          (m.senderId === me && m.receiverId === other) ||
          (m.senderId === other && m.receiverId === me)
        )
      }
      return true
    })
  }

  async function send(content: string) {
    const text = content.trim()
    if (!text || !currentCode) {
      return
    }
    if (activeChannel.value === 'PRIVATE' && !privateTarget.value) {
      ElMessage.warning('请先选择私聊对象')
      return
    }
    if (activeChannel.value === 'WOLF' && !wolfChatEnabled.value) {
      ElMessage.warning('只有狼人可以使用狼人频道')
      return
    }
    const payload: ChatSendPayload = {
      content: text,
      type: activeChannel.value as MessageType,
      receiverId:
        activeChannel.value === 'PRIVATE' ? privateTarget.value?.userId ?? null : null
    }
    sending.value = true
    try {
      if (ws.connected) {
        ws.publish(WsSend.chat(currentCode), payload)
      } else {
        // WebSocket 未连接时用 HTTP 兜底，保证消息不丢
        const message = await chatApi.send(currentCode, payload)
        appendMessage(message)
      }
    } finally {
      sending.value = false
    }
  }

  return {
    messages,
    unread,
    activeChannel,
    privateTarget,
    wolfChatEnabled,
    sending,
    enterRoom,
    leaveRoom,
    switchChannel,
    visibleMessages,
    send
  }
})
