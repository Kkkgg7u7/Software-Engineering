<script setup lang="ts">
/**
 * 对局页（P0 核心闭环的主界面）。
 *
 * 结构：
 *   顶栏（阶段倒计时 + 房间码 + 退出）
 *   左栏（事件日志 / 发言记录）  中栏（圆形牌桌）  右栏（身份卡 + 行动面板 + 聊天）
 *
 * 设计取舍：
 * - 座位位置用 `rotate(θ) translate(radius) rotate(-θ)` 计算，radius 由容器实测宽度决定，
 *   因此座位卡片在任意尺寸下都保持正立且均匀分布；
 * - 行动面板完全按后端下发的 `me.availableActions` 渲染，前端不猜阶段；
 * - 观战（无座位 / 已出局）只隐藏行动面板，公开信息一切照常显示。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import EffectManager from '@/components/EffectManager.vue'
import PhaseTimer from '@/components/PhaseTimer.vue'
import PlayerSeat from '@/components/PlayerSeat.vue'
import RoleCard from '@/components/RoleCard.vue'
import SeerRevealOverlay from '@/components/SeerRevealOverlay.vue'
import ChatPanel from '@/components/ChatPanel.vue'
import VoicePanel from '@/components/VoicePanel.vue'
import GameLogPanel from '@/components/GameLogPanel.vue'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'
import { useGameStore } from '@/stores/game'
import { useChatStore } from '@/stores/chat'
import type { ActionType, PlayerView, RoleType, RoomView } from '@/types'

const props = defineProps<{ roomCode: string }>()

const auth = useAuthStore()
const room = useRoomStore()
const game = useGameStore()
const chat = useChatStore()
const router = useRouter()

const code = computed(() => props.roomCode.toUpperCase())
const state = computed(() => game.state)
const isHost = computed(() => room.isHost)

function errorText(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallback
}

// ------------------------------------------------------------ 牌桌布局 ----

const stageRef = ref<HTMLDivElement | null>(null)
const stageSize = ref(600)
let observer: ResizeObserver | null = null

/** 座位环半径：随牌桌宽度自适应，最小保证 4 人局不重叠。 */
const radius = computed(() => stageSize.value < 360
  ? Math.max(80, stageSize.value * 0.34)
  : Math.max(96, stageSize.value * 0.375))

interface PositionedSeat {
  player: PlayerView
  angle: number
}

/** 按座位号升序，从正上方开始顺时针均匀分布。 */
const positionedSeats = computed<PositionedSeat[]>(() => {
  const list = [...(state.value?.players ?? [])].sort((a, b) => a.seatId - b.seatId)
  const count = list.length
  if (!count) {
    return []
  }
  return list.map((player, index) => ({
    player,
    angle: (360 / count) * index - 90
  }))
})

const aliveCount = computed(() => state.value?.aliveCount ?? 0)
const isNight = computed(() => state.value?.phase === 'NIGHT')
const sceneClass = computed(() => {
  if (state.value?.gameOver) return 'game--result'
  if (state.value?.phase === 'NIGHT') return 'game--night'
  if (['DAY', 'DISCUSSION', 'VOTING', 'SHERIFF_VOTING'].includes(state.value?.phase ?? '')) return 'game--day'
  return 'game--playing'
})

const phaseHint = computed(() => {
  const value = state.value
  if (!value) {
    return '正在同步对局状态…'
  }
  if (value.gameOver) {
    return '对局已结束'
  }
  switch (value.phase) {
    case 'CONFIRM':
      return game.iConfirmed ? '已确认身份，等待其他人' : '请确认你的身份'
    case 'NIGHT':
      return '天黑请闭眼'
    case 'DAY':
      return '天亮了'
    case 'DISCUSSION':
      return value.currentSpeakerSeatId != null ? `${value.currentSpeakerSeatId} 号发言中` : '讨论阶段'
    case 'VOTING':
      return '投票阶段'
    case 'STARTING':
      return '正在发牌…'
    case 'SHERIFF_VOTING': return '警长选举：每人一票，可投自己；平票重投一次'
    case 'DEATH_SKILL': return `等待 ${state.value?.skillActorSeatId} 号处理技能或警徽`
    case 'CHECK_WINNER':
      return '正在结算…'
    case 'END':
      return '对局结束'
    default:
      return '等待开始'
  }
})

const phaseSubHint = computed(() => {
  const value = state.value
  if (!value || value.gameOver) {
    return ''
  }
  if (value.phase === 'CONFIRM') {
    const pending = game.unconfirmedSeats
    if (game.iConfirmed) {
      return pending.length ? `还等 ${pending.join('、')} 号确认` : '即将进入第一晚'
    }
    return '看清你的身份牌后点击「我已确认身份」'
  }
  if (value.phase === 'DISCUSSION' && value.currentSpeakerNickname) {
    return `等待 ${value.currentSpeakerNickname} 发言`
  }
  if (value.phase === 'VOTING') {
    return `已投票 ${value.votedCount} 人 · 弃票 ${value.abstainCount} 人`
  }
  if (value.phase === 'DAY' && value.lastNightDeaths?.length) {
    return `昨夜出局：${value.lastNightDeaths.join('、')} 号`
  }
  return ''
})

function voteCountOf(seatId: number): number {
  const counts = state.value?.voteCounts
  if (!counts) {
    return 0
  }
  return counts[String(seatId)] ?? 0
}

// ------------------------------------------------------------ 行动面板 ----

const specialActionDefinitions: {action:ActionType;label:string;target:boolean}[] = [
 {action:'GUARD_PROTECT',label:'确认守护',target:true},{action:'GUARD_PASS',label:'空守',target:false},
 {action:'CUPID_LINK',label:'选择这名情侣',target:true},{action:'SHERIFF_VOTE',label:'选为警长',target:true},{action:'SHERIFF_ABSTAIN',label:'选举弃票',target:false},
 {action:'HUNTER_SHOOT',label:'猎人开枪',target:true},{action:'WHITE_WOLF_CARRY',label:'白狼王带人',target:true},{action:'SKILL_PASS',label:'放弃技能',target:false},
 {action:'BADGE_TRANSFER',label:'移交警徽',target:true},{action:'BADGE_DESTROY',label:'撕毁警徽',target:false}]
const specialActions=computed(()=>specialActionDefinitions.filter(x=>has(x.action)))
const speechText = ref('')
const acting = ref(false)

