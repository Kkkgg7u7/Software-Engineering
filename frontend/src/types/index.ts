/**
 * 与后端 DTO 严格对应的 TypeScript 类型定义。
 * 后端契约文档：docs/API.md、docs/WEBSOCKET.md；改动时两边必须同步。
 */

// ============================================================== 通用 =====

/** 后端统一响应体 { code, message, data, timestamp }。 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

/** 业务错误码（与后端 ErrorCode.java 一致）。 */
export const ErrorCode = {
  BAD_REQUEST: 1000,
  UNAUTHORIZED: 1001,
  FORBIDDEN: 1002,
  NOT_FOUND: 1003,
  INTERNAL_ERROR: 1004,
  RATE_LIMITED: 1005,
  USERNAME_TAKEN: 2000,
  NICKNAME_TAKEN: 2001,
  USER_NOT_FOUND: 2002,
  PASSWORD_WRONG: 2003,
  ACCOUNT_DISABLED: 2004,
  /** 该账号已在其他设备登录，当前登录已失效。 */
  SESSION_REPLACED: 2005,
  ROOM_NOT_FOUND: 3000,
  ROOM_PASSWORD_WRONG: 3001,
  ROOM_FULL: 3002,
  ROOM_NOT_WAITING: 3003,
  NOT_IN_ROOM: 3004,
  NOT_HOST: 3005,
  ALREADY_IN_ROOM: 3006,
  PLAYER_NOT_READY: 3007,
  ROOM_CONFIG_INVALID: 3008,
  GAME_NOT_FOUND: 4000,
  GAME_ALREADY_STARTED: 4001,
  GAME_NOT_YOUR_TURN: 4002,
  GAME_WRONG_PHASE: 4003,
  GAME_ROLE_NOT_ALLOWED: 4004,
  GAME_PLAYER_DEAD: 4005,
  GAME_TARGET_INVALID: 4006,
  GAME_ALREADY_ACTED: 4007,
  GAME_POTION_USED: 4008,
  GAME_ALREADY_VOTED: 4009,
  AI_UNAVAILABLE: 5000,
  AI_OUTPUT_INVALID: 5001
} as const

/** 登录态失效的错误码：前端统一清理并跳登录页。 */
export const AUTH_ERROR_CODES: number[] = [
  ErrorCode.UNAUTHORIZED,
  // 该账号已在别处登录，当前 Token 已失效 —— 同样需要重新登录
  ErrorCode.SESSION_REPLACED
]

// ============================================================== 用户 =====

export interface UserInfo {
  id: number
  username: string
  nickname: string
  avatar?: string
  signature?: string
  createdAt?: string
  updatedAt?: string
  totalGames?: number
  winGames?: number
}

export interface AuthResponse {
  token: string
  expiresIn: number
  user: UserInfo
}

export interface RegisterPayload {
  username: string
  password: string
  nickname: string
  avatar?: string
}

export interface LoginPayload {
  username: string
  password: string
}

// ============================================================== 房间 =====

export interface SeatView {
  seatId: number
  userId?: number
  nickname: string
  avatar?: string
  ai: boolean
  ready: boolean
  alive: boolean
  role?: string | null
  host: boolean
}

export interface MatchRoomPayload { gameType?: string; maxPlayers?: number; werewolfCount?: number; privateChatEnabled?: boolean
  voiceEnabled?: boolean }

export type RoleCounts = Record<RoleType, number>

export interface RoomView {
  roleCounts: RoleCounts
  gameType: string
  allowInvites: boolean
  privateChatEnabled: boolean
  voiceEnabled?: boolean
  createdAt: string
  configuration: Record<string, unknown>
  id: number
  roomCode: string
  roomName: string
  hasPassword: boolean
  maxPlayers: number
  werewolfCount: number
  aiCount: number
  allowAi: boolean
  publicRoom: boolean
  status: 'WAITING' | 'PLAYING' | 'ENDED'
  hostUserId?: number
  hostNickname?: string
  playerCount: number
  humanCount: number
  readyCount: number
  allReady: boolean
  seats: SeatView[]
  mySeatId?: number | null
  iAmHost: boolean
  iAmReady: boolean
}

export interface CreateRoomPayload {
  roleCounts?: RoleCounts
  gameType?: string
  allowInvites?: boolean
  privateChatEnabled?: boolean
  voiceEnabled?: boolean
  roomName: string
  password?: string
  maxPlayers: number
  werewolfCount: number
  aiCount: number
  allowAi: boolean
  publicRoom: boolean
}

export interface UpdateRoomPayload {
  roleCounts?: RoleCounts
  resetRoleCounts?: boolean
  allowInvites?: boolean
  privateChatEnabled?: boolean
  voiceEnabled?: boolean
  roomName?: string
  maxPlayers?: number
  werewolfCount?: number
  aiCount?: number
  allowAi?: boolean
  publicRoom?: boolean
  password?: string
  clearPassword?: boolean
}

// ============================================================== 游戏 =====

