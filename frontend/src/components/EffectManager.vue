<script setup lang="ts">
/**
 * 特效调度器（成员 E + F 负责模块，方案第九节）。
 *
 * 职责：
 * 1. 从 game store 的 pendingEffects 队列里顺序取出事件；
 * 2. 用「预生成 AI 素材（SVG）+ CSS 关键帧」播放，运行时不调用任何 AI 接口；
 * 3. 播放期间锁定交互，播完自动出队，保证多个事件不互相覆盖。
 *
 * 事件 -> 素材映射：
 *   NIGHT_START  -> 无素材，使用 CSS 夜色渐变 + 文字
 *   WOLF_KILL    -> /assets/roles/werewolf-icon.svg?art=night-v1
 *   SEER_CHECK   -> /assets/roles/seer-icon.svg?art=night-v1
 *   WITCH_HEAL   -> /assets/roles/witch-icon.svg?art=night-v1
 *   WITCH_POISON -> /assets/roles/witch-icon.svg?art=night-v1
 *   DAWN         -> /assets/art/scenes/day.jpg
 *   VOTE_EXILE   -> /assets/roles/hunter-icon.svg?art=night-v1
 *   PLAYER_DIED  -> 无素材，使用骷髅字符 + CSS
 *   GAME_START   -> /assets/roles/hidden-back.svg?art=night-v1（发牌）
 *   WIN_GOOD     -> /assets/roles/villager-card.svg?art=night-v1
 *   WIN_WEREWOLF -> /assets/roles/werewolf-card.svg?art=night-v1
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useGameStore, type EffectRequest } from '@/stores/game'

const game = useGameStore()

const current = ref<EffectRequest | null>(null)
const playing = ref(false)
let timer: number | null = null

/** 每类特效的时长（毫秒），与 effects.css 中的关键帧时长对应。 */
const DURATION: Record<string, number> = {
  NIGHT_START: 2000,
  DAWN: 2400,
  GAME_START: 2400,
  WOLF_KILL: 1500,
  SEER_CHECK: 2100,
  WITCH_HEAL: 2000,
  WITCH_POISON: 2000,
  VOTE_START: 1200,
  VOTE_EXILE: 1800,
  PLAYER_DIED: 1500,
  WIN_GOOD: 3200,
  WIN_WEREWOLF: 3200
}

const META: Record<
  string,
  { title: string; subtitle?: string; art?: string; cls: string; icon?: string }
> = {
  NIGHT_START: { title: '天黑了', subtitle: '请所有玩家闭眼', cls: 'ww-fx--night' },
  WOLF_KILL: {
    title: '狼人猎杀',
    art: '/assets/roles/werewolf-icon.svg?art=night-v1',
    cls: 'ww-fx--kill'
  },
  SEER_CHECK: {
    title: '预言家查验',
    art: '/assets/roles/seer-icon.svg?art=night-v1',
    cls: 'ww-fx--check'
  },
  WITCH_HEAL: {
    title: '女巫使用解药',
    art: '/assets/roles/witch-icon.svg?art=night-v1',
    cls: 'ww-fx--heal'
  },
  WITCH_POISON: {
    title: '女巫使用毒药',
    art: '/assets/roles/witch-icon.svg?art=night-v1',
    cls: 'ww-fx--poison'
  },
  DAWN: { title: '天亮了', art: '/assets/art/scenes/day.jpg', cls: 'ww-fx--dawn' },
  VOTE_START: { title: '开始投票', art: '/assets/roles/hunter-icon.svg?art=night-v1', cls: 'ww-fx--vote' },
  VOTE_EXILE: { title: '投票放逐', art: '/assets/roles/hunter-icon.svg?art=night-v1', cls: 'ww-fx--vote' },
  PLAYER_DIED: { title: '玩家出局', cls: 'ww-fx--death', icon: '💀' },
  GAME_START: {
    title: '正在发牌',
    subtitle: '你将获得一个隐藏身份',
    art: '/assets/roles/hidden-back.svg?art=night-v1',
    cls: 'ww-fx--start'
  },
  WIN_GOOD: {
    title: '好人阵营胜利',
    subtitle: '所有狼人已被清除',
    art: '/assets/roles/villager-card.svg?art=night-v1',
    cls: 'ww-fx--win'
  },
  WIN_WEREWOLF: {
    title: '狼人阵营胜利',
    subtitle: '狼人已掌控村庄',
    art: '/assets/roles/werewolf-card.svg?art=night-v1',
    cls: 'ww-fx--win'
  }
}

const meta = computed(() => (current.value ? META[current.value.key] ?? null : null))

const dynamicSubtitle = computed(() => {
  const req = current.value
  if (!req) {
    return undefined
  }
  if (req.seatId && (req.key === 'WOLF_KILL' || req.key === 'PLAYER_DIED')) {
    return `${req.seatId} 号玩家`
  }
  if (req.seatId && req.key === 'VOTE_EXILE') {
    return `${req.seatId} 号被放逐出局`
  }
  if (req.seatId && (req.key === 'SEER_CHECK' || req.key === 'WITCH_HEAL' || req.key === 'WITCH_POISON')) {
    return `目标：${req.seatId} 号`
  }
  return meta.value?.subtitle
})

/** 取出下一条特效并播放。 */
function playNext() {
  if (playing.value) {
    return
  }
  const next = game.consumeEffect()
  if (!next) {
    return
  }
  if (!META[next.key]) {
    // 未知事件：直接丢弃，避免卡住队列
    playNext()
    return
  }
  current.value = next
  playing.value = true
  const duration = DURATION[next.key] ?? 1600
  timer = window.setTimeout(() => {
    playing.value = false
    current.value = null
    timer = null
    playNext()
  }, duration)
}

// 队列变化时尝试播放
watch(
  () => game.pendingEffects.length,
  () => playNext()
)

onMounted(() => {
  playNext()
})

onUnmounted(() => {
  if (timer !== null) {
    window.clearTimeout(timer)
    timer = null
  }
})

defineExpose({ playNext, playing })
</script>

<template>
  <transition name="ww-fx-fade">
    <div
      v-if="current && meta"
      class="ww-fx"
      :class="meta.cls"
      role="status"
      aria-live="polite"
    >
      <div class="ww-fx__bg" />
      <img v-if="meta.art" class="ww-fx__art" :src="meta.art" :alt="meta.title" />
      <div v-else-if="meta.icon" class="ww-fx__skull">{{ meta.icon }}</div>
      <div class="ww-fx__title">{{ meta.title }}</div>
      <div v-if="dynamicSubtitle" class="ww-fx__subtitle">{{ dynamicSubtitle }}</div>
    </div>
  </transition>
</template>

<style scoped>
.ww-fx-fade-enter-active,
.ww-fx-fade-leave-active {
  transition: opacity 0.25s ease;
}

.ww-fx-fade-enter-from,
.ww-fx-fade-leave-to {
  opacity: 0;
}
</style>