const me = computed(() => game.me)
const mySeatId = computed(() => game.mySeatId)
const actions = computed<ActionType[]>(() => game.availableActions)
const selectableTargets = computed<number[]>(() => me.value?.selectableTargets ?? [])
const selectedTarget = computed<number | null>(() => game.selectedTarget)

/** 女巫解药只能救 selectableTargets[0]（后端已算好唯一合法目标）。 */
const healTarget = computed<number | null>(() => {
  return me.value?.witchVictimSeatId ?? null
})

function has(action: ActionType): boolean {
  return actions.value.includes(action)
}

/** 观战：没有座位或自己已出局。 */
const isSpectator = computed(() => {
  if (!me.value) {
    return true
  }
  if (mySeatId.value == null) {
    return false
  }
  return game.myPlayer?.alive === false && state.value?.skillActorSeatId !== mySeatId.value
})

const showActionPanel = computed(() => !!me.value && !isSpectator.value && actions.value.length > 0)

/** 当前行动是否需要点击座位选目标。 */
const needsTargetAction = computed(() =>
  actions.value.some(
    (action) =>
      action === 'WOLF_KILL' ||
      action === 'SEER_CHECK' ||
      action === 'WITCH_POISON' ||
      ['VOTE','GUARD_PROTECT','CUPID_LINK','SHERIFF_VOTE','HUNTER_SHOOT','WHITE_WOLF_CARRY','BADGE_TRANSFER'].includes(action)
  )
)

/** 该座位是否是当前行动的合法目标。 */
function canSelect(seatId: number): boolean {
  if (isSpectator.value || !actions.value.length || !needsTargetAction.value) {
    return false
  }
  const targets = selectableTargets.value
  if (targets.length) {
    return targets.includes(seatId)
  }
  return false
}

function onSeatSelect(seatId: number) {
  if (!canSelect(seatId)) {
    ElMessage.warning('该玩家不是当前行动的合法目标')
    return
  }
  game.selectTarget(selectedTarget.value === seatId ? null : seatId)
}

async function runAction(action: ActionType, target?: number | null, speech?: string) {
  acting.value = true
  try {
    await game.act(action, target ?? null, speech)
    speechText.value = ''
    ElMessage.success('已提交')
  } catch (error) {
    ElMessage.error(errorText(error, '行动提交失败'))
  } finally {
    acting.value = false
  }
}

function requireTarget(): number | null {
  if (selectedTarget.value == null) {
    ElMessage.warning('请先点击要选择的目标座位')
    return null
  }
  return selectedTarget.value
}

async function submitWolfKill() {
  const target = requireTarget()
  if (target == null) {
    return
  }
  await runAction('WOLF_KILL', target)
}

async function submitSeerCheck() {
  const target = requireTarget()
  if (target == null) {
    return
  }
  await runAction('SEER_CHECK', target)
}

async function submitWitchHeal() {
  const target = healTarget.value
  if (target == null) {
    ElMessage.warning('今晚没有可救治的目标')
    return
  }
  await runAction('WITCH_HEAL', target)
}

async function submitWitchPoison() {
  const target = requireTarget()
  if (target == null) {
    return
  }
  await runAction('WITCH_POISON', target)
}

async function submitWitchPass() {
  await runAction('WITCH_PASS', null)
}

async function submitVote() {
  const target = requireTarget()
  if (target == null) {
    return
  }
  await runAction('VOTE', target)
}

async function submitAbstain() {
  acting.value = true
  try {
    await game.voteSkip()
    ElMessage.success('已弃票')
  } catch (error) {
    ElMessage.error(errorText(error, '弃票失败'))
  } finally {
    acting.value = false
  }
}

async function submitSpeak() {
  const text = speechText.value.trim()
  if (!text) {
    ElMessage.warning('发言内容不能为空；想跳过请点「结束发言」')
    return
  }
  if (text.length > 500) {
    ElMessage.warning('发言不能超过 500 字')
    return
  }
  await runAction('SPEAK', null, text)
}

/** 结束自己的发言轮次（不发表内容），把剩余时间让给下一位。 */
async function submitSpeakPass() {
  const hasText = speechText.value.trim().length > 0
  if (hasText) {
    try {
      await ElMessageBox.confirm(
        '你输入的内容还没有发表，确定要直接结束发言吗？',
        '结束发言',
        { confirmButtonText: '结束发言', cancelButtonText: '继续写', type: 'warning' }
      )
    } catch {
      return
    }
  }
  await runAction('SPEAK_PASS')
  speechText.value = ''
}

function playerName(seatId: number): string {
  const found = state.value?.players?.find((item) => item.seatId === seatId)
  return found ? found.nickname : `${seatId} 号`
}

const teammateNames = computed(() =>
  (me.value?.wolfTeammates ?? []).map((seatId) => `${seatId} 号 ${playerName(seatId)}`)
)

/** 查验记录（避免在模板里对可能为 null 的 me 做深层访问）。 */
const checkRecords = computed(() => me.value?.checkRecords ?? [])

// ------------------------------------------------------ 狼队刀口协商 ----

/** 我是不是"正在夜晚的狼人"（此时要一直显示狼队面板，即使我已经出过刀）。 */
const isWolfNight = computed(
  () => ['WEREWOLF','WHITE_WOLF_KING'].includes(me.value?.role ?? '') && state.value?.phase === 'NIGHT' && !isSpectator.value
)

/** 刀口票数按座位号排序，便于对照牌桌。 */
const wolfTallyRows = computed(() =>
  Object.entries(game.wolfVoteCounts)
    .map(([seat, count]) => ({ seat: Number(seat), count }))
    .sort((a, b) => b.count - a.count || a.seat - b.seat)
)

/** 当前最高票数（用于标"领先"）。 */
const maxWolfVotes = computed(() =>
  wolfTallyRows.value.reduce((max, row) => Math.max(max, row.count), 0)
)

// -------------------------------------------------------------- 身份卡 ----

const roleNameMap: Record<RoleType, string> = {
  WHITE_WOLF_KING:'白狼王',HUNTER:'猎人',GUARD:'守卫',IDIOT:'白痴',CUPID:'爱神',
  WEREWOLF: '狼人',
  SEER: '预言家',
  WITCH: '女巫',
  VILLAGER: '村民'
}

function roleLabel(role?: RoleType | null): string {
  if (!role) {
    return '未知'
  }
  return roleNameMap[role] ?? '未知'
}

