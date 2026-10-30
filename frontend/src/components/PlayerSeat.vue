<script setup lang="ts">
/**
 * 玩家座位卡片（大厅 / 房间 / 对局三处复用）。
 *
 * 设计取舍：
 * - 组件本身不读取任何 store，全部状态通过 props 传入，保证在房间页（SeatView 转换来的
 *   最小 PlayerView）和对局页（后端下发的 PlayerView）里行为一致；
 * - `selectable` / `votable` 只决定“能否点击”，真实合法性仍由后端返回的
 *   `selectableTargets` 决定（父组件负责把两者对齐）。
 */
import { computed } from 'vue'
import type { PlayerView } from '@/types'

const props = withDefaults(
  defineProps<{
    player: PlayerView
    seatId: number
    isMe?: boolean
    isHost?: boolean
    isSpeaking?: boolean
    isSelected?: boolean
    selectable?: boolean
    votable?: boolean
    showRole?: boolean
    voteCount?: number
    compact?: boolean
  }>(),
  {
    isMe: false,
    isHost: false,
    isSpeaking: false,
    isSelected: false,
    selectable: false,
    votable: false,
    showRole: false,
    voteCount: 0,
    compact: false
  }
)

const emit = defineEmits<{ (e: 'select', seatId: number): void }>()

const dead = computed(() => !props.player.alive)
const speaking = computed(() => props.isSpeaking || props.player.speaking)
const voted = computed(() => props.player.voted)
const clickable = computed(() => (props.selectable || props.votable) && !dead.value)
const avatarSize = computed(() => (props.compact ? 40 : 58))

/** 座位号始终以 seatId 为准（player.seatId 在最小构造对象里可能缺失）。 */
const seatNumber = computed(() => props.seatId || props.player.seatId)

const nickname = computed(() => props.player.nickname || `${seatNumber.value} 号`)
const initial = computed(() => nickname.value.slice(0, 1).toUpperCase())

const roleKey = computed(() =>
  props.player.role ? String(props.player.role).toLowerCase() : ''
)
const roleIcon = computed(() => (roleKey.value ? `/assets/roles/${roleKey.value}-icon.svg?art=night-v1` : ''))
const roleClass = computed(() => (roleKey.value ? `role-${roleKey.value}` : ''))

const roleLabel = computed(() => {
  switch (roleKey.value) {
    case 'white_wolf_king': return '白狼王'
    case 'hunter': return '猎人'
    case 'guard': return '守卫'
    case 'idiot': return '白痴'
    case 'cupid': return '爱神'
    case 'werewolf':
      return '狼人'
    case 'seer':
      return '预言家'
    case 'witch':
      return '女巫'
    case 'villager':
      return '村民'
    default:
      return ''
  }
})

function handleSelect() {
  if (!clickable.value) {
    return
  }
  emit('select', seatNumber.value)
}
</script>

<template>
  <div
    class="ww-seat"
    :class="[
      roleClass,
      {
        'ww-seat--me': isMe,
        'ww-seat--dead': dead,
        'ww-seat--speaking': speaking,
        'ww-seat--selected': isSelected,
        'ww-seat--armed': selectable && !dead,
        'ww-seat--votable': votable && !dead,
        'ww-seat--clickable': clickable,
        'ww-seat--compact': compact
      }
    ]"
    :role="clickable ? 'button' : undefined"
    :tabindex="clickable ? 0 : undefined"
    :aria-label="`${seatNumber} 号座位 ${nickname}`"
    @click="handleSelect"
    @keydown.enter.prevent="handleSelect"
    @keydown.space.prevent="handleSelect"
  >
    <!-- 票数徽标 -->
    <span v-if="voteCount > 0" class="ww-seat__votes ww-pulse">{{ voteCount }}</span>

    <!-- 头像 -->
    <div class="ww-seat__avatar">
      <el-avatar :size="avatarSize" :src="player.avatar" class="ww-seat__img">
        {{ initial }}
      </el-avatar>

      <!-- AI 机器人角标 -->
      <span v-if="player.ai" class="ww-seat__bot" title="AI 玩家">🤖</span>

      <!-- 出局覆盖 -->
      <span v-if="dead" class="ww-seat__skull" title="已出局">💀</span>

      <!-- 已投票小勾 -->
      <span v-if="voted && !dead" class="ww-seat__tick" title="已投票">✓</span>
    </div>

    <!-- 名字与标记 -->
    <div class="ww-seat__meta">
      <div class="ww-seat__name" :class="{ 'ww-seat__name--dead': dead }">
        <span class="ww-seat-badge">{{ seatNumber }}</span>
        <span class="ww-seat__nick" :title="nickname">{{ nickname }}</span>
      </div>

      <div class="ww-seat__tags">
        <span v-if="player.ai" class="ww-tag ww-tag--ai">AI</span>
        <span v-if="isHost" class="ww-tag ww-seat__host">房主</span>
        <span v-if="isMe" class="ww-tag ww-seat__me">我</span>
        <span v-if="dead" class="ww-tag ww-tag--dead">出局</span>
        <span v-else-if="!player.ready && !compact" class="ww-tag ww-seat__wait">未准备</span>
      </div>
    </div>

    <!-- 角色小图标（仅在 showRole 且后端已公开身份时） -->
    <img
      v-if="showRole && roleIcon"
      class="ww-seat__role"
      :src="roleIcon"
      :alt="roleLabel"
      :title="roleLabel"
    />

    <span v-if="speaking" class="ww-seat__speaking-flag ww-pulse">发言中</span>
  </div>
