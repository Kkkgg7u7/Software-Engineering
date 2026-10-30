import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { gameApi } from '@/api/game'
import { chatApi } from '@/api/chat'
import ws, { WsSend, WsTopic, WsUserQueue } from '@/websocket/stomp'
import { useAuthStore } from './auth'
import { useChatStore } from './chat'
import type {
  ActionPayload,
  ActionType,
  GameEvent,
  GameMeta,
  GameView,
  PrivateView,
  SpeechRecord
} from '@/types'

/** 特效播放请求（由 EffectManager 消费）。 */
export interface EffectRequest {
  key: string
  seatId?: number | null
  text?: string
}

/**
 * 对局状态（成员 D + F 负责模块）。
 *
 * 关键设计：
 * 1. **私有状态与公开状态分开存放**：`publicState` 来自 /topic/room/{code}/game，
 *    `privateState` 来自 /user/queue/game（含本人身份、药水、可选行动）。
 *    合并后的 `state` 才是页面真正渲染的对象。
 * 2. 事件队列 `pendingEffects` 交给 EffectManager 播放预生成 AI 素材特效。
 * 3. 所有行动都通过 WebSocket 提交（低延迟），HTTP 仅作兜底与调试。
 */
export const useGameStore = defineStore('game', () => {
  const auth = useAuthStore()
  const chat = useChatStore()

  const publicState = ref<GameView | null>(null)
  const privateState = ref<GameView | null>(null)
  const speeches = ref<SpeechRecord[]>([])
  const eventLog = ref<Array<{ time: number; text: string; type: string }>>([])
  const pendingEffects = ref<EffectRequest[]>([])
  const meta = ref<GameMeta | null>(null)
  const connected = ref(false)
  const starting = ref(false)
  /** 当前选中的目标座位（点击头像选择）。 */
  const selectedTarget = ref<number | null>(null)

  let offs: Array<() => void> = []
  let currentCode = ''

  /** 合并后的完整状态：私有信息优先。 */
  function merged(): GameView | null {
    if (!privateState.value) {
      return publicState.value
    }
    if (!publicState.value) {
      return privateState.value
    }
    return {
      ...publicState.value,
      ...privateState.value,
      // players 以公开信息为准（私有状态也带 players，但可能略旧）
      players: publicState.value.players?.length
        ? publicState.value.players
        : privateState.value.players,
      me: privateState.value.me ?? publicState.value.me
    }
  }

  const state = computed(() => merged())
  const me = computed<PrivateView | null>(() => merged()?.me ?? null)
  const mySeatId = computed<number | null>(() => {
    const s = merged()
    if (!s) {
      return null
    }
    const mine = s.players?.find((p) => p.userId === auth.user?.id)
    return mine?.seatId ?? null
  })
  const myPlayer = computed(() => {
    const s = merged()
    const seat = mySeatId.value
    return seat ? s?.players.find((p) => p.seatId === seat) ?? null : null
  })
  const isMyTurn = computed(() => {
    const s = merged()
    return !!s && s.currentSpeakerSeatId != null && s.currentSpeakerSeatId === mySeatId.value
  })
  const availableActions = computed<ActionType[]>(() => me.value?.availableActions ?? [])

  /** 初始化：订阅游戏状态、私密事件、错误。 */
  function attach(roomCode: string) {
    detach()
    currentCode = roomCode
    // 公共状态：房间内所有人一致
    offs.push(
      ws.subscribeWith(WsTopic.game(roomCode), (envelope) => {
        if (envelope.type === 'GAME_STATE') {
          publicState.value = envelope.data as GameView
        }
      })
    )
    // 私有状态（本人身份 / 药水 / 可选行动）按已认证会话投递。
    const myUserId = auth.user?.id
    if (myUserId == null) {
      console.warn('[game] 未取到 userId，无法订阅私有队列，身份牌与操作面板将不可用')
    } else {
      offs.push(
        ws.subscribeWith(WsUserQueue.game, (envelope) => {
          if (envelope.type === 'GAME_STATE') {
            privateState.value = envelope.data as GameView
          }
        })
      )
    }
    const handleEvent = (envelope: { type: string; data: unknown }) => {
      if (envelope.type !== 'GAME_EVENT') {
        return
      }
      handleGameEvent(envelope.data as GameEvent)
    }
    offs.push(ws.subscribeWith(WsTopic.event(roomCode), handleEvent))
    if (myUserId != null) {
      // 私密事件（预言家查验结果、女巫用药）也走用户队列
      offs.push(ws.subscribeWith(WsUserQueue.event, handleEvent))
      offs.push(
        ws.subscribeWith(WsUserQueue.error, (envelope) => {
          const data = envelope.data as { code?: number; message?: string }
          if (data?.message) {
            pushLog(data.message, 'important')
          }
        })
      )
    }
    offs.push(
      ws.onStatus((isConnected) => {
        connected.value = isConnected
        if (isConnected) {
          ws.publish(WsSend.sync(roomCode), {})
          ws.publish(WsSend.me(roomCode), {})
          // 重连后立刻补一次 HTTP 全量状态，避免错过私有推送
          void refreshState()
        }
      })
    )
    // 主动拉一次（防止连接建立前的状态丢失）
    ws.publish(WsSend.sync(roomCode), {})
    void refreshState()
    void loadSpeeches()
    void loadMeta()
  }

  function detach() {
    offs.forEach((off) => off())
    offs = []
    currentCode = ''
  }

  function reset() {
    detach()
    publicState.value = null
    privateState.value = null
    speeches.value = []
    eventLog.value = []
    pendingEffects.value = []
    selectedTarget.value = null
  }

  // ------------------------------------------------------------- 事件处理 --

  /** 事件 -> 特效 + 日志（方案第九节：GameEngine 事件驱动前端动画）。 */
  function handleGameEvent(event: GameEvent) {
    const p = event.payload || {}
    switch (event.eventType) {
      case 'GAME_START':
        pushEffect('GAME_START')
        pushLog(`第 ${p.round ?? 0} 轮开始，共 ${p.playerCount} 名玩家（${p.werewolfCount} 狼）`)
        break
      case 'ROLE_CONFIRM_START':
        // 发牌完成，进入身份确认：请所有人看清自己的身份
        pushLog('请确认你的身份，全部确认后进入第一晚', 'important')
        pushEffect('GAME_START')
        break
      case 'ROLE_CONFIRMED': {
        const mine = p.seatId === mySeatId.value
        const auto = p.auto === true
        pushLog(
          auto
            ? `${p.seatId} 号（${p.nickname}）超时未确认，已自动确认`
            : `${p.seatId} 号（${p.nickname}）已确认身份${mine ? '（你）' : ''}`,
          auto ? 'important' : 'info'
        )
        break
      }
      case 'PHASE_DONE_BY': {
        const auto = p.auto === true
        pushLog(
          auto
            ? `${p.seatId} 号（${p.nickname}）超时未操作，已自动完成`
            : `${p.seatId} 号（${p.nickname}）已完成本阶段操作`,
          auto ? 'important' : 'info'
        )
        break
      }
      case 'SEER_REVEAL':
        // 只发给预言家本人：提示他正在看翻牌结果
        pushLog(`查验结果：${p.targetSeatId} 号是${p.wolf ? '狼人' : '好人'}`, 'important')
        break
      case 'SEER_REVEAL_CONFIRMED':
        if (p.auto === true) {
          pushLog('翻牌确认超时，已自动继续', 'important')
        }
        break
      case 'NIGHT_START':
        pushEffect('NIGHT_START')
        pushLog(`第 ${p.round} 夜降临，请闭眼`)
        break
      case 'ROLE_SKILL':
        if (p.effect === 'KILL') {
          pushEffect('WOLF_KILL', p.targetSeatId)
          pushLog(`狼人选择了 ${p.targetSeatId ?? '?'} 号作为目标`, 'wolf')
        }
        break
      case 'SEER_RESULT':
        pushEffect('SEER_CHECK', p.targetSeatId)
        pushLog(
          `查验结果：${p.targetSeatId} 号 ${p.targetNickname} 是${p.wolf ? '狼人' : '好人'}`,
          'private'
        )
        break
      case 'WITCH_ACTION':
        if (p.effect === 'HEAL') {
          pushEffect('WITCH_HEAL', p.targetSeatId)
          pushLog(`女巫使用解药救下 ${p.targetSeatId} 号`, 'private')
        } else if (p.effect === 'POISON') {
          pushEffect('WITCH_POISON', p.targetSeatId)
          pushLog(`女巫使用毒药毒杀 ${p.targetSeatId} 号`, 'private')
        } else {
          pushLog('女巫本晚没有使用药水', 'private')
        }
        break
      case 'DAWN':
        pushEffect('DAWN')
        pushLog(`天亮了，昨晚死亡：${(p.deaths || []).join('、')} 号`)
        break
      case 'PEACEFUL_NIGHT':
        pushEffect('DAWN')
        pushLog('昨晚是平安夜，无人死亡')
        break
      case 'SPEAK_TURN':
        pushLog(`轮到 ${p.seatId} 号（${p.nickname}）发言`)
        break
      case 'SPEECH':
        speechHandler(p)
        break
      case 'SPEAK_SKIP':
        // reason: PASS = 本人主动点「结束发言」；TIMEOUT = 5 分钟倒计时走完
        if (p.reason === 'PASS') {
          pushLog(`${p.seatId} 号（${p.nickname}）结束了发言`)
        } else {
          pushLog(`${p.seatId} 号（${p.nickname}）发言超时，已跳过`, 'important')
        }
        break
      case 'VOTE_START':
        pushEffect('VOTE_START')
        pushLog('进入投票阶段')
        break
      case 'VOTE_CAST':
        pushLog(`${p.actorSeatId} 号已完成投票`)
        break
      case 'VOTE_RESULT':
        if (p.exiledSeatId) {
          pushEffect('VOTE_EXILE', p.exiledSeatId)
          pushLog(`${p.exiledSeatId} 号（${p.exiledNickname}）被投票放逐，得票 ${p.votes}`)
        } else if (p.tie) {
          pushLog('投票平票，本轮无人出局')
        }
        break
      case 'CUPID_LINKED': pushLog(`情侣已连接：${(p.lovers ?? []).join(" 号与 ")} 号`, 'private'); break
      case 'IDIOT_REVEALED': pushLog(`${p.seatId} 号白痴翻牌，失去正常投票权`); break
      case 'SHERIFF_RESULT': pushLog(`第 ${p.round} 轮警长选举计票完成${p.tied?.length > 1 ? '，候选人平票' : ''}`); break
      case 'DEATH_SKILL_START': pushLog(`等待 ${p.actorSeatId ?? p.seatId} 号处理死亡技能或警徽`); break
      case 'BADGE_CHANGED': pushLog(p.seatId ? `警长为 ${p.seatId} 号（1.5 票）` : '警徽已撕毁'); break
      case 'PLAYER_DIED':
        pushEffect('PLAYER_DIED', p.seatId)
        pushLog(`${p.seatId} 号（${p.nickname}）出局（${({VOTE:'被放逐',NIGHT:'夜间死亡',LOVER:'殉情',HUNTER:'猎人开枪',WHITE_WOLF:'白狼王带人'} as Record<string,string>)[p.cause] ?? '出局'}）`)
        break
      case 'GAME_OVER':
        pushEffect(p.winner === 'WEREWOLF' ? 'WIN_WEREWOLF' : 'WIN_GOOD')
        pushLog(
          `游戏结束：${p.winner === 'LOVERS' ? '情侣阵营' : p.winner === 'WEREWOLF' ? '狼人阵营' : '好人阵营'}胜利`,
          'important'
        )
        void refreshState()
        break
      default:
        break
    }
    // GAME_STATE 会随后到达，这里只做轻量刷新
  }

  function speechHandler(p: Record<string, any>) {
    const record: SpeechRecord = {
      round: p.round,
      seatId: p.seatId,
      nickname: p.nickname,
      content: p.content,
      ai: !!p.ai
    }
    if (!speeches.value.some((s) => s.round === record.round && s.seatId === record.seatId
      && s.content === record.content)) {
      speeches.value = [...speeches.value, record]
    }
    pushLog(`${p.seatId} 号：${p.content}`)
  }

  function pushEffect(key: string, seatId?: number | null, text?: string) {
    pendingEffects.value = [...pendingEffects.value, { key, seatId, text }]
  }

  function consumeEffect(): EffectRequest | null {
    if (!pendingEffects.value.length) {
      return null
    }
    const [first, ...rest] = pendingEffects.value
    pendingEffects.value = rest
    return first
  }

  function pushLog(text: string, type = 'info') {
    if (!text) {
      return
    }
    eventLog.value = [...eventLog.value.slice(-199), { time: Date.now(), text, type }]
  }

  // --------------------------------------------------------------- 操作 ---

  async function loadMeta() {
    if (meta.value) {
      return meta.value
    }
    try {
      meta.value = await gameApi.meta()
    } catch {
      meta.value = null
    }
    return meta.value
  }

  async function refreshState() {
    try {
      const s = await gameApi.state()
      if (s) {
        privateState.value = s
      }
      return s
    } catch {
      return null
    }
  }

  async function loadSpeeches() {
    try {
      speeches.value = await gameApi.speeches()
    } catch {
      speeches.value = []
    }
  }

  async function startGame() {
    starting.value = true
    try {
      const s = await gameApi.start()
      privateState.value = s
      ElMessage.success('游戏开始，请查看你的身份')
      return s
    } finally {
      starting.value = false
    }
  }

  /** 提交行动：WebSocket 优先，未连接则走 HTTP。 */
  async function act(action: ActionType, targetSeatId?: number | null, speech?: string) {
    const payload: ActionPayload = {
      action,
      targetSeatId: targetSeatId ?? null,
      speech: speech ?? null
    }
    if (ws.connected && currentCode) {
      ws.publish(WsSend.action(currentCode), payload)
      selectedTarget.value = null
      return null
    }
    const s = await gameApi.action(payload)
    privateState.value = s
    selectedTarget.value = null
    return s
  }

  async function voteSkip() {
    if (ws.connected && currentCode) {
      ws.publish(WsSend.voteSkip(currentCode), {})
      return
    }
    await act('ABSTAIN')
  }

  async function resetGame() {
    await gameApi.reset()
    publicState.value = null
    privateState.value = null
    eventLog.value = []
    speeches.value = []
    pendingEffects.value = []
  }

  function selectTarget(seatId: number | null) {
    selectedTarget.value = seatId
  }

  /** 该座位是否是当前可选的合法目标。 */
  function isSelectable(seatId: number): boolean {
    const targets = me.value?.selectableTargets ?? []
    return targets.includes(seatId)
  }

  /** 是否有需要选择目标才能执行的行动。 */
  function needsTarget(): boolean {
    return availableActions.value.some((a) =>
      (['WOLF_KILL', 'SEER_CHECK', 'WITCH_POISON', 'VOTE', 'GUARD_PROTECT', 'CUPID_LINK', 'SHERIFF_VOTE', 'HUNTER_SHOOT', 'WHITE_WOLF_CARRY', 'BADGE_TRANSFER'] as ActionType[]).includes(a)
    )
  }

  // ---------------------------------------------------- 身份确认阶段 ----

  /** 是否处于身份确认阶段。 */
  const inConfirmPhase = computed(() => merged()?.phase === 'CONFIRM')

  /** 我是否已经确认过身份。 */
  const iConfirmed = computed(() => merged()?.iConfirmed === true)

  /** 尚未确认身份的座位号（用于提示"还等谁"）。 */
  const unconfirmedSeats = computed<number[]>(() => merged()?.unconfirmedSeats ?? [])

  /** 提交身份确认。 */
  async function confirmRole() {
    return act('CONFIRM_ROLE')
  }

  // ---------------------------------------------------- 阶段完成门 ----

  /** 本阶段还需要点"完成"的座位号。 */
  const pendingActorSeats = computed<number[]>(() => merged()?.pendingActorSeats ?? [])

  /** 我是否已经点过本阶段的"完成"。 */
  const iPhaseDone = computed(() => merged()?.iPhaseDone === true)

  /** 本阶段需要操作的总人数。 */
  const actorCount = computed(() => merged()?.actorCount ?? 0)

  /** 声明"我本阶段操作完了"；全员完成后服务端会立即推进阶段。 */
  async function phaseDone() {
    return act('PHASE_DONE')
  }

  // ------------------------------------------------ 预言家翻牌确认 ----

  /** 翻牌展示信息（仅预言家本人有值）。 */
  const seerReveal = computed(() => merged()?.seerReveal ?? null)

  /** 是否正在等待我确认查验结果。 */
  const seerRevealPending = computed(() => merged()?.seerRevealPending === true)

  /** 确认已看过查验结果；服务端随即继续夜晚流程。 */
  async function confirmSeerReveal() {
    return act('SEER_CONFIRM')
  }

  // ---------------------------------------------------- 狼队刀口协商 ----

  /**
   * 狼队刀口票数（仅狼人有值）。
   *
   * <p>规则是"少数服从多数"：所有狼人各自选目标，票数最高者生效；
   * <b>平票则本晚空刀</b>。所以狼人需要看到队友选了什么，否则容易出现
   * "我以为队友会跟我选同一个"结果空刀。
   */
  const wolfVoteCounts = computed<Record<string, number>>(
    () => merged()?.wolfVoteCounts ?? {}
  )

  /** 狼队是否已就刀口达成一致；false = 平票或还没投完（平票会空刀）。 */
  const wolvesAgreed = computed(() => merged()?.wolvesAgreed === true)

  /** 我是否能拿到狼队信息（非狼人拿到的是 null）。 */
  const hasWolfInfo = computed(() => merged()?.wolfVoteCounts != null)

  async function sendChat(content: string) {
    await chat.send(content)
  }

  async function loadChatHistory(roomCode: string) {
    try {
      const history = await chatApi.history(roomCode, 150)
      return history
    } catch {
      return []
    }
  }

  return {
    publicState,
    privateState,
    speeches,
    eventLog,
    pendingEffects,
    meta,
    connected,
    starting,
    selectedTarget,
    state,
    me,
    mySeatId,
    myPlayer,
    isMyTurn,
    availableActions,
    attach,
    detach,
    reset,
    loadMeta,
    refreshState,
    loadSpeeches,
    startGame,
    act,
    voteSkip,
    resetGame,
    selectTarget,
    isSelectable,
    needsTarget,
    inConfirmPhase,
    iConfirmed,
    unconfirmedSeats,
    confirmRole,
    pendingActorSeats,
    iPhaseDone,
    actorCount,
    phaseDone,
    seerReveal,
    seerRevealPending,
    confirmSeerReveal,
    wolfVoteCounts,
    wolvesAgreed,
    hasWolfInfo,
    consumeEffect,
    pushEffect,
    pushLog,
    sendChat,
    loadChatHistory
  }
})
