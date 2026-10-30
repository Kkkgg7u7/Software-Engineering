<script setup lang="ts">
/**
 * 房间等待页：座位网格 + 房主配置 + 房间聊天 + 准备/开始。
 *
 * 设计取舍：
 * - 进入页面时先尝试 `restoreCurrent()`（刷新 / 从大厅跳回来的常见路径），只有确认不在
 *   目标房间时才走 join；需要密码时弹出内联密码框而不是直接报错；
 * - 房主配置用一份本地副本编辑、点「保存配置」才提交，避免每改一个数字就发一次请求；
 * - 房主开始前做前端校验（人数 ≥ 4、全员已准备），后端仍会再校验一次。
 */
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import RoomSeatGrid from '@/components/RoomSeatGrid.vue'
import RoleCountsEditor from '@/components/RoleCountsEditor.vue'
import { roleConfigurationError, sameRoles } from '@/game/roleConfiguration'
import RoomInviteDialog from '@/components/RoomInviteDialog.vue'
import ChatPanel from '@/components/ChatPanel.vue'
import VoicePanel from '@/components/VoicePanel.vue'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'
import { useGameStore } from '@/stores/game'
import { useChatStore } from '@/stores/chat'
import type { PlayerView, SeatView, RoleCounts } from '@/types'

const props = defineProps<{ roomCode: string }>()

const auth = useAuthStore()
const room = useRoomStore()
const game = useGameStore()
const chat = useChatStore()
const router = useRouter()

const MIN_PLAYERS = 4
const MAX_PLAYERS = 13

const code = computed(() => props.roomCode.toUpperCase())
const current = computed(() => room.current)
const isHost = computed(() => room.isHost)
const isReady = computed(() => room.isReady)

/**
 * 需要"准备"的玩家 = 除房主以外的所有人（真人 + AI）。
 *
 * <p><b>房主不需要准备</b>：他是发起开局的人。早期后端要求房主也准备，
 * 但房主的操作条里没有准备按钮，结果房主把自己卡在"未准备"上、点开始只弹
 * "还有玩家未准备"却无从解决（F-14）。现在后端已豁免房主，前端也按同一口径显示。
 */
const seatsNeedingReady = computed(() => (current.value?.seats ?? []).filter((s) => !s.host))

/** 除房主外已准备的人数。 */
const readyAmongPlayers = computed(
  () => seatsNeedingReady.value.filter((s) => s.ready).length
)

/** 除房主外需要准备的总人数。 */
const needReadyCount = computed(() => seatsNeedingReady.value.length)

/** 是否可以开局：人数够 + 除房主外都已准备。 */
const canStart = computed(
  () =>
    !configDirty.value && !roleError.value && (current.value?.playerCount ?? 0) === current.value?.maxPlayers &&
    needReadyCount.value > 0 &&
    readyAmongPlayers.value === needReadyCount.value
)

/** 还没准备的人的名字，直接告诉房主卡在谁身上。 */
const unreadyNames = computed(() =>
  seatsNeedingReady.value.filter((s) => !s.ready).map((s) => s.nickname)
)

/** 传给 ChatPanel 的玩家列表（房间座位 -> PlayerView）。 */
const players = computed<PlayerView[]>(() =>
  (current.value?.seats ?? []).map((seat: SeatView) => ({
    seatId: seat.seatId,
    userId: seat.userId,
    nickname: seat.nickname,
    avatar: seat.avatar,
    alive: true,
    ready: seat.ready,
    ai: seat.ai,
    speaking: false,
    voted: false
  }))
)

// ------------------------------------------------------------ 进入房间 ----

const inviteVisible=ref(false)
async function copyInviteLink(){try{await navigator.clipboard.writeText(`${location.origin}/join/${code.value}`);ElMessage.success('邀请链接已复制')}catch{ElMessage.warning('复制不可用，可使用房间码邀请')}}
const passwordVisible = ref(false)
const passwordInput = ref('')
const joining = ref(false)

function errorText(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallback
}

