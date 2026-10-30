<script setup lang="ts">
/**
 * 局内聊天面板：公共 / 私聊 / 狼人 三个频道。
 *
 * 设计取舍：
 * - 页签用自绘按钮 + el-badge（未读数）而不是 el-tabs：暗色主题下更好控色，
 *   同时能直接对禁用态做 tooltip 提示；
 * - 发送只调用 `chat.send()`，频道切换只调用 `chat.switchChannel()`，
 *   所有离线兜底、私聊对象校验都在 store 里完成，这里不重复实现。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useChatStore, type ChatChannel } from '@/stores/chat'
import type { ChatMessage, PlayerView } from '@/types'

const props = defineProps<{
  roomCode: string
  players: PlayerView[]
  privateChatEnabled?: boolean
}>()

const chat = useChatStore()
const auth = useAuthStore()

const draft = ref('')
const listRef = ref<HTMLDivElement | null>(null)

interface ChannelTab {
  key: ChatChannel
  label: string
  hint: string
}

const tabs: ChannelTab[] = [
  { key: 'PUBLIC', label: '公共', hint: '所有玩家可见' },
  { key: 'PRIVATE', label: '私聊', hint: '仅你与对方可见' },
  { key: 'WOLF', label: '狼人', hint: '仅狼人可用' }
]

const activeChannel = computed<ChatChannel>(() => chat.activeChannel)
const privateEnabled=computed(()=>props.privateChatEnabled!==false)
watch(privateEnabled,enabled=>{if(!enabled&&chat.activeChannel==='PRIVATE')chat.switchChannel('PUBLIC')},{immediate:true})
const wolfEnabled = computed(() => chat.wolfChatEnabled)

/** 可私聊对象：真人 + AI，排除自己。 */
const privateCandidates = computed(() =>
  props.players.filter((p) => !!p.userId && p.userId !== auth.user?.id)
)

const messages = computed<ChatMessage[]>(() => chat.visibleMessages())

const inputDisabled = computed(
  () => activeChannel.value === 'PRIVATE' && !chat.privateTarget
)

const placeholder = computed(() => {
  if (activeChannel.value === 'PRIVATE') {
    return chat.privateTarget
      ? `私聊 ${chat.privateTarget.nickname}…（Enter 发送，Shift+Enter 换行）`
      : '请先在上方选择私聊对象'
  }
  if (activeChannel.value === 'WOLF') {
    return wolfEnabled.value ? '狼人频道…（Enter 发送）' : '仅狼人可用'
  }
  return '说点什么…（Enter 发送，Shift+Enter 换行）'
})

const unreadOf = (channel: ChatChannel): number => chat.unread[channel] ?? 0

function switchTo(channel: ChatChannel) {
  if(channel==='PRIVATE'&&!privateEnabled.value){ElMessage.warning('房主已关闭局内私聊');return}
  if (channel === 'WOLF' && !wolfEnabled.value) {
    ElMessage.warning('狼人频道仅狼人可用')
    return
  }
  chat.switchChannel(channel)
}

function pickTarget(player: PlayerView) {
  if (!player.userId) {
    return
  }
  chat.switchChannel('PRIVATE', { userId: player.userId, nickname: player.nickname })
}

function isMine(message: ChatMessage): boolean {
  return !!auth.user?.id && message.senderId === auth.user.id
}

function isSystem(message: ChatMessage): boolean {
  return message.messageType === 'SYSTEM'
}

function formatTime(value?: string): string {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

async function handleSend() {
  const text = draft.value.trim()
  if (!text) {
    return
  }
  if (activeChannel.value === 'PRIVATE' && !chat.privateTarget) {
    ElMessage.warning('请先选择私聊对象')
    return
  }
  if (activeChannel.value === 'WOLF' && !wolfEnabled.value) {
    ElMessage.warning('狼人频道仅狼人可用')
    return
  }
  try {
    await chat.send(text)
    draft.value = ''
    await scrollToBottom()
  } catch {
    ElMessage.error('消息发送失败，请稍后重试')
  }
}

/** Enter 发送，Shift+Enter 换行；中文输入法组词时不拦截。 */
function handleKeydown(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null
  // 只在输入框里响应，避免焦点在按钮上时回车被重复处理
  if (!target || target.tagName !== 'TEXTAREA') {
    return
  }
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) {
    return
  }
  event.preventDefault()
  void handleSend()
}

