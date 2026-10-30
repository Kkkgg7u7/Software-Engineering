<script setup lang="ts">
/**
 * 游戏大厅：公开房间列表 + 创建房间 + 房间码加入。
 *
 * 设计取舍：
 * - 房间列表以 REST 拉取为准，WebSocket 只用来触发「节流刷新」（后端 ROOM_UPDATE 带
 *   refresh 标记），避免消息风暴把列表刷爆；
 * - 创建表单主体只保留房间名 / 密码 / 人数 / 狼人数 / 是否公开，AI 数量放在默认收起的
 *   「高级选项」里，且默认 0；
 * - 人数与狼人数做联动收敛（狼人上限 = floor((人数-1)/2)），避免提交必然失败的配置。
 */
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'
import ws, { WsTopic } from '@/websocket/stomp'
import type { CreateRoomPayload, RoomView } from '@/types'

const auth = useAuthStore()
const room = useRoomStore()
const router = useRouter()

const MIN_PLAYERS = 4
const MAX_PLAYERS = 13

// ------------------------------------------------------------- 欢迎条 ----

const totalGames = computed(() => auth.user?.totalGames ?? 0)
const winGames = computed(() => auth.user?.winGames ?? 0)
const winRate = computed(() => {
  if (totalGames.value <= 0) {
    return 0
  }
  return Math.round((winGames.value / totalGames.value) * 100)
})

// --------------------------------------------------------- 房间列表 -----

const refreshing = ref(false)
/** 断线/刷新后仍在房间里的提示。 */
const pendingRoom = ref<RoomView | null>(null)
const checkingRoom = ref(true)

// ----------------------------------------------------------- 快速匹配 ----

const matchPlayers = ref(4)
const matchPrivateChat = ref(true)
const matchWolves = computed(() => ({4:1,6:1,8:2,10:3,12:3,13:4} as Record<number,number>)[matchPlayers.value]||1)
const matching = ref(false)
const matchCancelRequested = ref(false)
const lobbyBusy = computed(() => matching.value || room.loading)

/** 取消发生在服务端分配房间前时，等待分配结果后立即退出，避免留下占用的座位。 */
function cancelMatch() {
  if (matching.value) {
    matchCancelRequested.value = true
  }
}

async function startMatch() {
  if (matching.value || room.loading || checkingRoom.value) {
    return
  }
  if (pendingRoom.value) {
    ElMessage.warning('你已在房间中，请先回到该房间或退出后再匹配')
    return
  }

  matching.value = true
  matchCancelRequested.value = false
  try {
    let matched: RoomView | null = null
    let matchError: unknown = null
    try {
      matched = await room.matchRoom({gameType:'WEREWOLF',maxPlayers:matchPlayers.value,werewolfCount:matchWolves.value,privateChatEnabled:matchPrivateChat.value})
    } catch (error) {
      matchError = error
      // 网络超时不代表服务器没有分配房间；先查询当前房间再决定下一步。
      matched = await room.restoreCurrent()
    }

    if (!matched) {
      if (matchCancelRequested.value) {
        ElMessage.warning('网络异常，无法确认是否已取消匹配；连接恢复后请检查当前房间')
      } else {
        ElMessage.error(errorText(matchError, '匹配失败，请稍后重试'))
      }
      return
    }

    if (matchCancelRequested.value) {
      try {
        await room.cancelMatchedRoom(matched.roomCode)
        pendingRoom.value = null
        ElMessage.info('已取消匹配')
        void refreshRooms()
      } catch (error) {
        pendingRoom.value = matched
        ElMessage.error(`${errorText(error, '退出匹配房间失败')}，请从上方提示返回房间后手动离开`)
      }
      return
    }

    pendingRoom.value = null
    ElMessage.success(matched.iAmHost ? '已创建匹配房间，等待其他玩家加入' : '已匹配到房间')
    await router.push({ name: 'room', params: { roomCode: matched.roomCode } })
  } finally {
    matching.value = false
    matchCancelRequested.value = false
  }
}

async function refreshRooms() {
  refreshing.value = true
  try {
    await room.loadPublicRooms()
  } catch (error) {
    ElMessage.error(errorText(error, '房间列表加载失败'))
  } finally {
    refreshing.value = false
  }
}

