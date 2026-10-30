import { request } from './request'
import type { CreateRoomPayload, RoomView, UpdateRoomPayload, MatchRoomPayload } from '@/types'

/** 房间接口（成员 A 负责模块）。 */
export const roomApi = {
  invite(roomCode:string,data:{clientMessageId:string;targetUserId?:number;conversationId?:number}) { return request({url:`/rooms/${roomCode}/invite`,method:'POST',data}) },
  create(payload: CreateRoomPayload) {
    return request<RoomView>({ url: '/rooms', method: 'POST', data: payload })
  },
  join(roomCode: string, password?: string) {
    return request<RoomView>({ url: '/rooms/join', method: 'POST', data: { roomCode, password } })
  },
  /** 一键匹配：服务端优先加入标准公开等待房，否则创建新房。 */
  match(payload?: MatchRoomPayload) {
    return request<RoomView>({ url: '/rooms/match', method: 'POST', data: payload })
  },
  listPublic() {
    return request<RoomView[]>({ url: '/rooms/public', method: 'GET' })
  },
  current() {
    return request<RoomView | null>({ url: '/rooms/current', method: 'GET' })
  },
  detail(roomCode: string) {
    return request<RoomView>({ url: `/rooms/${roomCode}`, method: 'GET' })
  },
  update(roomCode: string, payload: UpdateRoomPayload) {
    return request<RoomView>({ url: `/rooms/${roomCode}`, method: 'PUT', data: payload })
  },
  ready(roomCode: string, ready: boolean) {
    return request<RoomView>({
      url: `/rooms/${roomCode}/ready`,
      method: 'POST',
      data: { ready }
    })
  },
  leave(roomCode: string) {
    return request<void>({ url: `/rooms/${roomCode}/leave`, method: 'POST' })
  },
  kick(roomCode: string, seatId: number) {
    return request<void>({ url: `/rooms/${roomCode}/seats/${seatId}`, method: 'DELETE' })
  },
  dissolve(roomCode: string) {
    return request<void>({ url: `/rooms/${roomCode}`, method: 'DELETE' })
  }
}

/** 大厅接口。 */
export const lobbyApi = {
  rooms() {
    return request<RoomView[]>({ url: '/lobby/rooms', method: 'GET' })
  }
}