watch(
  () => [messages.value.length, activeChannel.value, chat.privateTarget?.userId],
  () => {
    void scrollToBottom()
  },
  { immediate: true }
)
</script>

<template>
  <section class="chat-panel ww-panel">
    <!-- 频道页签 -->
    <div class="chat-panel__tabs" role="tablist">
      <el-tooltip
        v-for="tab in tabs"
        :key="tab.key"
        :content="tab.key==='PRIVATE'&&!privateEnabled?'房主已关闭局内私聊':tab.key==='WOLF'&&!wolfEnabled?'仅狼人可用':tab.hint"
        placement="top"
      >
        <button
          type="button"
          class="chat-panel__tab"
          :class="{
            'chat-panel__tab--active': activeChannel === tab.key,
            'chat-panel__tab--locked': (tab.key === 'WOLF' && !wolfEnabled)||(tab.key==='PRIVATE'&&!privateEnabled)
          }"
          role="tab"
          :aria-selected="activeChannel === tab.key"
          :disabled="(tab.key === 'WOLF' && !wolfEnabled)||(tab.key==='PRIVATE'&&!privateEnabled)"
          @click="switchTo(tab.key)"
        >
          <el-badge
            :value="unreadOf(tab.key)"
            :hidden="unreadOf(tab.key) <= 0"
            :max="99"
            class="chat-panel__badge"
          >
            {{ tab.label }}
          </el-badge>
        </button>
      </el-tooltip>
      <span class="ww-spacer" />
      <span class="chat-panel__room ww-mono">{{ roomCode }}</span>
    </div>

    <!-- 私聊对象选择 -->
    <div v-if="activeChannel === 'PRIVATE'" class="chat-panel__targets">
      <span class="ww-muted">私聊对象：</span>
      <div class="chat-panel__target-list ww-scroll">
        <button
          v-for="player in privateCandidates"
          :key="player.seatId"
          type="button"
          class="chat-panel__target"
          :class="{ 'chat-panel__target--active': chat.privateTarget?.userId === player.userId }"
          @click="pickTarget(player)"
        >
          <el-avatar :size="20" :src="player.avatar">
            {{ (player.nickname || '?').slice(0, 1) }}
          </el-avatar>
          <span class="chat-panel__target-name">{{ player.nickname }}</span>
        </button>
        <span v-if="!privateCandidates.length" class="ww-muted">暂无其他玩家</span>
      </div>
    </div>

    <!-- 消息列表 -->
    <div ref="listRef" class="chat-panel__list ww-scroll">
      <template v-if="messages.length">
        <div
          v-for="(message, index) in messages"
          :key="message.id ?? `${message.senderId}-${index}`"
          class="chat-panel__row"
          :class="{
            'chat-panel__row--mine': isMine(message),
            'chat-panel__row--system': isSystem(message)
          }"
        >
          <template v-if="isSystem(message)">
            <span class="chat-panel__system">{{ message.content }}</span>
          </template>
          <template v-else>
            <el-avatar v-if="!isMine(message)" :size="26" class="chat-panel__avatar">
              {{ (message.senderNickname || '?').slice(0, 1) }}
            </el-avatar>
            <div class="chat-panel__bubble">
              <div class="chat-panel__head">
                <span class="chat-panel__sender">{{ message.senderNickname || '玩家' }}</span>
                <span v-if="message.isAi" class="ww-tag ww-tag--ai">AI</span>
                <span v-if="message.messageType === 'WOLF'" class="ww-tag ww-tag--wolf">狼</span>
                <span v-if="message.messageType === 'PRIVATE'" class="ww-tag ww-tag--seer">私</span>
                <span class="chat-panel__time">{{ formatTime(message.createdAt) }}</span>
              </div>
              <p class="chat-panel__text">{{ message.content }}</p>
            </div>
          </template>
        </div>
      </template>
      <el-empty v-else :image-size="60" description="还没有消息，先打个招呼吧" />
    </div>

    <!-- 输入区 -->
    <div class="chat-panel__input" @keydown="handleKeydown">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="2"
        resize="none"
        maxlength="500"
        show-word-limit
        :placeholder="placeholder"
        :disabled="inputDisabled"
      />
      <el-button
        type="primary"
        class="chat-panel__send"
        :loading="chat.sending"
        :disabled="inputDisabled || !draft.trim()"
        @click="handleSend"
      >
        发送
      </el-button>
    </div>
  </section>