function statusLabel(status: RoomView['status']): string {
  switch (status) {
    case 'WAITING':
      return '等待中'
    case 'PLAYING':
      return '游戏中'
    case 'ENDED':
      return '已结束'
    default:
      return '未知'
  }
}

function statusType(status: RoomView['status']): 'success' | 'warning' | 'info' {
  if (status === 'WAITING') {
    return 'success'
  }
  return status === 'PLAYING' ? 'warning' : 'info'
}

function isJoinable(item: RoomView): boolean {
  return item.status === 'WAITING' && item.playerCount < item.maxPlayers
}

// ----------------------------------------------------------- 加入房间 ----

async function joinRoom(item: RoomView) {
  let password: string | undefined
  if (item.hasPassword) {
    try {
      const result = await ElMessageBox.prompt(
        `房间「${item.roomName}」需要密码，请输入后加入`,
        '输入房间密码',
        {
          confirmButtonText: '加入',
          cancelButtonText: '取消',
          inputType: 'password',
          inputPlaceholder: '房间密码',
          inputValidator: (value: string) => (value ? true : '密码不能为空')
        }
      )
      password = result.value
    } catch {
      return
    }
  }
  await doJoin(item.roomCode, password)
}

async function doJoin(roomCode: string, password?: string) {
  try {
    const joined = await room.joinRoom(roomCode, password)
    ElMessage.success(`已加入房间 ${joined.roomCode}`)
    pendingRoom.value = null
    await router.push({ name: 'room', params: { roomCode: joined.roomCode } })
  } catch (error) {
    const message = errorText(error, '加入房间失败')
    // 密码房间：弹一次输入框后重试（只重试一次，密码错误时直接提示）
    if (!password && message.includes('密码')) {
      try {
        const result = await ElMessageBox.prompt('该房间需要密码，请输入后加入', '输入房间密码', {
          confirmButtonText: '加入',
          cancelButtonText: '取消',
          inputType: 'password',
          inputPlaceholder: '房间密码',
          inputValidator: (value: string) => (value ? true : '密码不能为空')
        })
        await doJoin(roomCode, result.value)
      } catch {
        /* 用户取消 */
      }
      return
    }
    ElMessage.error(message)
  }
}

// ----------------------------------------------------- 按房间码加入 -----

const joinCode = ref('')

watch(joinCode, (value) => {
  const normalized = value
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
    .slice(0, 6)
  if (normalized !== value) {
    joinCode.value = normalized
  }
})

async function joinByCode() {
  const code = joinCode.value.trim().toUpperCase()
  if (code.length !== 6) {
    ElMessage.warning('请输入 6 位房间码')
    return
  }
  await doJoin(code)
}

// ----------------------------------------------------------- 创建房间 ----

const createFormRef = ref<FormInstance>()
const advancedOpen = ref(false)

const createForm = reactive({
  roomName: '',
  password: '',
  maxPlayers: 6,
  werewolfCount: 1,
  aiCount: 0,
  allowAi: false,
  voiceEnabled: false,
  publicRoom: true
})

const createRules: FormRules = {
  roomName: [
    { required: true, message: '请输入房间名', trigger: 'blur' },
    { min: 1, max: 20, message: '房间名长度为 1-20 位', trigger: 'blur' }
  ],
  password: [{ max: 20, message: '密码最长 20 位', trigger: 'blur' }]
}

const werewolfMax = computed(() => createForm.maxPlayers===13?4:Math.max(1, Math.floor((createForm.maxPlayers - 1) / 2)))
const aiMax = computed(() => Math.max(0, createForm.maxPlayers - 1))