function roleTagClass(role?: RoleType | null): string {
  switch (role) {
    case 'WEREWOLF':
      return 'ww-tag--wolf'
    case 'SEER':
      return 'ww-tag--seer'
    case 'WITCH':
      return 'ww-tag--witch'
    case 'VILLAGER':
      return 'ww-tag--villager'
    default:
      return 'ww-tag--dead'
  }
}

// -------------------------------------------------------------- 结算 ----

const settlementVisible = ref(false)

const settlementPlayers = computed(() =>
  [...(state.value?.players ?? [])].sort((a, b) => a.seatId - b.seatId)
)

function isMe(player: PlayerView): boolean {
  return !!auth.user?.id && player.userId === auth.user.id
}

const iWon = computed(() => {
  const winner = state.value?.winner
  const camp = me.value?.camp
  if (!winner || !camp) {
    return null
  }
  return winner === camp
})

watch(
  () => state.value?.gameOver,
  (over) => {
    if (over) {
      settlementVisible.value = true
    }
  }
)

async function restartGame() {
  if (!isHost.value) {
    ElMessage.info('请等待房主开启下一局')
    return
  }
  try {
    await game.resetGame()
    settlementVisible.value = false
    await router.push({ name: 'room', params: { roomCode: code.value } })
  } catch (error) {
    ElMessage.error(errorText(error, '重置对局失败'))
  }
}

function closeSettlement() {
  settlementVisible.value = false
}

// ---------------------------------------------------------- 身份确认 ----

/** 身份确认面板是否显示：只在确认阶段且对局未结束时出现。 */
const confirmVisible = computed({
  get: () => game.inConfirmPhase && !state.value?.gameOver,
  // el-dialog 会写回 false（用户点遮罩/ESC），这里忽略——面板由阶段驱动，
  // 不允许手动关掉，否则玩家可能没看身份就进了第一晚。
  set: () => {}
})

const confirming = ref(false)

const confirmAllDone = computed(
  () => (state.value?.confirmTargetCount ?? 0) > 0
    && (state.value?.confirmedCount ?? 0) >= (state.value?.confirmTargetCount ?? 0)
)

const confirmPercent = computed(() => {
  const total = state.value?.confirmTargetCount ?? 0
  if (total <= 0) {
    return 0
  }
  return Math.min(100, Math.round(((state.value?.confirmedCount ?? 0) / total) * 100))
})

async function handleConfirmRole() {
  confirming.value = true
  try {
    await game.confirmRole()
    // WebSocket 未连接时 act() 走 HTTP，返回值已经是新状态
    ElMessage.success('身份已确认，等待其他玩家')
  } catch (error) {
    ElMessage.error(errorText(error, '确认失败，请稍后重试'))
  } finally {
    confirming.value = false
  }
}

// -------------------------------------------------------- 阶段完成门 ----

/**
 * 是否显示"我完成了"按钮。
 *
 * <p>夜晚与投票阶段都由"全员完成 / 倒计时耗尽"驱动推进，
 * 因此当前玩家如果还有操作没做完，就必须先操作再点完成。
 */
const showPhaseDone = computed(() => {
  const phase = state.value?.phase
  if (state.value?.gameOver) {
    return false
  }
  if (phase !== 'NIGHT' && phase !== 'VOTING') {
    return false
  }
  // 自己不是本阶段的相关人员（例如平民在夜晚）时不显示
  return (state.value?.actorCount ?? 0) > 0 && isPhaseActor.value
})

/** 我是否属于本阶段需要操作的人。 */
const isPhaseActor = computed(() => {
  const seat = mySeatId.value
  if (seat == null || !state.value) {
    return false
  }
  // 已完成的座位也在 phaseDoneSeats 里；两类加起来才是全部相关人员
  const done = state.value.phaseDoneSeats ?? []
  const pending = state.value.pendingActorSeats ?? []
  return done.includes(seat) || pending.includes(seat)
})

const iPhaseDone = computed(() => state.value?.iPhaseDone === true)

const pendingNames = computed(() => {
  const seats = state.value?.pendingActorSeats ?? []
  if (!seats.length) {
    return '其他人完成后即进入下一步'
  }
  return seats.filter((s) => s !== mySeatId.value).map((s) => `${s} 号`).join('、') || '即将推进'
})

const phaseDoneLoading = ref(false)

async function handlePhaseDone() {
  phaseDoneLoading.value = true
  try {
    await game.phaseDone()
    ElMessage.success('已提交，等待其他玩家')
  } catch (error) {
    ElMessage.error(errorText(error, '提交失败，请稍后重试'))
  } finally {
    phaseDoneLoading.value = false
  }
}

// ---------------------------------------------------- 预言家翻牌确认 ----

const seerConfirming = ref(false)

async function handleSeerConfirm() {
  seerConfirming.value = true
  try {
    await game.confirmSeerReveal()
  } catch (error) {
    ElMessage.error(errorText(error, '确认失败，请稍后重试'))
  } finally {
    seerConfirming.value = false
  }
}

// -------------------------------------------------------------- 生命周期 --

function exitToLobby() {
  void router.push({ name: 'lobby' })
}

function exitToRoom() {
  void router.push({ name: 'room', params: { roomCode: code.value } })
}

onMounted(async () => {
  game.attach(code.value)

  if (!room.current) {
    try {
      await room.restoreCurrent()
    } catch {
      /* 忽略：聊天只依赖 roomCode */
    }
  }
  if(!room.current||room.current.roomCode!==code.value){
    game.detach();ElMessage.info('房间已结束或当前账号不在该房间');await router.replace({name:'lobby'});return
  }
  const roomView: RoomView = room.current ?? ({ roomCode: code.value } as unknown as RoomView)
  try {
    await chat.enterRoom(roomView)
  } catch {
    ElMessage.error('聊天记录加载失败，实时消息仍可用')
  }

  await game.loadMeta()

  // 座位环半径随容器实测尺寸变化
  const stage = stageRef.value
  if (stage) {
    stageSize.value = stage.clientWidth || stageSize.value
    observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) {
        stageSize.value = entry.contentRect.width
      }
    })
    observer.observe(stage)
  }
})

onUnmounted(() => {
  observer?.disconnect()
  observer = null
  game.detach()
  chat.leaveRoom()
})

/** 狼人频道开关：只有狼人可用（好人页签置灰）。 */
watch(
  () => me.value?.role,
  (role) => {
    chat.wolfChatEnabled = role === 'WEREWOLF' || role === 'WHITE_WOLF_KING'
  },
  { immediate: true }
)
</script>