/** 游戏阶段（与后端 GamePhase 一致）。 */
export type GamePhase =
  | 'WAITING'
  | 'STARTING'
  /** 身份确认：全部真人确认后才进入第一晚。 */
  | 'CONFIRM'
  | 'NIGHT'
  | 'DAY'
  | 'SHERIFF_VOTING'
  | 'DEATH_SKILL'
  | 'DISCUSSION'
  | 'VOTING'
  | 'CHECK_WINNER'
  | 'END'

/** 行动类型（与后端 ActionType 一致）。 */
export type ActionType =
  | 'WOLF_KILL'
  | 'SEER_CHECK'
  | 'WITCH_HEAL'
  | 'WITCH_POISON'
  | 'WITCH_PASS'
  /** 确认自己的身份（身份确认阶段）。 */
  | 'CONFIRM_ROLE'
  /** 声明自己本阶段操作完了（夜晚 / 投票）。 */
  | 'PHASE_DONE'
  /** 预言家确认已看过翻牌结果。 */
  | 'SEER_CONFIRM'
  | 'SPEAK'
  /** 结束/跳过自己的发言轮次（不发内容）。 */
  | 'SPEAK_PASS'
  | 'VOTE'
  | 'ABSTAIN'
  | 'GUARD_PROTECT' | 'GUARD_PASS' | 'CUPID_LINK' | 'SHERIFF_VOTE' | 'SHERIFF_ABSTAIN'
  | 'HUNTER_SHOOT' | 'WHITE_WOLF_CARRY' | 'SKILL_PASS' | 'BADGE_TRANSFER' | 'BADGE_DESTROY'

/** 角色（与后端 RoleType 一致）。 */
export type RoleType = 'WEREWOLF' | 'SEER' | 'WITCH' | 'VILLAGER' | 'WHITE_WOLF_KING' | 'HUNTER' | 'GUARD' | 'IDIOT' | 'CUPID'

export type Camp = 'WEREWOLF' | 'VILLAGE' | 'LOVERS'

export interface PlayerView {
  idiotRevealed?: boolean
  seatId: number
  userId?: number
  nickname: string
  avatar?: string
  alive: boolean
  ready: boolean
  ai: boolean
  speaking: boolean
  voted: boolean
  /** 是否已确认身份（身份确认阶段）。 */
  confirmed?: boolean
  /** 仅 gameOver=true 时由后端回填。 */
  role?: RoleType | null
}

export interface CheckRecord {
  round: number
  seatId: number
  nickname: string
  wolf: boolean
  label: string
}

/** 仅本人可见的私有信息。 */
export interface PrivateView {
  loverSeatId?: number | null
  lastGuardSeatId?: number | null
  cupidFirstSeatId?: number | null
  witchVictimSeatId?: number | null
  role: RoleType
  roleLabel: string
  camp: Camp
  skill: string
  assetName: string
  wolfTeammates: number[]
  healPotionAvailable?: boolean
  poisonPotionAvailable?: boolean
  checkRecords: CheckRecord[]
  availableActions: ActionType[]
  selectableTargets: number[]
}

/** 预言家查验后的翻牌展示信息。 */
export interface SeerReveal {
  targetSeatId: number | null
  targetNickname: string | null
  targetAvatar?: string | null
  /** true = 狼人（红色调），false = 好人（蓝色调）。 */
  wolf: boolean
  /** 阵营英文名：WEREWOLF / VILLAGE。 */
  camp: string
  /** 剩余确认秒数（服务端权威）。 */
  secondsLeft: number
  /** 总秒数，用于画进度。 */
  totalSeconds: number
}

export interface GameView {
  sheriffSeatId?: number | null
  sheriffVoteRound?: number
  sheriffCandidates?: number[]
  skillActorSeatId?: number | null
  skillKind?: string | null
  roomId: number
  roomCode: string
  roomName: string
  phase: GamePhase
  phaseLabel: string
  round: number
  phaseSecondsLeft: number
  maxPlayers: number
  werewolfCount: number
  gameOver: boolean
  winner?: Camp | null
  winnerLabel?: string | null
  dayAnnounced: boolean
  currentSpeakerSeatId?: number | null
  currentSpeakerNickname?: string | null
  lastExiledSeatId?: number | null
  lastNightDeaths: number[]

  // ---- 身份确认阶段（phase === 'CONFIRM'）----
  /** 已确认身份的玩家数。 */
  confirmedCount: number
  /** 需要确认身份的总人数（真人；AI 不计入）。 */
  confirmTargetCount: number
  /** 尚未确认身份的真人座位号。 */
  unconfirmedSeats: number[]
  /** 我是否已确认身份。 */
  iConfirmed?: boolean | null

  // ---- 阶段"完成"门（夜晚 / 投票）----
  /** 本阶段还需要点"完成"的座位号。 */
  pendingActorSeats: number[]
  /** 本阶段需要操作的总人数。 */
  actorCount: number
  /** 本阶段已点"完成"的座位号。 */
  phaseDoneSeats: number[]
  /** 我是否已经点过"完成"。 */
  iPhaseDone?: boolean | null