/** 人数变化时收敛狼人数。 */
watch(
  () => createForm.maxPlayers,
  (value) => {
    if(value===13)createForm.werewolfCount=4;
    if (createForm.werewolfCount > werewolfMax.value) {
      createForm.werewolfCount = werewolfMax.value
      ElMessage.warning(`狼人数量不能超过 ${werewolfMax.value} 个，已自动调整为 ${werewolfMax.value}`)
    }
    if (createForm.aiCount > aiMax.value) {
      createForm.aiCount = aiMax.value
    }
    if (value < MIN_PLAYERS || value > MAX_PLAYERS) {
      createForm.maxPlayers = Math.min(MAX_PLAYERS, Math.max(MIN_PLAYERS, value))
    }
  }
)

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function onMaxPlayersChange(value: number | undefined) {
  createForm.maxPlayers = clamp(Math.round(value ?? MIN_PLAYERS), MIN_PLAYERS, MAX_PLAYERS)
}

function onWerewolfChange(value: number | undefined) {
  const next = clamp(Math.round(value ?? 1), 1, werewolfMax.value)
  if ((value ?? 0) > werewolfMax.value) {
    ElMessage.warning(`狼人数量不能超过 ${werewolfMax.value} 个，已自动调整`)
  }
  createForm.werewolfCount = createForm.maxPlayers===13?4:next
}

function onAiCountChange(value: number | undefined) {
  createForm.aiCount = clamp(Math.round(value ?? 0), 0, aiMax.value)
  createForm.allowAi = createForm.aiCount > 0
}

function onPublicRoomChange(value: string | number | boolean) {
  createForm.publicRoom = value === true
}

function onAllowAiChange(value: string | number | boolean) {
  createForm.allowAi = value === true
  if (!createForm.allowAi) createForm.aiCount = 0
}

function toggleAdvanced() {
  advancedOpen.value = !advancedOpen.value
}

async function createRoom() {
  const form = createFormRef.value
  if (form) {
    const valid = await form.validate().catch(() => false)
    if (!valid) {
      return
    }
  }
  if (createForm.werewolfCount > werewolfMax.value) {
    ElMessage.warning(`狼人数量不能超过 ${werewolfMax.value} 个`)
    return
  }
  const payload: CreateRoomPayload = {
    roomName: createForm.roomName.trim(),
    password: createForm.password ? createForm.password : undefined,
    maxPlayers: createForm.maxPlayers,
    werewolfCount: createForm.werewolfCount,
    aiCount: createForm.aiCount,
    allowAi: createForm.allowAi,
    voiceEnabled: createForm.voiceEnabled,
    publicRoom: createForm.publicRoom
  }
  try {
    const created = await room.createRoom(payload)
    ElMessage.success(`房间 ${created.roomCode} 创建成功`)
    pendingRoom.value = null
    await router.push({ name: 'room', params: { roomCode: created.roomCode } })
  } catch (error) {
    ElMessage.error(errorText(error, '创建房间失败'))
  }
}

// --------------------------------------------------------------- 其它 ----

function errorText(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallback
}

async function backToRoom() {
  const code = pendingRoom.value?.roomCode
  if (!code) {
    return
  }
  await router.push({ name: 'room', params: { roomCode: code } })
}

let offRooms: (() => void) | null = null
let refreshTimer: number | null = null

/** WebSocket 触发的列表刷新：500ms 节流。 */
function scheduleRefresh() {
  if (refreshTimer !== null) {
    return
  }
  refreshTimer = window.setTimeout(() => {
    refreshTimer = null
    void refreshRooms()
  }, 500)
}

onMounted(async () => {
  offRooms = ws.subscribeWith(WsTopic.rooms, (envelope) => {
    if (envelope.type !== 'ROOM_UPDATE') {
      return
    }
    const data = envelope.data as { refresh?: boolean } | null | undefined
    if (data?.refresh) {
      scheduleRefresh()
    }
  })

  try {
    await auth.refreshProfile()
  } catch {
    /* 战绩刷新失败不影响大厅使用 */
  }

  try {
    const current = await room.restoreCurrent()
    if (current?.roomCode) {
      pendingRoom.value = current
    }
  } catch {
    pendingRoom.value = null
  } finally {
    checkingRoom.value = false
  }

  await refreshRooms()
})

onUnmounted(() => {
  offRooms?.()
  offRooms = null
  if (refreshTimer !== null) {
    window.clearTimeout(refreshTimer)
    refreshTimer = null
  }
})
</script>