<template>
  <div class="game" :class="sceneClass">
    <!--
      特效层：EffectManager 自身的样式表（src/styles/effects.css）当前没有被任何入口 import，
      这里用一个固定定位的容器兜住它，保证特效素材不会挤乱牌桌布局；
      容器 pointer-events: none，所以永远不会挡住操作。
    -->
    <div class="game__fx">
      <EffectManager />
    </div>

    <!-- 顶栏 -->
    <header class="game__top ww-panel">
      <PhaseTimer
        :phase="state?.phase ?? 'WAITING'"
        :phase-label="state?.phaseLabel ?? '等待开始'"
        :round="state?.round ?? 0"
        :seconds-left="state?.phaseSecondsLeft ?? 0"
        :alive-count="aliveCount"
        :actor-count="state?.actorCount ?? 0"
        :done-count="state?.phaseDoneSeats?.length ?? 0"
        :pending-seats="state?.pendingActorSeats ?? []"
      />
      <span class="ww-spacer" />
      <div class="game__top-right">
        <span class="game__room ww-mono">{{ code }}</span>
        <span class="ww-muted">{{ state?.roomName || '对局中' }}</span>
        <el-button size="small" text @click="exitToLobby">退出到大厅</el-button>
      </div>
    </header>

    <div class="game__body">
      <!-- 左栏：日志 -->
      <aside class="game__left">
        <GameLogPanel class="game__panel" />
      </aside>

      <!-- 中栏：牌桌 + 行动面板 -->
      <section class="game__center">
        <div ref="stageRef" class="table" :class="{ 'table--large': positionedSeats.length > 6 }">
          <div class="table__felt" />
          <div v-if="isNight" class="ww-night-veil table__veil" />

          <div class="table__hint">
            <div class="table__hint-main" :class="{ 'ww-pulse': isNight }">{{ phaseHint }}</div>
            <div v-if="phaseSubHint" class="table__hint-sub">{{ phaseSubHint }}</div>
            <div v-if="state" class="table__hint-meta ww-muted">
              第 {{ state.round }} 轮 · 存活 {{ aliveCount }} 人
              <span v-if="state.sheriffSeatId"> · 警长 {{ state.sheriffSeatId }} 号（1.5 票）</span>
              <span v-if="state.players.some(p=>p.idiotRevealed)"> · 翻牌白痴：{{ state.players.filter(p=>p.idiotRevealed).map(p=>p.seatId).join('、') }} 号</span>
            </div>
          </div>

          <div
            v-for="item in positionedSeats"
            :key="item.player.seatId"
            class="table__seat"
            :style="{
              transform: `translate(-50%, -50%) rotate(${item.angle}deg) translate(${radius}px) rotate(${-item.angle}deg)`
            }"
          >
            <PlayerSeat
              :player="item.player"
              :seat-id="item.player.seatId"
              :is-me="item.player.seatId === mySeatId"
              :is-speaking="state?.currentSpeakerSeatId === item.player.seatId"
              :is-selected="selectedTarget === item.player.seatId"
              :selectable="showActionPanel && canSelect(item.player.seatId) && !has('VOTE')"
              :votable="has('VOTE') && canSelect(item.player.seatId)"
              :show-role="!!state?.gameOver"
              :vote-count="voteCountOf(item.player.seatId)"
              @select="onSeatSelect"
            />
          </div>

          <el-skeleton v-if="!positionedSeats.length" class="table__skeleton" :rows="4" animated />
        </div>

        <!-- 行动面板 -->
        <section class="acting ww-panel ww-panel--pad">
          <div class="ww-panel__title">
            我的行动
            <span class="ww-spacer" />
            <span v-if="state" class="ww-muted">{{ state.phaseLabel }}</span>
          </div>

          <!-- 状态未同步（例如对局已结束 / 自己是最后一个离开的人） -->
          <div v-if="!state" class="acting__idle">
            <p class="ww-muted">正在同步对局状态…如果一直没有变化，说明当前房间没有进行中的对局。</p>
            <el-button size="small" type="primary" text @click="exitToRoom">返回房间</el-button>
          </div>

          <!-- 观战 / 出局 -->
          <p v-else-if="isSpectator" class="ww-muted acting__idle">
            {{ me ? '你已出局，可以旁观并在聊天频道发言' : '你正在观战，等待对局结束' }}
          </p>

          <div v-else-if="specialActions.length" class="acting__block">
              <div class="acting__title">{{ state?.phaseLabel }} · {{ me?.roleLabel }}</div>
              <p v-if="has('CUPID_LINK')" class="ww-muted">先选第一名，再选第二名情侣（可选自己）。{{ me?.cupidFirstSeatId ? `已选 ${me.cupidFirstSeatId} 号，请选另一人` : '尚未选择' }}</p>
              <p v-if="has('GUARD_PROTECT')" class="ww-muted">不可连守；同守同救会死亡。上次守护：{{ me?.lastGuardSeatId ?? '无' }} 号。</p>
              <p v-if="state?.phase==='SHERIFF_VOTING'" class="ww-muted">第 {{ state.sheriffVoteRound }} 次选举。警长放逐票权 1.5，其他玩家 1。</p>
              <p v-if="selectedTarget" class="acting__target">目标：{{ selectedTarget }} 号 {{ playerName(selectedTarget) }}</p>
              <div class="acting__actions"><el-button v-for="item in specialActions" :key="item.action" :loading="acting" :disabled="item.target && !selectedTarget" @click="runAction(item.action,item.target?selectedTarget:null)">{{ item.label }}</el-button></div>
            </div>
            <!-- 狼人刀人 / 狼队协商 -->
          <div v-else-if="isWolfNight" class="acting__block">
            <div class="acting__title">
              {{ has('WOLF_KILL') ? '选择今晚要击杀的目标' : '今晚的刀口已提交' }}
            </div>
            <p v-if="has('WOLF_KILL')" class="ww-muted">点击牌桌上的座位选择目标，再点确认。</p>
            <p v-if="teammateNames.length" class="acting__teammates">
              狼队友：{{ teammateNames.join('、') }}
            </p>

            <div v-if="has('WOLF_KILL')" class="acting__actions">
              <span v-if="selectedTarget" class="acting__target">
                目标：{{ selectedTarget }} 号 {{ playerName(selectedTarget) }}
              </span>
              <span v-else class="ww-muted">尚未选择目标</span>
              <span class="ww-spacer" />
              <el-button type="danger" :loading="acting" @click="submitWolfKill">确认击杀</el-button>
            </div>

            <!-- 刀口票数：少数服从多数，平票会空刀，所以必须让狼人看到队友选了什么 -->
            <div v-if="game.hasWolfInfo" class="acting__wolf-tally">
              <div class="acting__subtitle">狼队刀口（全员一致才击杀）</div>
              <div v-if="wolfTallyRows.length" class="acting__vote-list">
                <span
                  v-for="row in wolfTallyRows"
                  :key="row.seat"
                  class="acting__vote-item"
                  :class="{ 'acting__vote-item--on': row.count > 0 }"
                >
                  {{ row.seat }} 号 {{ row.count }} 票
                  <template v-if="row.count === maxWolfVotes && wolfTallyRows.length > 1">
                    · 领先
                  </template>
                </span>
              </div>
              <p v-else class="ww-muted">还没有狼人提交目标</p>
              <p v-if="wolfTallyRows.length && !game.wolvesAgreed" class="acting__wolf-warn">
                ⚠ 狼队意见不一致，若持续到倒计时结束将<b>空刀</b>（今晚无人死亡）
              </p>
              <p v-else-if="game.wolvesAgreed" class="acting__wolf-ok">
                ✓ 狼队已达成一致
              </p>
              <p class="ww-muted acting__speak-hint">
                所有狼人各自选择，最终取票数最高者为刀口；平票则本晚空刀。
              </p>
            </div>
          </div>

          <!-- 预言家查验 -->
          <div v-else-if="has('SEER_CHECK')" class="acting__block">
            <div class="acting__title">选择要查验的玩家</div>
            <p class="ww-muted">查验结果只有你自己能看到。</p>
            <div class="acting__actions">
              <span v-if="selectedTarget" class="acting__target">
                目标：{{ selectedTarget }} 号 {{ playerName(selectedTarget) }}
              </span>
              <span v-else class="ww-muted">尚未选择目标</span>
              <span class="ww-spacer" />
              <el-button type="primary" :loading="acting" @click="submitSeerCheck">查验</el-button>
            </div>

            <div class="acting__records">
              <div class="acting__subtitle">历史查验</div>
              <table v-if="checkRecords.length" class="acting__table">
                <thead>
                  <tr>
                    <th>轮次</th>
                    <th>座位</th>
                    <th>结论</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(record, index) in checkRecords" :key="index">
                    <td>第 {{ record.round }} 轮</td>
                    <td>{{ record.seatId }} 号 {{ record.nickname }}</td>
                    <td :class="record.wolf ? 'acting__wolf' : 'acting__good'">
                      {{ record.label || (record.wolf ? '狼人' : '好人') }}
                    </td>
                  </tr>
                </tbody>
              </table>
              <p v-else class="ww-muted">还没有查验记录</p>
            </div>
          </div>

          <!-- 女巫 -->
          <div
            v-else-if="has('WITCH_HEAL') || has('WITCH_POISON') || has('WITCH_PASS')"
            class="acting__block"
          >
            <div class="acting__title">女巫用药</div>
            <div class="acting__potions">
              <span class="ww-tag" :class="me?.healPotionAvailable ? 'ww-tag--villager' : 'ww-tag--dead'">
                解药：{{ me?.healPotionAvailable ? '可用' : '已用完' }}
              </span>
              <span class="ww-tag" :class="me?.poisonPotionAvailable ? 'ww-tag--witch' : 'ww-tag--dead'">
                毒药：{{ me?.poisonPotionAvailable ? '可用' : '已用完' }}
              </span>
            </div>

            <div class="acting__actions">
              <el-button
                v-if="has('WITCH_HEAL')"
                type="success"
                :loading="acting"
                :disabled="healTarget == null"
                @click="submitWitchHeal"
              >
                使用解药
                <template v-if="healTarget != null">（救 {{ healTarget }} 号）</template>
              </el-button>
              <el-button
                v-if="has('WITCH_POISON')"
                type="danger"
                :loading="acting"
                :disabled="selectedTarget == null"
                @click="submitWitchPoison"
              >
                使用毒药<template v-if="selectedTarget != null">（毒 {{ selectedTarget }} 号）</template>
              </el-button>
              <el-button v-if="has('WITCH_PASS')" text :loading="acting" @click="submitWitchPass">
                不使用药水
              </el-button>
            </div>
            <p class="ww-muted">解药只能救今晚被袭击的玩家（已自动选定）；毒药点击座位选择目标。</p>
          </div>

          <!-- 发言 -->
          <div v-else-if="has('SPEAK')" class="acting__block">
            <div class="acting__title">
              {{ game.isMyTurn ? '轮到你发言' : '等待其他玩家发言' }}
            </div>
            <template v-if="game.isMyTurn">
              <el-input
                v-model="speechText"
                type="textarea"
                :rows="4"
                maxlength="500"
                show-word-limit
                resize="none"
                placeholder="请陈述你的推理，最多 500 字（也可以直接点「结束发言」跳过）"
                @keydown.ctrl.enter.prevent="submitSpeak"
              />
              <div class="acting__actions">
                <span class="ww-muted acting__speak-tip">
                  还剩 {{ state?.phaseSecondsLeft ?? 0 }} 秒 · Ctrl+Enter 快速发表
                </span>
                <span class="ww-spacer" />
                <el-button
                  :loading="acting"
                  @click="submitSpeakPass"
                >
                  {{ speechText.trim() ? '不发表，结束发言' : '结束发言' }}
                </el-button>
                <el-button
                  type="primary"
                  :loading="acting"
                  :disabled="!speechText.trim()"
                  @click="submitSpeak"
                >
                  发表
                </el-button>
              </div>
              <p v-if="!speechText.trim()" class="ww-muted acting__speak-hint">
                直接点「结束发言」可以把剩余时间让给下一位，不会留下空发言记录。
              </p>
            </template>
            <p v-else class="ww-muted acting__idle">
              等待 {{ state?.currentSpeakerNickname || '其他玩家' }} 发言
            </p>
          </div>

          <!-- 投票 -->
          <div v-else-if="has('VOTE') || has('ABSTAIN')" class="acting__block">
            <div class="acting__title">投票放逐</div>
            <p class="ww-muted">点击座位选择要放逐的玩家。</p>
            <div class="acting__actions">
              <span v-if="selectedTarget" class="acting__target">
                目标：{{ selectedTarget }} 号 {{ playerName(selectedTarget) }}
              </span>
              <span v-else class="ww-muted">尚未选择目标</span>
              <span class="ww-spacer" />
              <el-button
                v-if="has('VOTE')"
                type="danger"
                :loading="acting"
                @click="submitVote"
              >
                确认投票
              </el-button>
              <el-button v-if="has('ABSTAIN')" text :loading="acting" @click="submitAbstain">
                弃票
              </el-button>
            </div>

            <div v-if="state" class="acting__votes">
              <div class="acting__subtitle">实时票数</div>
              <div class="acting__vote-list">
                <span
                  v-for="player in settlementPlayers"
                  :key="player.seatId"
                  class="acting__vote-item"
                  :class="{ 'acting__vote-item--on': voteCountOf(player.seatId) > 0 }"
                >
                  {{ player.seatId }} 号 {{ voteCountOf(player.seatId) }} 票
                </span>
              </div>
            </div>
          </div>

          <!-- 身份确认阶段 -->
          <div v-else-if="game.inConfirmPhase" class="acting__block">
            <div class="acting__title">身份确认</div>
            <p class="ww-muted">
              看清右侧的身份牌后点击下方按钮确认；全部玩家确认后进入第一晚。
            </p>
            <div class="acting__actions">
              <span class="ww-muted">
                {{ state?.confirmedCount ?? 0 }} / {{ state?.confirmTargetCount ?? 0 }} 已确认
              </span>
              <span class="ww-spacer" />
              <el-button
                v-if="!game.iConfirmed"
                type="primary"
                :loading="confirming"
                @click="handleConfirmRole"
              >
                我已确认身份
              </el-button>
              <el-button v-else disabled type="success">✓ 已确认，等待其他人…</el-button>
            </div>
          </div>

          <!-- 无可用行动 -->
          <p v-else class="ww-muted acting__idle">
            {{
              state?.gameOver
                ? '对局已结束，可查看全员身份'
                : state?.phase === 'NIGHT'
                  ? '夜晚请等待其他玩家行动'
                  : '当前没有需要你执行的操作，请等待流程推进'
            }}
          </p>

          <!-- 阶段"完成"门：所有行动阶段都显示，点完成后才会推进到下一步 -->
          <div v-if="showPhaseDone" class="acting__done">
            <el-divider />
            <div class="acting__done-row">
              <span class="ww-muted acting__done-tip">
                {{
                  iPhaseDone
                    ? `你已完成，还等 ${pendingNames}`
                    : '操作完成后请点击右侧按钮；全部玩家完成后才会进入下一步'
                }}
              </span>
              <span class="ww-spacer" />
              <span class="ww-mono acting__done-count">
                {{ state?.phaseDoneSeats?.length ?? 0 }}/{{ state?.actorCount ?? 0 }}
              </span>
              <el-button
                v-if="!iPhaseDone"
                type="success"
                :loading="phaseDoneLoading"
                @click="handlePhaseDone"
              >
                我完成了
              </el-button>
              <el-button v-else disabled type="success">✓ 已完成</el-button>
            </div>
          </div>
        </section>
      </section>

      <!-- 右栏：身份卡 + 聊天 -->
      <aside class="game__right">
        <section class="ww-panel ww-panel--pad identity">
          <div class="ww-panel__title">我的身份</div>
          <template v-if="me">
            <div class="identity__card">
              <RoleCard
                :role="me.role"
                :asset-name="me.assetName"
                :label="me.roleLabel"
                :skill="me.skill"
                :camp="me.camp === 'LOVERS' ? '情侣阵营' : me.camp === 'WEREWOLF' ? '狼人阵营' : '好人阵营'"
                :revealed="true"
                size="md"
              />
            </div>
            <div class="identity__meta">
              <span class="ww-tag" :class="roleTagClass(me.role)">
                {{ me.roleLabel || roleLabel(me.role) }}
              </span>
              <span class="ww-tag" :class="me.camp === 'WEREWOLF' ? 'ww-tag--wolf' : 'ww-tag--villager'">
                {{ me.camp === 'LOVERS' ? '情侣阵营' : me.camp === 'WEREWOLF' ? '狼人阵营' : '好人阵营' }}
              </span>
              <span v-if="mySeatId != null" class="ww-tag ww-tag--dead">{{ mySeatId }} 号座位</span>
            </div>
            <p v-if="me.loverSeatId" class="ww-muted">你的情侣：{{ me.loverSeatId }} 号。一方死亡，另一方殉情。</p>
              <p class="identity__skill">{{ me.skill }}</p>
          </template>
          <el-empty v-else :image-size="60" description="观战中，暂无身份信息" />
        </section>

        <VoicePanel :room-code="code" :enabled="room.current?.voiceEnabled" />
          <ChatPanel class="game__panel chat-fill" :room-code="code" :players="state?.players ?? []" :private-chat-enabled="room.current?.privateChatEnabled" />
      </aside>
    </div>

    <!-- 结算面板 -->
    <el-dialog v-model="settlementVisible" title="对局结算" width="min(620px, 94vw)">
      <div class="settle">
        <div class="settle__winner" :class="state?.winner === 'WEREWOLF' ? 'settle__winner--wolf' : 'settle__winner--good'">
          {{ state?.winnerLabel || (state?.winner === 'WEREWOLF' ? '狼人阵营胜利' : '好人阵营胜利') }}
        </div>
        <p v-if="iWon !== null" class="settle__self">
          你的身份：<strong>{{ me?.roleLabel || roleLabel(me?.role) }}</strong>
          <span :class="iWon ? 'settle__win' : 'settle__lose'">{{ iWon ? '本局胜利 🎉' : '本局失败' }}</span>
        </p>

        <div class="settle__list">
          <div v-for="player in settlementPlayers" :key="player.seatId" class="settle__row">
            <span class="ww-seat-badge">{{ player.seatId }}</span>
            <el-avatar :size="24" :src="player.avatar">{{ player.nickname.slice(0, 1) }}</el-avatar>
            <span class="settle__name">{{ player.nickname }}</span>
            <span v-if="isMe(player)" class="ww-tag ww-tag--seer">我</span>
            <span v-if="player.ai" class="ww-tag ww-tag--ai">AI</span>
            <span v-if="!player.alive" class="ww-tag ww-tag--dead">出局</span>
            <span class="ww-spacer" />
            <span class="ww-tag" :class="roleTagClass(player.role)">{{ roleLabel(player.role) }}</span>
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="closeSettlement">留在本局</el-button>
        <el-button type="primary" @click="restartGame">
          {{ isHost ? '再来一局' : '等待房主开启下一局' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 身份确认面板：所有人都确认后才进入第一晚 -->
    <el-dialog
      v-model="confirmVisible"
      title="请确认你的身份"
      width="min(460px, 94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
    >
      <div class="confirm-panel">
        <p class="confirm-panel__tip">
          这是你的身份牌，<strong>只有你能看到</strong>。看清后点击下方按钮确认，
          全部玩家确认后才会进入第一晚。
        </p>

        <RoleCard
          v-if="me"
          :role="me.role"
          :asset-name="me.assetName"
          :role-label="me.roleLabel"
          :skill="me.skill"
          :camp="me.camp"
          :revealed="true"
          :flippable="false"
          size="md"
        />

        <div class="confirm-panel__progress">
          <el-progress
            :percentage="confirmPercent"
            :stroke-width="10"
            :show-text="false"
            :status="confirmAllDone ? 'success' : undefined"
          />
          <span class="confirm-panel__count">
            {{ state?.confirmedCount ?? 0 }} / {{ state?.confirmTargetCount ?? 0 }} 已确认
          </span>
        </div>

        <p v-if="game.unconfirmedSeats.length" class="confirm-panel__pending">
          还等这些玩家确认：{{ game.unconfirmedSeats.map((s) => `${s} 号`).join('、') }}
        </p>

        <p class="ww-muted confirm-panel__hint">
          倒计时结束后未确认的玩家会被自动确认（防止挂机卡住对局）。
        </p>
      </div>

      <template #footer>
        <el-button
          v-if="!game.iConfirmed"
          type="primary"
          size="large"
          :loading="confirming"
          @click="handleConfirmRole"
        >
          我已确认身份
        </el-button>
        <el-button v-else disabled size="large" type="success">
          ✓ 已确认，等待其他人…
        </el-button>
      </template>
    </el-dialog>
    <!-- 预言家翻牌结果：好人蓝色调 / 狼人红色调，30 秒确认窗口 -->
    <SeerRevealOverlay
      v-if="game.seerRevealPending && game.seerReveal"
      :target-seat-id="game.seerReveal.targetSeatId"
      :target-nickname="game.seerReveal.targetNickname"
      :target-avatar="game.seerReveal.targetAvatar"
      :wolf="game.seerReveal.wolf"
      :seconds-left="game.seerReveal.secondsLeft"
      :total-seconds="game.seerReveal.totalSeconds"
      :loading="seerConfirming"
      @confirm="handleSeerConfirm"
    />
  </div>
</template>

<style scoped>
.game {
  position: relative;
  isolation: isolate;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 14px clamp(10px, 2.4vw, 22px) 24px;
  max-width: 1680px;
  margin: 0 auto;
}

.game::before {
  content: '';
  position: fixed;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background: linear-gradient(rgba(5, 11, 29, .68), rgba(5, 11, 29, .8)), url('/assets/art/scenes/game.jpg') center / cover no-repeat;
}
.game--night::before { background-image: linear-gradient(rgba(5, 11, 29, .68), rgba(5, 11, 29, .8)), url('/assets/art/scenes/night.jpg'); }
.game--day::before { background-image: linear-gradient(rgba(5, 11, 29, .68), rgba(5, 11, 29, .8)), url('/assets/art/scenes/day.jpg'); }
.game--result::before { background-image: linear-gradient(rgba(5, 11, 29, .68), rgba(5, 11, 29, .8)), url('/assets/art/scenes/result.jpg'); }
.settle { padding: 14px; border-radius: 12px; background: linear-gradient(rgba(5, 11, 29, .82), rgba(5, 11, 29, .88)), url('/assets/art/scenes/result.jpg') center / cover no-repeat; }

/* ---------------------------------------------------------------- 顶栏 -- */
.game__top {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 12px 18px;
  flex-wrap: wrap;
}

.game__top-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.game__room {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 4px;
  color: var(--ww-moon);
}

/* ---------------------------------------------------------------- 布局 -- */
.game__body {
  display: grid;
  grid-template-columns: minmax(230px, 300px) minmax(0, 1fr) minmax(280px, 340px);
  gap: 14px;
  align-items: start;
}

.game__left,
.game__right {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.game__panel {
  min-height: 200px;
}

.game__left .game__panel {
  max-height: 74vh;
}

.chat-fill {
  max-height: 52vh;
}

.game__center {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

/* -------------------------------------------------------------- 特效层 -- */
/* 只做定位/尺寸兜底，不碰 effects.css 里的关键帧动画（若该表被引入则完全兼容）。 */
.game__fx {
  position: fixed;
  inset: 0;
  z-index: 3000;
  pointer-events: none;
}

.game__fx :deep(.ww-fx) {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.game__fx :deep(.ww-fx__bg) {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at 50% 50%, rgba(7, 10, 20, 0.72), var(--ww-bg));
}

.game__fx :deep(.ww-fx__art) {
  width: min(46vw, 380px);
  max-height: 62vh;
  object-fit: contain;
  border-radius: 12px;
  filter: drop-shadow(0 0 28px rgba(167, 139, 250, 0.55));
}

.game__fx :deep(.ww-fx__title) {
  margin-top: 14px;
  font-size: clamp(18px, 3vw, 34px);
  font-weight: 800;
  letter-spacing: 6px;
  color: var(--ww-moon);
  text-shadow: 0 0 22px rgba(124, 58, 237, 0.9);
}

.game__fx :deep(.ww-fx__subtitle) {
  margin-top: 4px;
  font-size: clamp(12px, 1.6vw, 16px);
  letter-spacing: 2px;
  color: var(--ww-text-dim);
}

.game__fx :deep(.ww-fx__skull) {
  font-size: clamp(64px, 12vw, 150px);
  filter: drop-shadow(0 0 24px rgba(148, 163, 184, 0.8));
}

/* -------------------------------------------------------------- 牌桌 -- */
.table {
  position: relative;
  width: 100%;
  max-width: 720px;
  aspect-ratio: 1;
  margin: 0 auto;
}

.table__felt {
  position: absolute;
  inset: 13%;
  border-radius: 50%;
  background: url('/assets/art/scenes/game.jpg') center / cover no-repeat,
    radial-gradient(circle at 50% 40%, rgba(124, 58, 237, 0.35), rgba(7, 10, 20, 0.9));
  border: 2px solid var(--ww-border);
  box-shadow: inset 0 0 60px rgba(2, 6, 23, 0.85), 0 0 60px rgba(124, 58, 237, 0.28);
}

.table__veil {
  position: absolute;
  inset: 6%;
  z-index: 1;
  border-radius: 50%;
  background: radial-gradient(circle at 50% 42%, rgba(30, 27, 75, 0.24), rgba(2, 4, 12, 0.74));
  pointer-events: none;
  transition: opacity 0.6s ease;
}

.table__hint {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 46%;
  text-align: center;
  pointer-events: none;
  z-index: 2;
}

.table__hint-main {
  font-size: clamp(16px, 2vw, 24px);
  font-weight: 800;
  letter-spacing: 4px;
  color: var(--ww-moon);
  text-shadow: 0 0 22px rgba(124, 58, 237, 0.75);
}

.table__hint-sub {
  margin-top: 6px;
  font-size: 12px;
  color: var(--ww-text-dim);
}

.table__hint-meta {
  margin-top: 4px;
  font-size: 11px;
}

.table__seat {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 116px;
  margin: 0;
  z-index: 3;
}

.table__skeleton {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 60%;
}

/* ---------------------------------------------------------- 行动面板 -- */
.acting {
  min-height: 150px;
}

.acting__block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.acting__title {
  font-size: 14px;
  font-weight: 700;
  color: var(--ww-moon);
  letter-spacing: 1px;
}

.acting__subtitle {
  font-size: 12px;
  font-weight: 700;
  color: var(--ww-text-dim);
  margin-bottom: 6px;
}

.acting__idle {
  margin: 0;
  line-height: 1.7;
}

/* ---------------------------------------------------------- 发言 ---- */

.acting__speak-tip {
  font-size: 12px;
}

.acting__speak-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

/* ---------------------------------------------------- 狼队刀口协商 ---- */

.acting__wolf-tally {
  margin-top: 12px;
}

.acting__wolf-warn {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-color-warning);
}

.acting__wolf-ok {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-color-success);
}

/* ---------------------------------------------------------- 身份确认 ---- */

.confirm-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.confirm-panel__tip {
  margin: 0;
  line-height: 1.7;
}

.confirm-panel__progress {
  display: flex;
  align-items: center;
  gap: 12px;
}

.confirm-panel__progress :deep(.el-progress) {
  flex: 1;
}

.confirm-panel__count {
  flex: none;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.confirm-panel__pending {
  margin: 0;
  color: var(--el-color-warning);
  line-height: 1.6;
}

.confirm-panel__hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}

/* -------------------------------------------------------- 完成门 ---- */

.acting__done {
  margin-top: 4px;
}

.acting__done :deep(.el-divider) {
  margin: 12px 0;
}

.acting__done-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.acting__done-tip {
  line-height: 1.6;
}

.acting__done-count {
  font-variant-numeric: tabular-nums;
  font-weight: 700;
}

.acting__teammates {
  margin: 0;
  font-size: 12px;
  color: var(--ww-wolf);
}

.acting__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.acting__target {
  font-size: 13px;
  color: var(--ww-moon);
  font-weight: 700;
}

.acting__potions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.acting__records {
  margin-top: 6px;
  padding-top: 8px;
  border-top: 1px dashed var(--ww-border-soft);
}

.acting__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.acting__table th,
.acting__table td {
  text-align: left;
  padding: 4px 6px;
  border-bottom: 1px solid var(--ww-border-soft);
  color: var(--ww-text-dim);
}

.acting__table th {
  color: var(--ww-text-mute);
  font-weight: 600;
}

.acting__wolf {
  color: var(--ww-wolf);
  font-weight: 700;
}

.acting__good {
  color: var(--ww-villager);
  font-weight: 700;
}

.acting__votes {
  margin-top: 6px;
  padding-top: 8px;
  border-top: 1px dashed var(--ww-border-soft);
}

.acting__vote-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.acting__vote-item {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--ww-border-soft);
  color: var(--ww-text-mute);
}

.acting__vote-item--on {
  color: var(--ww-wolf);
  border-color: rgba(239, 68, 68, 0.45);
  background: var(--ww-wolf-soft);
  font-weight: 700;
}

/* -------------------------------------------------------------- 身份 -- */
.identity__card {
  display: flex;
  justify-content: center;
  margin-bottom: 10px;
}

.identity__meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.identity__skill {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ww-text-dim);
}