</template>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

/* ---------------------------------------------------------------- 页签 -- */
.chat-panel__tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--ww-border-soft);
  background: rgba(8, 12, 24, 0.35);
}

.chat-panel__tab {
  appearance: none;
  border: 1px solid transparent;
  background: transparent;
  color: var(--ww-text-dim);
  font-size: 13px;
  font-family: inherit;
  padding: 4px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.18s ease;
}

.chat-panel__tab:hover:not(:disabled) {
  color: var(--ww-text);
  background: var(--ww-primary-soft);
}

.chat-panel__tab--active {
  color: var(--ww-moon);
  background: var(--ww-moon-soft);
  border-color: rgba(251, 191, 36, 0.45);
  font-weight: 700;
}

.chat-panel__tab--locked {
  cursor: not-allowed;
  opacity: 0.45;
}

.chat-panel__badge {
  line-height: 1;
}

.chat-panel__room {
  font-size: 11px;
  color: var(--ww-text-mute);
  letter-spacing: 1px;
}

/* ------------------------------------------------------------ 私聊对象 -- */
.chat-panel__targets {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--ww-border-soft);
}

.chat-panel__target-list {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 2px;
  max-width: 100%;
}

.chat-panel__target {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px 2px 3px;
  border-radius: 999px;
  border: 1px solid var(--ww-border-soft);
  background: rgba(8, 12, 24, 0.6);
  color: var(--ww-text-dim);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.18s ease;
}

.chat-panel__target:hover {
  border-color: var(--ww-moon);
  color: var(--ww-text);
}

.chat-panel__target--active {
  border-color: var(--ww-moon);
  color: var(--ww-moon);
  background: var(--ww-moon-soft);
}

/* ------------------------------------------------------------ 消息列表 -- */
.chat-panel__list {
  flex: 1;
  min-height: 120px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.chat-panel__row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  max-width: 100%;
}

.chat-panel__row--mine {
  flex-direction: row-reverse;
}

.chat-panel__row--system {
  justify-content: center;
}

.chat-panel__system {
  font-size: 11px;
  color: var(--ww-text-mute);
  text-align: center;
  padding: 2px 10px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.1);
}

.chat-panel__avatar {
  flex: none;
  background: var(--ww-primary-soft);
  color: var(--ww-text);
  font-size: 12px;
}

.chat-panel__bubble {
  max-width: 82%;
  padding: 6px 10px;
  border-radius: var(--ww-radius-sm);
  background: var(--ww-panel-2);
  border: 1px solid var(--ww-border-soft);
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.chat-panel__row--mine .chat-panel__bubble {
  background: linear-gradient(135deg, rgba(124, 58, 237, 0.28), rgba(76, 29, 149, 0.24));
  border-color: var(--ww-border);
}

.chat-panel__head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.chat-panel__sender {
  font-size: 11px;
  font-weight: 700;
  color: var(--ww-text-dim);
}

.chat-panel__time {
  font-size: 10px;
  color: var(--ww-text-mute);
  font-family: var(--ww-font-mono);
}

.chat-panel__text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ww-text);
  word-break: break-word;
  white-space: pre-wrap;
}

/* -------------------------------------------------------------- 输入区 -- */
.chat-panel__input {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 8px 10px 10px;
  border-top: 1px solid var(--ww-border-soft);
  background: rgba(8, 12, 24, 0.35);
}

.chat-panel__input :deep(.el-textarea__inner) {
  font-family: inherit;
  font-size: 13px;
}

.chat-panel__send {
  flex: none;
}
</style>