async function enterRoom(password?: string) {
  joining.value = true
  try {
    const joined = await room.joinRoom(code.value, password)
    passwordVisible.value = false
    passwordInput.value = ''
    ElMessage.success(`已加入房间 ${joined.roomCode}`)
    afterEnter()
  } catch (error) {
    const message = errorText(error, '加入房间失败')
    // 可能其实已经在该房间里（状态不同步），先尝试恢复再报错
    if (message.includes('已在')) {
      const restored = await room.restoreCurrent()
      if (restored?.roomCode === code.value) {
        afterEnter()
        return
      }
    }
    ElMessage.error(message)
    // 需要密码时给出输入框，而不是把用户挡在门外
    if (message.includes('密码')) {
      passwordVisible.value = true
    }
  } finally {
    joining.value = false
  }
}

function submitPassword() {
  if (!passwordInput.value) {
    ElMessage.warning('请输入房间密码')
    return
  }
  void enterRoom(passwordInput.value)
}

/** 订阅房间主题 + 接入聊天 / 对局状态。 */
function afterEnter() {
  const value = room.current
  if (!value) {
    return
  }
  room.subscribeRoom(value.roomCode)
  void chat.enterRoom(value)
  game.attach(value.roomCode)
  syncConfig()
  // 刷新页面时如果对局已经开始，直接送回对局页
  if (value.status === 'PLAYING') {
    void router.push({ name: 'game', params: { roomCode: value.roomCode } })
  }
}

function closePasswordDialog() {
  passwordVisible.value = false
  passwordInput.value = ''
}

// ----------------------------------------------------------- 房主配置 ----

const configForm = reactive({
  maxPlayers: 6,
  werewolfCount: 1,
  aiCount: 0,
  publicRoom: true,
  password: '',
  clearPassword: false,
  allowInvites: true,
  voiceEnabled: false,
  privateChatEnabled: true
})

const savingConfig = ref(false)
const roleCounts = ref<RoleCounts>({} as RoleCounts)
const displayedCounts = computed(() => isHost.value ? roleCounts.value : current.value?.roleCounts ?? {} as RoleCounts)
const roleError = computed(() => roleConfigurationError(displayedCounts.value, isHost.value ? configForm.maxPlayers : current.value?.maxPlayers ?? 0, game.meta?.roles ?? []))
const presetOptions = computed(() => Object.keys(game.meta?.roleDefaults?.[String(configForm.maxPlayers)] ?? {}))
const selectedPreset = computed(() => presetOptions.value.find(w => {
  const preset=game.meta?.roleDefaults?.[String(configForm.maxPlayers)]?.[w]
  return preset && sameRoles(roleCounts.value,preset)
}) ?? 'custom')
function applyPreset(wolves: string) {
  const counts = game.meta?.roleDefaults?.[String(configForm.maxPlayers)]?.[wolves]
  if (counts) roleCounts.value = {...counts}
}
function restoreDefaultRoles() {
  const desired=String(current.value?.werewolfCount ?? 1)
  applyPreset(presetOptions.value.includes(desired) ? desired : presetOptions.value[0] ?? '')
}


const aiMax = computed(() =>
  Math.max(0, configForm.maxPlayers - (current.value?.humanCount ?? 0))
)

function syncConfig() {
  const value = current.value
  if (!value) {
    return
  }
  roleCounts.value = {...value.roleCounts}
  configForm.maxPlayers = value.maxPlayers
  configForm.werewolfCount = value.werewolfCount
  configForm.aiCount = value.aiCount
  configForm.publicRoom = value.publicRoom
  configForm.allowInvites=value.allowInvites
  configForm.voiceEnabled=!!value.voiceEnabled
  configForm.privateChatEnabled=value.privateChatEnabled
}

watch(() => {
  const value=current.value
  return value ? [value.id,value.maxPlayers,value.werewolfCount,value.aiCount,value.publicRoom,value.allowInvites,value.voiceEnabled,value.privateChatEnabled,JSON.stringify(value.roleCounts)].join('|') : ''
},()=>syncConfig())

const configDirty=computed(()=>{
 const value=current.value
 return !!value&&(!sameRoles(roleCounts.value,value.roleCounts)||configForm.maxPlayers!==value.maxPlayers||configForm.werewolfCount!==value.werewolfCount||configForm.aiCount!==value.aiCount||configForm.publicRoom!==value.publicRoom||configForm.allowInvites!==value.allowInvites||configForm.voiceEnabled!==!!value.voiceEnabled||configForm.privateChatEnabled!==value.privateChatEnabled||!!configForm.password||configForm.clearPassword)
})

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function onMaxPlayersChange(value: number | undefined) {
  configForm.maxPlayers = clamp(Math.round(value ?? MIN_PLAYERS), MIN_PLAYERS, MAX_PLAYERS)
  configForm.aiCount = Math.min(configForm.aiCount, aiMax.value)
  // Keep the chosen roles; capacity changes adjust only ordinary villager seats.
  const total=Object.values(roleCounts.value).reduce((sum,n)=>sum+n,0)
  roleCounts.value={...roleCounts.value,VILLAGER:Math.max(0,(roleCounts.value.VILLAGER??0)+configForm.maxPlayers-total)}
}

