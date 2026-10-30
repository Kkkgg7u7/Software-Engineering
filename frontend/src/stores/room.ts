import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { lobbyApi, roomApi } from '@/api/room'
import ws, { WsSend, WsTopic, WsUserQueue } from '@/websocket/stomp'
import { useAuthStore } from './auth'
import type { CreateRoomPayload, RoomView, UpdateRoomPayload } from '@/types'

/**
 * 房间/座位状态（成员 A 负责模块）。
 *
 * 数据来源：
 * - REST：创建/加入/退出/修改配置（需要明确的成功/失败语义）；
 * - WebSocket 公共主题 `/topic/room/{code}`：不含个人视角的公开快照；
 * - WebSocket 私有队列 `/user/queue/room`：本人专属字段（`mySeatId` / `iAmReady`）。
 *
 * **为什么个人字段必须走私有队列**：这些字段对每个玩家都不同。如果广播到公共主题，
 * 客户端会依次收到 `mySeatId = null/1/2/...`，最后一条覆盖前面的，
 * 于是房主看到「我」的标记落在别人的座位上（F-12）。
 */
export const useRoomStore = defineStore('room', () => {
  const auth = useAuthStore()

  /** 公共快照（所有人看到的内容一致）。 */
  const publicRoom = ref<RoomView | null>(null)
  /** 本人专属视图（只在收到私有推送时更新，用于拿到 mySeatId / iAmReady）。 */
  const privateRoom = ref<RoomView | null>(null)
  /** 对外暴露的合并视图：页面只读这个。 */
  const current = computed<RoomView | null>(() => {
    if (!publicRoom.value) {
      return privateRoom.value
    }
    if (!privateRoom.value || privateRoom.value.roomCode !== publicRoom.value.roomCode) {
      return publicRoom.value
    }
    return {
      ...publicRoom.value,
      mySeatId: privateRoom.value.mySeatId ?? publicRoom.value.mySeatId,
      iAmReady: privateRoom.value.iAmReady ?? publicRoom.value.iAmReady,
      iAmHost: privateRoom.value.iAmHost ?? publicRoom.value.iAmHost
    }
  })

  const publicRooms = ref<RoomView[]>([])
  const notices = ref<string[]>([])
  const loading = ref(false)

  let unsubscribers: Array<() => void> = []

  /**
   * 我坐哪个座位。
   *
   * <p>优先用后端的 `mySeatId`（私有队列给的，权威）；
   * 若私有推送还没到（刚进房间的那一瞬间），就用「座位里的 userId == 我的 userId」推导兜底。
   * 两条路径都不依赖"谁是最后一个加入的人"，因此不会串视角。
   */
  const mySeatId = computed<number | null>(() => {
    const view = current.value
    if (!view) {
      return null
    }
    if (view.mySeatId != null) {
      return view.mySeatId
    }
    const me = auth.user?.id
    if (me == null) {
      return null
    }
    return view.seats?.find((seat) => seat.userId === me)?.seatId ?? null
  })

  /** 我是否是房主。 */
  const isHost = computed(
    () => current.value?.iAmHost === true || (mySeatId.value != null
      && current.value?.seats?.find((s) => s.seatId === mySeatId.value)?.host === true)
  )

  /** 我是否已准备。 */
  const isReady = computed(() => current.value?.iAmReady === true)

  function setCurrent(view: RoomView | null) {
    if (!view) {
      publicRoom.value = null
      privateRoom.value = null
      return
    }
    publicRoom.value = view
    privateRoom.value = null
  }

  /** 进入房间后订阅房间主题。 */
  function subscribeRoom(roomCode: string) {
    unsubscribeRoom()
    // 1) 公共快照：不含个人视角
    unsubscribers.push(
      ws.subscribeWith(WsTopic.room(roomCode), (envelope) => {
        if (envelope.type === 'ROOM_UPDATE') {
          const incoming = envelope.data as RoomView
          // 只接受当前房间的更新（避免切换房间时的旧消息覆盖）
          if (!incoming?.roomCode || incoming.roomCode === publicRoom.value?.roomCode
              || !publicRoom.value) {
            publicRoom.value = incoming
          }
        } else if (envelope.type === 'NOTICE') {
          const data = envelope.data as { message: string }
          pushNotice(data?.message)
        }
      })
    )
    // 2) 本人专属视图（mySeatId / iAmReady）
    //    /user/queue/room 由服务端按已认证会话映射到当前用户。
    const myUserId = auth.user?.id
    if (myUserId == null) {
      console.warn('[room] 未取到 userId，私有房间视图不可用，将依赖 userId 兜底推导座位')
    } else {
      unsubscribers.push(
        ws.subscribeWith(WsUserQueue.room, (envelope) => {
          if (envelope.type !== 'ROOM_UPDATE') {
            return
          }
          const incoming = envelope.data as RoomView
          if (!incoming?.roomCode || incoming.roomCode === publicRoom.value?.roomCode
              || !publicRoom.value) {
            privateRoom.value = incoming
          }
        })
      )
    }
    // 主动请求一次全量同步（断线重连/刷新页面场景）
    ws.publish(WsSend.sync(roomCode), {})
    ws.publish(WsSend.me(roomCode), {})
  }

  function unsubscribeRoom() {
    unsubscribers.forEach((off) => off())
    unsubscribers = []
  }

  function resetLocal() {
    unsubscribeRoom()
    setCurrent(null)
    notices.value = []
  }

  function pushNotice(message?: string) {
    if (!message) {
      return
    }
    notices.value = [...notices.value.slice(-49), message]
  }

  // --------------------------------------------------------------- 操作 ---

  async function createRoom(payload: CreateRoomPayload) {
    loading.value = true
    try {
      const room = await roomApi.create(payload)
      setCurrent(room)
      return room
    } finally {
      loading.value = false
    }
  }

  async function joinRoom(roomCode: string, password?: string) {
    loading.value = true
    try {
      const room = await roomApi.join(roomCode, password)
      setCurrent(room)
      return room
    } finally {
      loading.value = false
    }
  }

  async function matchRoom(payload?: import('@/types').MatchRoomPayload) {
    loading.value = true
    try {
      const matched = await roomApi.match(payload)
      setCurrent(matched)
      return matched
    } finally {
      loading.value = false
    }
  }

  /** 取消刚分配的匹配房；失败时保留当前房间供用户手动处理。 */
  async function cancelMatchedRoom(roomCode: string) {
    await roomApi.leave(roomCode)
    setCurrent(null)
  }

  async function loadPublicRooms() {
    publicRooms.value = await lobbyApi.rooms()
    return publicRooms.value
  }

  /** 刷新后恢复"我在哪个房间"。 */
  async function restoreCurrent() {
    try {
      const room = await roomApi.current()
      setCurrent(room)
      return room
    } catch {
      setCurrent(null)
      return null
    }
  }

  async function updateRoom(roomCode: string, payload: UpdateRoomPayload) {
    const room = await roomApi.update(roomCode, payload)
    setCurrent(room)
    return room
  }

  async function setReady(roomCode: string, ready: boolean) {
    // 优先 WebSocket（低延迟），失败由 HTTP 兜底
    if (ws.connected) {
      ws.publish(WsSend.ready(roomCode), { ready })
      // 乐观更新：只改本人私有视图里的 iAmReady，等服务端私有推送到达后以服务端为准
      if (privateRoom.value) {
        privateRoom.value = { ...privateRoom.value, iAmReady: ready }
      } else if (publicRoom.value) {
        privateRoom.value = { ...publicRoom.value, iAmReady: ready }
      }
      return
    }
    const room = await roomApi.ready(roomCode, ready)
    setCurrent(room)
  }

  async function leaveRoom(roomCode: string) {
    unsubscribeRoom()
    try {
      await roomApi.leave(roomCode)
    } catch {
      /* 房间可能已被解散 */
    }
    setCurrent(null)
  }

  async function kick(roomCode: string, seatId: number) {
    await roomApi.kick(roomCode, seatId)
    ElMessage.success(`已移出 ${seatId} 号座位`)
  }

  async function dissolve(roomCode: string) {
    await roomApi.dissolve(roomCode)
    setCurrent(null)
  }

  async function ready(roomCode: string) {
    const room = await roomApi.ready(roomCode, true)
    setCurrent(room)
  }

  return {
    current,
    /** 公共快照与本人私有视图（排障时可能有用）。 */
    publicRoom,
    privateRoom,
    /** 我坐哪个座位（权威值 + userId 兜底推导）。 */
    mySeatId,
    /** 我是否是房主。 */
    isHost,
    /** 我是否已准备。 */
    isReady,
    publicRooms,
    notices,
    loading,
    subscribeRoom,
    unsubscribeRoom,
    resetLocal,
    pushNotice,
    createRoom,
    joinRoom,
    matchRoom,
    cancelMatchedRoom,
    loadPublicRooms,
    restoreCurrent,
    updateRoom,
    setReady,
    leaveRoom,
    kick,
    dissolve,
    ready
  }
})