/* -------------------------------------------------------------- 结算 -- */
.settle {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.settle__winner {
  font-size: 24px;
  font-weight: 800;
  letter-spacing: 6px;
  text-align: center;
  padding: 10px;
  border-radius: var(--ww-radius);
}

.settle__winner--wolf {
  color: var(--ww-wolf);
  background: var(--ww-wolf-soft);
}

.settle__winner--good {
  color: var(--ww-villager);
  background: var(--ww-villager-soft);
}

.settle__self {
  margin: 0;
  text-align: center;
  font-size: 13px;
  color: var(--ww-text-dim);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
}

.settle__win {
  color: var(--ww-moon);
  font-weight: 700;
}

.settle__lose {
  color: var(--ww-text-mute);
}

.settle__list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 42vh;
  overflow-y: auto;
}

.settle__row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: var(--ww-radius-sm);
  background: var(--ww-panel-2);
  border: 1px solid var(--ww-border-soft);
}

.settle__name {
  font-size: 13px;
  color: var(--ww-text);
}

/* ------------------------------------------------------------ 响应式 -- */
@media (max-width: 1280px) {
  .game__body {
    grid-template-columns: minmax(0, 1fr) minmax(280px, 340px);
    grid-template-areas:
      'center right'
      'left right';
  }

  .game__left {
    grid-area: left;
  }

  .game__center {
    grid-area: center;
  }

  .game__right {
    grid-area: right;
  }
}

@media (max-width: 900px) {
  .game__body {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      'center'
      'right'
      'left';
  }

  .table__seat {
    width: 96px;
  }

  .chat-fill {
    max-height: 46vh;
  }

  .game__left .game__panel {
    max-height: 42vh;
  }
}
@media (max-width: 600px) {
 .table--large { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px 8px; aspect-ratio:auto; padding:12px 0; }
 .table--large .table__felt, .table--large .table__veil { display:none; }
 .table--large .table__hint { position:static; transform:none; width:auto; grid-column:1 / -1; padding:12px; border:1px solid var(--ww-border); border-radius:12px; }
 .table--large .table__seat { position:static; width:auto; transform:none !important; }
 .table--large :deep(.ww-seat) { width:100%; min-height:116px; }
}
</style>