function onAiCountChange(value: number | undefined) {
  configForm.aiCount = clamp(Math.round(value ?? 0), 0, aiMax.value)
}

function onPublicChange(value: string | number | boolean) {
  configForm.publicRoom = value === true
}

async function saveConfig() {
  const value = current.value
  if (!value) {
    return
  }
  if (roleError.value) { ElMessage.warning(roleError.value); return }
  savingConfig.value = true
  try {
    await room.updateRoom(value.roomCode, {
      maxPlayers: configForm.maxPlayers,
      roleCounts: {...roleCounts.value},
      aiCount: configForm.aiCount,
      allowAi: configForm.aiCount > 0,
      publicRoom: configForm.publicRoom,
      allowInvites: configForm.allowInvites,
      voiceEnabled: configForm.voiceEnabled,
      privateChatEnabled: configForm.privateChatEnabled,
      password: configForm.password ? configForm.password : undefined,
      clearPassword: configForm.clearPassword ? true : undefined
    })
    configForm.password = ''
    configForm.clearPassword = false
    ElMessage.success('房间配置已更新')
  } catch (error) {
    ElMessage.error(errorText(error, '保存配置失败'))
  } finally {
    savingConfig.value = false
  }
}

/** 快捷增删 AI：直接改 aiCount。 */
async function changeAiCount(delta: number) {
  const value = current.value
  if (!value) {
    return
  }
  const next = clamp((value.aiCount ?? 0) + delta, 0, Math.max(0, value.maxPlayers - value.humanCount))
  if (next === value.aiCount) {
    ElMessage.warning(delta > 0 ? '座位已满，无法再加入 AI' : '当前没有 AI 玩家')
    return
  }
  savingConfig.value = true
  try {
    await room.updateRoom(value.roomCode, { aiCount: next, allowAi: next > 0 })
    configForm.aiCount = next
  } catch (error) {
    ElMessage.error(errorText(error, '操作失败'))
  } finally {
    savingConfig.value = false
  }
}

// ----------------------------------------------------------- 操作按钮 ----

const readyLoading = ref(false)

async function toggleReady() {
  const value = current.value
  if (!value) {
    return
  }
  readyLoading.value = true
  try {
    await room.setReady(value.roomCode, !value.iAmReady)
  } catch (error) {
    ElMessage.error(errorText(error, '操作失败'))
  } finally {
    readyLoading.value = false
  }
}

async function startGame() {
  const value = current.value
  if (!value) {
    return
  }
  if (value.playerCount < MIN_PLAYERS) {
    ElMessage.warning(`至少需要 ${MIN_PLAYERS} 名玩家才能开始（当前 ${value.playerCount} 人）`)
    return
  }
  // 只检查"除房主外"的玩家：房主不需要准备（与后端同一口径）
  if (unreadyNames.value.length > 0) {
    ElMessage.warning(`还有玩家未准备：${unreadyNames.value.join('、')}`)
    return
  }
  try {
    if(roleError.value){ElMessage.warning(roleError.value);return}
    if(configDirty.value){ElMessage.warning('请先保存身份及房间配置');return}
    await game.startGame()
    await router.push({ name: 'game', params: { roomCode: value.roomCode } })
  } catch (error) {
    ElMessage.error(errorText(error, '开始游戏失败'))
  }
}

