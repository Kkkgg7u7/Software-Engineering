import { request } from './request'
import type { ChatMessage, ChatSendPayload } from '@/types'

/** 聊天接口（成员 C 负责模块）。实时消息走 STOMP，这里用于历史记录与兜底发送。 */
export const chatApi = {
  history(roomCode: string, limit = 100) {
    return request<ChatMessage[]>({
      url: `/chat/${roomCode}/history`,
      method: 'GET',
      params: { limit }
    })
  },
  send(roomCode: string, payload: ChatSendPayload) {
    return request<ChatMessage>({
      url: `/chat/${roomCode}/send`,
      method: 'POST',
      data: payload
    })
  }
}