<template>
  <div class="lobby">
    <!-- 欢迎条 -->
    <header class="lobby__hero ww-panel ww-panel--pad">
      <div class="lobby__hello">
        <el-avatar :size="46" :src="auth.user?.avatar">
          {{ auth.displayName.slice(0, 1) }}
        </el-avatar>
        <div>
          <div class="lobby__nick">欢迎回来，{{ auth.displayName }}</div>
          <div class="ww-muted">一键匹配进入房间，也可以自己创建一局</div>
        </div>
      </div>
      <div class="lobby__stats">
        <div class="lobby__stat">
          <strong>{{ totalGames }}</strong>
          <span>总场次</span>
        </div>
        <div class="lobby__stat">
          <strong>{{ winGames }}</strong>
          <span>胜场</span>
        </div>
        <div class="lobby__stat">
          <strong>{{ winRate }}%</strong>
          <span>胜率</span>
        </div>
        <el-button text type="primary" :disabled="matching" @click="router.push({ name: 'records' })">
          查看战绩
        </el-button>
      </div>
    </header>

    <!-- 仍在房间中的提示 -->
    <el-alert
      v-if="pendingRoom"
      class="lobby__alert"
      type="warning"
      :closable="false"
      show-icon
      :title="`你还在房间 ${pendingRoom?.roomCode ?? ''}（${pendingRoom?.roomName ?? ''}）中`"
    >
      <template #default>
        <div class="lobby__alert-body">
          <span>可以直接回到该房间继续游戏。</span>
          <el-button size="small" type="warning" @click="backToRoom">回到房间</el-button>
        </div>
      </template>
    </el-alert>

    <section class="lobby__match ww-panel ww-panel--pad">
      <div class="lobby__match-copy">
        <div class="ww-panel__title">快速匹配</div>
        <p class="ww-muted">
          按所选人数、狼人数和私聊设置加入公开等待房；暂无合适房间时创建新房。
        </p>
        <span v-if="matching" class="lobby__match-status">
          {{ matchCancelRequested ? '正在取消并退出已分配的房间…' : '正在寻找房间…' }}
        </span>
        <span v-else-if="pendingRoom" class="lobby__match-status">
          你已在房间中，退出后可以重新匹配。
        </span>
      </div>
      <div class="lobby__match-actions"><el-select v-model="matchPlayers" aria-label="匹配人数" style="width:125px" :disabled="lobbyBusy"><el-option v-for="n in [4,6,8,10,12,13]" :key="n" :label="`${n}人 · ${({4:1,6:1,8:2,10:3,12:3,13:4} as Record<number,number>)[n]}狼`" :value="n"/></el-select><el-checkbox v-model="matchPrivateChat" :disabled="lobbyBusy">局内私聊</el-checkbox>
        <template v-if="matching">
          <el-button size="large" type="primary" loading disabled>正在匹配</el-button>
          <el-button size="large" :disabled="matchCancelRequested" @click="cancelMatch">
            取消匹配
          </el-button>
        </template>
        <el-button
          v-else
          size="large"
          type="primary"
          :disabled="checkingRoom || !!pendingRoom || room.loading"
          @click="startMatch"
        >
          开始匹配
        </el-button>
      </div>
    </section>

    <div class="lobby__grid">
      <!-- 左：公开房间列表 -->
      <section class="ww-panel ww-panel--pad lobby__rooms">
        <div class="ww-panel__title">
          公开房间
          <span class="ww-spacer" />
          <el-button size="small" text type="primary" :loading="refreshing" @click="refreshRooms">
            刷新
          </el-button>
        </div>

        <div class="lobby__room-list ww-scroll">
          <template v-if="room.publicRooms.length">
            <article v-for="item in room.publicRooms" :key="item.id || item.roomCode" class="room-row">
              <div class="room-row__main">
                <div class="room-row__title">
                  <strong>{{ item.roomName }}</strong>
                  <span class="room-row__code ww-mono">{{ item.roomCode }}</span>
                  <span v-if="item.hasPassword" class="ww-tag ww-tag--dead">🔒 有密码</span>
                </div>
                <div class="room-row__meta">
                  <span>{{ item.humanCount }}/{{ item.maxPlayers }} 人</span>
                  <span>狼人 {{ item.werewolfCount }}</span>
                  <span v-if="item.aiCount > 0">AI {{ item.aiCount }}</span>
                  <span>已准备 {{ item.readyCount }}</span>
                </div>
              </div>
              <div class="room-row__side">
                <el-tag :type="statusType(item.status)" size="small" effect="dark" round>
                  {{ statusLabel(item.status) }}
                </el-tag>
                <el-button
                  size="small"
                  type="primary"
                  :disabled="!isJoinable(item) || lobbyBusy"
                  @click="joinRoom(item)"
                >
                  {{ isJoinable(item) ? '加入' : '不可加入' }}
                </el-button>
              </div>
            </article>
          </template>
          <el-empty v-else :image-size="80" description="暂无公开房间，右侧开一局吧" />
        </div>
      </section>

      <!-- 右：创建 / 加入 -->
      <aside class="lobby__side">
        <section class="ww-panel ww-panel--pad">
          <div class="ww-panel__title">创建房间</div>
          <el-form
            ref="createFormRef"
            :model="createForm"
            :rules="createRules"
            label-position="top"
          >
            <el-form-item label="房间名" prop="roomName">
              <el-input v-model="createForm.roomName" placeholder="例如：新手教学局" clearable />
            </el-form-item>
            <el-form-item label="房间密码（可空）" prop="password">
              <el-input
                v-model="createForm.password"
                type="password"
                placeholder="留空表示无密码"
                show-password
              />
            </el-form-item>
            <div class="lobby__form-row">
              <el-form-item label="最大人数">
                <el-input-number
                  :model-value="createForm.maxPlayers"
                  :min="MIN_PLAYERS"
                  :max="MAX_PLAYERS"
                  @update:model-value="onMaxPlayersChange"
                />
              </el-form-item>
              <el-form-item label="狼人数量">
                <el-input-number
                  :model-value="createForm.werewolfCount"
                  :min="1"
                  :max="werewolfMax"
                  @update:model-value="onWerewolfChange"
                />
              </el-form-item>
            </div>
            <el-form-item label="房间可见性">
              <div class="lobby__switch-row">
                <el-switch
                  :model-value="createForm.publicRoom"
                  @update:model-value="onPublicRoomChange"
                />
                <span class="ww-muted">
                  {{ createForm.publicRoom ? '公开：出现在大厅列表' : '私有：只能凭房间码加入' }}
                </span>
              </div>
            </el-form-item>

            <!-- 高级选项：默认收起 -->
            <button type="button" class="lobby__advanced" @click="toggleAdvanced">
              <span>{{ advancedOpen ? '▾' : '▸' }} 高级选项</span>
              <span class="ww-muted">AI 玩家（默认 0）</span>
            </button>

            <div v-show="advancedOpen" class="lobby__advanced-body">
              <div class="lobby__form-row">
                <el-form-item label="AI 数量">
                  <el-input-number
                    :model-value="createForm.aiCount"
                    :min="0"
                    :max="aiMax"
                    @update:model-value="onAiCountChange"
                  />
                </el-form-item>
                <el-form-item label="房间语音"><el-switch v-model="createForm.voiceEnabled" aria-label="开启房间语音" /></el-form-item>
                <el-form-item label="允许 AI 加入">
                  <el-switch
                    :model-value="createForm.allowAi"
                    @update:model-value="onAllowAiChange"
                  />
                </el-form-item>
              </div>
              <p class="ww-muted lobby__advanced-tip">
                留空或设为 0 即为纯真人局。AI 数量 + 真人数量不能超过最大人数。
              </p>
            </div>

            <el-button
              type="primary"
              class="lobby__create"
              :loading="room.loading && !matching"
              :disabled="matching"
              @click="createRoom"
            >
              创建并进入房间
            </el-button>
          </el-form>
        </section>

        <section class="ww-panel ww-panel--pad">
          <div class="ww-panel__title">输入房间码加入</div>
          <div class="lobby__join" @keydown.enter.prevent="joinByCode">
            <el-input
              v-model="joinCode"
              class="lobby__join-input ww-mono"
              maxlength="6"
              placeholder="6 位房间码"
              :disabled="lobbyBusy"
            />
            <el-button type="primary" :disabled="lobbyBusy" @click="joinByCode">加入</el-button>
          </div>
          <p class="ww-muted lobby__join-tip">有密码的房间会在加入时提示输入密码。</p>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.lobby {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px clamp(14px, 4vw, 32px) 32px;
  max-width: 1500px;
  margin: 0 auto;
}

