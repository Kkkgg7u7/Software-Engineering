import { request } from './request'
import type { ActionPayload, GameMeta, GameView, SpeechRecord } from '@/types'

/** 游戏接口（成员 D 负责模块）。 */
export const gameApi = {
  /** 房主开始游戏。 */
  start() {
    return request<GameView>({ url: '/game/start', method: 'POST' })
  },
  /** 提交行动。WebSocket 通道更适合实时操作，这里是 HTTP 兜底与调试通道。 */
  action(payload: ActionPayload) {
    return request<GameView>({ url: '/game/action', method: 'POST', data: payload })
  },
  state() {
    return request<GameView>({ url: '/game/state', method: 'GET' })
  },
  stateByCode(roomCode: string) {
    return request<GameView>({ url: `/game/state/${roomCode}`, method: 'GET' })
  },
  speeches() {
    return request<SpeechRecord[]>({ url: '/game/speeches', method: 'GET' })
  },
  reset() {
    return request<void>({ url: '/game/reset', method: 'POST' })
  },
  meta() {
    return request<GameMeta>({ url: '/game/meta', method: 'GET' })
  }
}