</template>

<style scoped>
.ww-seat {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 8px 8px;
  border-radius: var(--ww-radius);
  border: 1px solid var(--ww-border-soft);
  background: var(--ww-panel-2);
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
  user-select: none;
}

.ww-seat--compact {
  padding: 8px 6px 6px;
  gap: 4px;
}

.ww-seat--clickable {
  cursor: pointer;
}

.ww-seat--clickable:hover {
  transform: translateY(-2px);
  border-color: var(--ww-moon);
}

/* 可选目标：金色描边 */
.ww-seat--armed,
.ww-seat--votable {
  border-color: var(--ww-moon);
  box-shadow: 0 0 0 1px var(--ww-moon-soft), 0 0 18px rgba(251, 191, 36, 0.22);
}

.ww-seat--votable {
  border-color: var(--ww-danger);
  box-shadow: 0 0 0 1px var(--ww-wolf-soft), 0 0 18px rgba(239, 68, 68, 0.2);
}

/* 选中目标：更强高亮 + 缩放 */
.ww-seat--selected {
  border-color: var(--ww-moon);
  background: linear-gradient(180deg, var(--ww-moon-soft), var(--ww-panel-2));
  box-shadow: var(--ww-shadow-glow), 0 0 0 2px var(--ww-moon) inset;
  transform: scale(1.06);
}

/* 自己 */
.ww-seat--me {
  border-color: var(--ww-border);
}

.ww-seat--me::after {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: var(--ww-radius);
  border: 1px dashed rgba(124, 58, 237, 0.55);
  pointer-events: none;
}

/* 正在发言：金色呼吸光环 */
.ww-seat--speaking {
  border-color: var(--ww-moon);
  background: linear-gradient(180deg, var(--ww-moon-soft), var(--ww-panel-2));
  animation: ww-seat-speaking 1.8s ease-in-out infinite;
}

@keyframes ww-seat-speaking {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(251, 191, 36, 0.5);
  }
  50% {
    box-shadow: 0 0 0 10px rgba(251, 191, 36, 0);
  }
}

/* 出局：灰度 + 划线 */
.ww-seat--dead {
  filter: grayscale(0.85);
  opacity: 0.62;
}

.ww-seat--dead .ww-seat__name {
  text-decoration: line-through;
}

.ww-seat__avatar {
  position: relative;
  line-height: 0;
}

.ww-seat__img {
  border: 2px solid var(--ww-border);
  background: rgba(8, 12, 24, 0.8);
}

.ww-seat--speaking .ww-seat__img {
  border-color: var(--ww-moon);
}

.ww-seat--selected .ww-seat__img {
  border-color: var(--ww-moon);
}

/* AI 标记刻意保持安静：不做发光、不做动画 */
.ww-seat__bot {
  position: absolute;
  right: -3px;
  bottom: -3px;
  font-size: 12px;
  line-height: 1;
  opacity: 0.75;
  filter: grayscale(1);
}

.ww-seat__skull {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  background: rgba(7, 10, 20, 0.45);
  border-radius: 50%;
}

.ww-seat__tick {
  position: absolute;
  right: -5px;
  top: -5px;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 11px;
  font-weight: 700;
  color: var(--ww-bg);
  background: var(--ww-villager);
  box-shadow: 0 0 8px rgba(52, 211, 153, 0.7);
}

.ww-seat__votes {
  position: absolute;
  right: -6px;
  top: -8px;
  z-index: 2;
  min-width: 22px;
  height: 22px;
  padding: 0 5px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  font-family: var(--ww-font-mono);
  color: var(--ww-text);
  background: var(--ww-wolf);
  box-shadow: 0 0 10px rgba(239, 68, 68, 0.6);
}

.ww-seat__meta {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  max-width: 100%;
}

.ww-seat__name {
  display: flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
}

.ww-seat__nick {
  font-size: 12px;
  font-weight: 600;
  color: var(--ww-text);
  max-width: 78px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ww-seat__tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 4px;
}

.ww-seat__host {
  color: var(--ww-moon);
  background: var(--ww-moon-soft);
  border-color: rgba(251, 191, 36, 0.4);
}

.ww-seat__me {
  color: var(--ww-seer);
  background: var(--ww-seer-soft);
  border-color: rgba(96, 165, 250, 0.4);
}

.ww-seat__wait {
  color: var(--ww-text-mute);
  background: rgba(148, 163, 184, 0.12);
  border-color: rgba(148, 163, 184, 0.24);
}

.ww-seat__role {
  position: absolute;
  left: -6px;
  top: -6px;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: rgba(8, 12, 24, 0.9);
  border: 1px solid var(--role-color, var(--ww-border));
  box-shadow: 0 0 10px var(--role-soft, rgba(124, 58, 237, 0.3));
}

.ww-seat__speaking-flag {
  position: absolute;
  bottom: -9px;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 700;
  color: var(--ww-bg);
  background: var(--ww-moon);
  box-shadow: 0 0 12px rgba(251, 191, 36, 0.6);
  white-space: nowrap;
}
</style>