/* -------------------------------------------------------------- 欢迎条 -- */
.lobby__hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}

.lobby__hello {
  display: flex;
  align-items: center;
  gap: 12px;
}

.lobby__nick {
  font-size: 17px;
  font-weight: 700;
  color: var(--ww-text);
}

.lobby__stats {
  display: flex;
  align-items: center;
  gap: 22px;
}

.lobby__stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  line-height: 1.25;
}

.lobby__stat strong {
  font-family: var(--ww-font-mono);
  font-size: 20px;
  color: var(--ww-moon);
}

.lobby__stat span {
  font-size: 11px;
  color: var(--ww-text-mute);
}

.lobby__alert-body {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

/* ------------------------------------------------------------ 快速匹配 -- */
.lobby__match {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-color: rgba(251, 191, 36, 0.3);
  background: linear-gradient(110deg, rgba(251, 191, 36, 0.12), rgba(18, 25, 44, 0.86));
}

.lobby__match-copy p {
  margin: 8px 0 0;
  line-height: 1.6;
}

.lobby__match-status {
  display: inline-block;
  margin-top: 10px;
  color: var(--ww-moon);
  font-size: 12px;
}

.lobby__match-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

/* ---------------------------------------------------------------- 布局 -- */
.lobby__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 400px);
  gap: 16px;
  align-items: start;
}