async function dissolveRoom() {
  const value = current.value
  if (!value) {
    return
  }
  try {
    await ElMessageBox.confirm('解散后所有玩家都会被移出房间，确定吗？', '解散房间', {
      confirmButtonText: '确定解散',
      cancelButtonText: '再想想',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await room.dissolve(value.roomCode)
    chat.leaveRoom()
    game.detach()
    ElMessage.success('房间已解散')
    await router.push({ name: 'lobby' })
  } catch (error) {
    ElMessage.error(errorText(error, '解散房间失败'))
  }
}

async function leaveRoom() {
  const value = current.value
  if (!value) {
    return
  }
  chat.leaveRoom()
  game.detach()
  await room.leaveRoom(value.roomCode)
  ElMessage.success('已退出房间')
  await router.push({ name: 'lobby' })
}

async function handleKick(seatId: number) {
  const value = current.value
  if (!value) {
    return
  }
  try {
    await room.kick(value.roomCode, seatId)
  } catch (error) {
    ElMessage.error(errorText(error, '移出玩家失败'))
  }
}

/** 复制房间码（非安全上下文下退化为 execCommand）。 */
async function copyCode() {
  const text = code.value
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      throw new Error('clipboard unavailable')
    }
    ElMessage.success(`房间码 ${text} 已复制`)
  } catch {
    try {
      const area = document.createElement('textarea')
      area.value = text
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      document.body.removeChild(area)
      ElMessage.success(`房间码 ${text} 已复制`)
    } catch {
      ElMessage.info(`房间码：${text}`)
    }
  }
}

// -------------------------------------------------------------- 生命周期 --

/** 房间状态变成 PLAYING 时自动进入对局页（房主点开始后其他玩家也能跟上）。 */
watch(
  () => current.value?.status,
  (status) => {
    if (status === 'PLAYING') {
      void router.push({ name: 'game', params: { roomCode: code.value } })
    }
  }
)

onMounted(async () => {
  // 1) 已经在目标房间里（例如从对局页返回）直接复用
  if (room.current?.roomCode === code.value) {
    afterEnter()
    return
  }

  // 2) 刷新后恢复“我在哪个房间”
  try {
    const restored = await room.restoreCurrent()
    if (restored?.roomCode === code.value) {
      afterEnter()
      return
    }
  } catch {
    /* 恢复失败则走 join */
  }

  // 3) 主动加入
  await enterRoom()
})

onUnmounted(() => {
  room.unsubscribeRoom()
  chat.leaveRoom()
  game.detach()
})
</script>