  // ---- 预言家翻牌确认（只对预言家本人下发）----
  /** 是否正在等待预言家确认查验结果。 */
  seerRevealPending?: boolean | null
  /** 翻牌动画所需的全部信息。 */
  seerReveal?: SeerReveal | null

  // ---- 狼队刀口协商（只对狼人下发）----
  /** 狼队刀口票数：座位号字符串 -> 票数。 */
  wolfVoteCounts?: Record<string, number> | null
  /** 狼队是否已就刀口达成一致；false（含平票）会导致本晚空刀。 */
  wolvesAgreed?: boolean | null

  players: PlayerView[]
  voteCounts: Record<string, number>
  votedCount: number
  aliveCount: number
  abstainCount: number
  me?: PrivateView | null
  notices: string[]
}

export interface ActionPayload {
  action: ActionType
  targetSeatId?: number | null
  speech?: string | null
}

export interface SpeechRecord {
  round: number
  seatId: number
  nickname: string
  content: string
  ai: boolean
}

// ============================================================== 事件 =====

export type GameEventType =
  | 'CUPID_LINKED' | 'GUARD_ACTION' | 'IDIOT_REVEALED' | 'SHERIFF_RESULT' | 'DEATH_SKILL_START' | 'BADGE_CHANGED'
  | 'GAME_START'
  | 'PHASE_CHANGE'
  /** 进入身份确认阶段。 */
  | 'ROLE_CONFIRM_START'
  /** 某位玩家确认了身份（auto=true 表示超时自动确认）。 */
  | 'ROLE_CONFIRMED'
  /** 某位玩家在本阶段点了"完成"（auto=true 表示超时自动完成）。 */
  | 'PHASE_DONE_BY'
  /** 预言家查验出结果，进入翻牌确认窗口。 */
  | 'SEER_REVEAL'
  /** 预言家确认看过结果（auto=true 表示 30 秒超时自动确认）。 */
  | 'SEER_REVEAL_CONFIRMED'
  | 'NIGHT_START'
  | 'ROLE_SKILL'
  | 'SEER_RESULT'
  | 'WITCH_ACTION'
  | 'DAWN'
  | 'PEACEFUL_NIGHT'
  | 'SPEAK_TURN'
  | 'SPEECH'
  | 'SPEAK_SKIP'
  | 'VOTE_START'
  | 'VOTE_CAST'
  | 'VOTE_RESULT'
  | 'PLAYER_DIED'
  | 'GAME_OVER'

export interface GameEvent {
  eventType: GameEventType
  payload: Record<string, any>
  timestamp: number
  roomCode?: string
}

// ============================================================== 聊天 =====

export type MessageType = 'PUBLIC' | 'PRIVATE' | 'WOLF' | 'SYSTEM'

export interface ChatMessage {
  id?: number
  roomId: number
  senderId: number
  receiverId?: number | null
  content: string
  messageType: MessageType
  channel?: 'LOBBY' | 'GAME'
  senderNickname?: string
  isAi?: boolean
  createdAt?: string
}

export interface ChatSendPayload {
  content: string
  receiverId?: number | null
  type?: MessageType
}

// ======================================================= 元数据(图例) =====

export interface RoleMeta {
  maxCount: number
  name: RoleType
  label: string
  camp: Camp
  campLabel: string
  skill: string
  asset: string
  icon: string
  token: string
}

export interface PhaseMeta {
  name: GamePhase
  label: string
  isNight: boolean
}

export interface GameMatchRules {
  /** 最少人数。 */
  minPlayers: number
  /** 最多人数。 */
  maxPlayers: number
  /** 最少狼人数。 */
  minWerewolves: number
  /** 各人数下的狼人数上限速查表（key 为人数）。 */
  maxWerewolvesOf: Record<string, number>
  /** 必须保留的神职角色。 */
  requiredSpecialRoles: RoleType[]
  werewolfRule: string
  winnerRule: string
}

export interface GameMeta {
  roleDefaults: Record<string, Record<string, RoleCounts>>
  roles: RoleMeta[]
  phases: PhaseMeta[]
  actions: ActionType[]
  aiProvider: string
  llmEnabled: boolean
  cardBack: string
  /** 开局约束（人数 / 狼人数 / 胜负规则），前端据此做表单联动校验。 */
  rules?: GameMatchRules
  effects: Record<string, string>
}

// ======================================================== WebSocket 报文 ===

export interface WsEnvelope<T = any> {
  type:
    | 'ROOM_UPDATE'
    | 'GAME_STATE'
    | 'GAME_EVENT'
    | 'CHAT_MESSAGE'
    | 'NOTICE'
    | 'ERROR'
    | 'KICKED'
    | 'FRIEND_UPDATE'
    | 'CONVERSATION_UPDATE'
      | 'VOICE_SIGNAL'
      | 'VOICE_MEMBERS'
      | 'VOICE_CLOSED'
  data: T
  timestamp: number
}