.lobby__rooms {
  display: flex;
  flex-direction: column;
  min-height: 320px;
}

.lobby__room-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 62vh;
  padding-right: 4px;
}

.room-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-radius: var(--ww-radius);
  border: 1px solid var(--ww-border-soft);
  background: var(--ww-panel-2);
  transition: border-color 0.18s ease, transform 0.18s ease;
}

.room-row:hover {
  border-color: var(--ww-border);
  transform: translateY(-1px);
}

.room-row__main {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.room-row__title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.room-row__title strong {
  font-size: 15px;
  color: var(--ww-text);
}

.room-row__code {
  font-size: 12px;
  letter-spacing: 2px;
  padding: 1px 8px;
  border-radius: 6px;
  color: var(--ww-moon);
  background: var(--ww-moon-soft);
  border: 1px solid rgba(251, 191, 36, 0.3);
}

.room-row__meta {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--ww-text-dim);
}

.room-row__side {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

/* ---------------------------------------------------------------- 侧栏 -- */
.lobby__side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lobby__form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.lobby__switch-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.lobby__advanced {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 10px;
  border-radius: var(--ww-radius-sm);
  border: 1px dashed var(--ww-border-soft);
  background: rgba(8, 12, 24, 0.35);
  color: var(--ww-text-dim);
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.18s ease, color 0.18s ease;
}

.lobby__advanced:hover {
  border-color: var(--ww-border);
  color: var(--ww-text);
}

.lobby__advanced-body {
  padding: 12px 12px 4px;
  border-radius: var(--ww-radius-sm);
  border: 1px solid var(--ww-border-soft);
  background: rgba(8, 12, 24, 0.28);
  margin-bottom: 10px;
}

.lobby__advanced-tip {
  margin: 0 0 8px;
  line-height: 1.6;
}

.lobby__create {
  width: 100%;
}

.lobby__join {
  display: flex;
  gap: 10px;
}

.lobby__join-input :deep(.el-input__inner) {
  letter-spacing: 6px;
  text-align: center;
  font-size: 16px;
}

.lobby__join-tip {
  margin: 10px 0 0;
}

@media (max-width: 980px) {
  .lobby__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .lobby__room-list {
    max-height: none;
  }
}

@media (max-width: 680px) {
  .lobby__match {
    align-items: stretch;
    flex-direction: column;
  }

  .lobby__match-actions {
    flex-wrap: wrap;
  }
}
</style>