<template>
  <div class="room">
    <!-- 顶部信息条 -->
    <header class="room__header ww-panel ww-panel--pad">
      <div class="room__ident">
        <h2 class="room__name">{{ current?.roomName || '房间加载中…' }}</h2>
        <div class="room__code-row">
          <span class="room__code ww-mono">{{ code }}</span>
          <el-button size="small" text type="primary" @click="copyCode">复制房间码</el-button><el-button size="small" text :disabled="!current?.allowInvites" @click="copyInviteLink">复制邀请链接</el-button><el-button size="small" type="primary" :disabled="!current?.allowInvites" @click="inviteVisible=true">邀请开局</el-button>
        </div>
      </div>

      <div class="room__facts">
        <span class="room__fact">
          <strong>{{ current?.humanCount ?? 0 }}</strong> 真人 /
          <strong>{{ current?.playerCount ?? 0 }}</strong> 座位
        </span>
        <span class="room__fact">狼人 <strong>{{ current?.werewolfCount ?? 0 }}</strong></span>
        <span class="room__fact">AI <strong>{{ current?.aiCount ?? 0 }}</strong></span>
        <span class="room__fact">
          密码
          <strong>{{ current?.hasPassword ? '已设置' : '无' }}</strong>
        </span>
        <el-tag
          :type="canStart ? 'success' : 'warning'"
          size="small"
          effect="dark"
          round
        >
          {{ readyAmongPlayers }}/{{ needReadyCount }} 已准备
        </el-tag>
      </div>
    </header>

    <div class="room__grid">
      <!-- 左：座位 + 房主配置 -->
      <section class="ww-panel ww-panel--pad room__seats">
        <div class="ww-panel__title">
          座位
          <span class="ww-spacer" />
          <span class="ww-muted">房主：{{ current?.hostNickname || '—' }}</span>
        </div>
        <RoomSeatGrid v-if="current" :room="current" :is-host="isHost" @kick="handleKick" />
        <el-skeleton v-else :rows="3" animated />
      </section>

      <section v-if="isHost" class="ww-panel ww-panel--pad room__config">
        <div class="ww-panel__title">房主配置</div>
        <div class="room__config-grid">
          <div class="room__field">
            <span class="room__field-label">最大人数</span>
            <el-input-number
              :model-value="configForm.maxPlayers"
              :min="MIN_PLAYERS"
              :max="MAX_PLAYERS"
              @update:model-value="onMaxPlayersChange"
            />
          </div>
          <div class="room__field">
            <span class="room__field-label">AI 数量</span>
            <el-input-number
              :model-value="configForm.aiCount"
              :min="0"
              :max="aiMax"
              @update:model-value="onAiCountChange"
            />
          </div>
          <div class="room__field">
            <span class="room__field-label">公开房间</span>
            <el-switch
              :model-value="configForm.publicRoom"
              @update:model-value="onPublicChange"
            />
          </div>
        </div>

        <div class="room__config-password"><el-checkbox v-model="configForm.allowInvites">允许房间邀请</el-checkbox><el-checkbox v-model="configForm.privateChatEnabled">允许局内私聊</el-checkbox><el-checkbox v-model="configForm.voiceEnabled">开启房间语音</el-checkbox></div>
        <div class="room__config-password">
          <el-input
            v-model="configForm.password"
            type="password"
            placeholder="设置新密码（留空表示不改）"
            show-password
          />
          <el-button
            v-if="current?.hasPassword"
            size="small"
            text
            type="danger"
            @click="configForm.clearPassword = !configForm.clearPassword"
          >
            {{ configForm.clearPassword ? '取消清除密码' : '清除密码' }}
          </el-button>
        </div>

        <div class="room__config-actions">
          <el-button size="small" :disabled="savingConfig || configDirty || (current?.aiCount ?? 0) >= Math.max(0,(current?.maxPlayers ?? 0)-(current?.humanCount ?? 0))" @click="changeAiCount(1)">
            添加 AI
          </el-button>
          <el-button size="small" :disabled="savingConfig || configDirty || (current?.aiCount ?? 0) <= 0" @click="changeAiCount(-1)">
            移除 AI
          </el-button>
          <span class="ww-spacer" />
          <el-button size="small" type="primary" :loading="savingConfig" :disabled="!!roleError" @click="saveConfig">
            保存配置
          </el-button>
        </div>
        <p class="ww-muted room__config-tip"><span v-if="configDirty">配置尚未保存。请点击「保存配置」，AI 会自动入座并准备。</span><span v-else>AI 会自动准备；一人试玩请补齐本局人数减 1 个 AI。</span></p>
        <p class="ww-muted room__config-tip">身份总数必须与本局人数一致，座位需全部入座；对局开始后无法修改。</p>
      </section>
      <section class="ww-panel ww-panel--pad room__roles">
        <div class="ww-panel__title">身份配置 <span class="ww-spacer"/><span v-if="!isHost" class="ww-muted">由房主修改，保存后全房间同步</span></div>
        <div v-if="isHost" class="room__role-presets">
          <span class="ww-muted">预设方案</span>
          <el-select :model-value="selectedPreset" aria-label="身份预设方案" style="width:200px" @change="applyPreset">
            <el-option v-if="selectedPreset==='custom'" value="custom" label="自定义身份" disabled/>
            <el-option v-for="w in presetOptions" :key="w" :value="w" :label="configForm.maxPlayers===13?'13 人标准方案':`${configForm.maxPlayers} 人 · ${w} 狼默认方案`"/>
          </el-select>
          <el-button :disabled="savingConfig || !presetOptions.length" @click="restoreDefaultRoles">恢复默认配置</el-button>
        </div>
        <RoleCountsEditor v-model="roleCounts" v-if="isHost" :roles="game.meta?.roles??[]" :players="configForm.maxPlayers" :disabled="savingConfig"/>
        <RoleCountsEditor v-else :model-value="displayedCounts" :roles="game.meta?.roles??[]" :players="current?.maxPlayers??0" disabled/>
        <el-button v-if="!game.meta" @click="game.loadMeta">重新加载身份规则</el-button>
        <el-button v-if="isHost" class="room__role-save" type="primary" :loading="savingConfig" :disabled="!!roleError" @click="saveConfig">保存配置</el-button>
      </section>
    </div>

    <!-- 右：房间聊天 -->
    <div class="room__chat-wrap">
      <VoicePanel :room-code="code" :enabled="current?.voiceEnabled" />
      <ChatPanel class="room__chat ww-panel" :room-code="code" :players="players" :private-chat-enabled="current?.privateChatEnabled" />
    </div>

    <!-- 底部操作条 -->
    <footer class="room__footer ww-panel">
      <span class="ww-muted room__footer-tip">
        <template v-if="isHost">
          你是房主，<strong>不需要准备</strong>；
          <template v-if="unreadyNames.length">
            还等 {{ unreadyNames.join('、') }} 准备
          </template>
          <template v-else-if="roleError">{{roleError}}</template>
          <template v-else-if="configDirty">身份或房间配置尚未保存</template>
          <template v-else-if="current?.playerCount !== current?.maxPlayers">当前入座 {{current?.playerCount??0}} / {{current?.maxPlayers??0}} 人，请补齐座位</template>
          <template v-else>可以开始了</template>
        </template>
        <template v-else>
          {{ isReady ? '你已准备，等待房主开始' : '点击准备，等待房主开始游戏' }}
        </template>
      </span>
      <span class="ww-spacer" />
      <template v-if="isHost">
        <el-button
          type="primary"
          :loading="game.starting"
          :disabled="!canStart"
          @click="startGame"
        >
          开始游戏
        </el-button>
        <el-button type="danger" plain @click="dissolveRoom">解散房间</el-button>
      </template>
      <template v-else>
        <el-button
          :type="isReady ? 'warning' : 'success'"
          :loading="readyLoading"
          @click="toggleReady"
        >
          {{ isReady ? '取消准备' : '准备' }}
        </el-button>
        <el-button text @click="leaveRoom">离开房间</el-button>
      </template>
    </footer>

    <RoomInviteDialog v-model="inviteVisible" :room-code="code"/>
    <!-- 房间密码输入（内联弹窗） -->
    <el-dialog v-model="passwordVisible" title="该房间需要密码" width="360px">
      <div @keydown.enter.prevent="submitPassword">
        <p class="ww-muted room__dialog-tip">请输入房间「{{ current?.roomName || code }}」的密码</p>
        <el-input
          v-model="passwordInput"
          type="password"
          placeholder="房间密码"
          show-password
        />
      </div>
      <template #footer>
        <el-button @click="closePasswordDialog">取消</el-button>
        <el-button type="primary" :loading="joining" @click="submitPassword">加入房间</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.room__role-presets{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:16px}.room__role-save{margin-top:16px}

.room {
  position: relative;
  isolation: isolate;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 340px);
  grid-template-areas:
    'header header'
    'main chat'
    'footer footer';
  gap: 16px;
  padding: 18px clamp(14px, 3vw, 28px) 28px;
  max-width: 1500px;
  margin: 0 auto;
}

.room::before {
  content: '';
  position: fixed;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background: linear-gradient(rgba(5, 11, 29, .66), rgba(5, 11, 29, .78)), url('/assets/art/scenes/preparation.jpg') center / cover no-repeat;
}

.room__header {
  grid-area: header;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}

.room__name {
  margin: 0;
  font-size: 20px;
}

.room__code-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.room__code {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 8px;
  color: var(--ww-moon);
  text-shadow: 0 0 18px rgba(251, 191, 36, 0.4);
}

.room__facts {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.room__fact {
  font-size: 13px;
  color: var(--ww-text-dim);
}

.room__fact strong {
  color: var(--ww-text);
  font-family: var(--ww-font-mono);
}

.room__grid {
  grid-area: main;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.room__config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px 16px;
}

.room__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.room__field-label {
  font-size: 12px;
  color: var(--ww-text-dim);
}

.room__config-password {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.room__config-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.room__config-tip {
  margin: 10px 0 0;
  line-height: 1.6;
}

.room__chat-wrap {
  grid-area: chat;
  min-width: 0;
}

.room__chat {
  height: 100%;
  max-height: 72vh;
}

.room__footer {
  grid-area: footer;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 18px;
  flex-wrap: wrap;
}

.room__footer-tip {
  font-size: 13px;
}

.room__dialog-tip {
  margin: 0 0 10px;
}

@media (max-width: 600px) {
  .room__ident { min-width: 0; max-width: 100%; }
  .room__code-row { flex-wrap: wrap; }
  .room__config-password { flex-wrap: wrap; }
}

@media (max-width: 1080px) {
  .room {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      'header'
      'main'
      'chat'
      'footer';
  }

  .room__chat {
    max-height: 46vh;
  }
}
</style>
