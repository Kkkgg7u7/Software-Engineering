<script setup lang="ts">
/**
 * 预言家翻牌结果动画（30 秒确认窗口）。
 *
 * 交互流程：
 *   1. 卡片先显示背面，播放一段翻牌动画；
 *   2. 翻到正面后显示查验结论 —— **好人蓝色调，狼人红色调**；
 *   3. 底部出现倒计时（默认 30 秒）与「知道了」按钮；
 *   4. 按下按钮或倒计时走完，才进入下一步。
 *
 * 计时以后端下发为准：`secondsLeft` 由服务端每秒推一次，
 * 本地不做自减，避免客户端改表或时钟漂移导致绕过。
 */
import { computed, ref, watch } from 'vue'

const props = defineProps<{
  /** 被查验的座位号。 */
  targetSeatId: number | null
  targetNickname: string | null
  targetAvatar?: string | null
  /** true = 狼人（红），false = 好人（蓝）。 */
  wolf: boolean
  /** 剩余秒数（服务端权威）。 */
  secondsLeft: number
  /** 总秒数，用于画进度。 */
  totalSeconds: number
  /** 提交中。 */
  loading?: boolean
}>()

const emit = defineEmits<{ (e: 'confirm'): void }>()

/** 是否已经翻到正面（动画播完）。 */
const flipped = ref(false)

// 每次出现新的查验结果都重新播一遍翻牌动画
watch(
  () => [props.targetSeatId, props.wolf],
  () => {
    flipped.value = false
    window.setTimeout(() => {
      flipped.value = true
    }, 420)
  },
  { immediate: true }
)

/** 结果色调：好人蓝、狼人红。 */
const tone = computed(() => (props.wolf ? 'wolf' : 'good'))
const verdict = computed(() => (props.wolf ? '狼人' : '好人'))
const campLabel = computed(() => (props.wolf ? '狼人阵营' : '好人阵营'))

const percent = computed(() => {
  const total = Math.max(1, props.totalSeconds)
  return Math.max(0, Math.min(100, Math.round((props.secondsLeft / total) * 100)))
})

const urgent = computed(() => props.secondsLeft > 0 && props.secondsLeft <= 5)
</script>

<template>
  <div class="reveal" :class="`reveal--${tone}`">
    <div class="reveal__panel">
      <div class="reveal__head">
        <span class="reveal__badge">预言家查验结果</span>
        <span class="reveal__seat ww-mono">{{ targetSeatId }} 号</span>
      </div>

      <!-- 3D 翻牌：背面 -> 正面 -->
      <div class="reveal__stage">
        <div class="reveal__card" :class="{ 'reveal__card--flipped': flipped }">
          <!-- 背面 -->
          <div class="reveal__face reveal__face--back">
            <img src="/assets/roles/hidden-back.svg?art=night-v1" alt="身份牌背面" />
            <span class="reveal__back-text">正在查验…</span>
          </div>
          <!-- 正面：结论 -->
          <div class="reveal__face reveal__face--front">
            <el-avatar :size="72" :src="targetAvatar || undefined">
              {{ (targetNickname || '?').slice(0, 1) }}
            </el-avatar>
            <div class="reveal__name">{{ targetNickname }}</div>
            <div class="reveal__verdict">{{ verdict }}</div>
            <div class="reveal__camp">{{ campLabel }}</div>
          </div>
        </div>
      </div>

      <p class="reveal__desc">
        <template v-if="wolf">
          <strong>{{ targetSeatId }} 号 {{ targetNickname }}</strong> 是<strong class="reveal__hl">狼人</strong>，
          请记住这个结论。
        </template>
        <template v-else>
          <strong>{{ targetSeatId }} 号 {{ targetNickname }}</strong> 是<strong class="reveal__hl">好人</strong>，
          可以把他当作可信的同伴。
        </template>
      </p>

      <!-- 30 秒确认倒计时 -->
      <div class="reveal__timer">
        <div class="reveal__bar">
          <div
            class="reveal__bar-fill"
            :class="{ 'reveal__bar-fill--urgent': urgent }"
            :style="{ width: `${percent}%` }"
          />
        </div>
        <span class="reveal__seconds ww-mono" :class="{ 'reveal__seconds--urgent': urgent }">
          {{ secondsLeft }}s
        </span>
      </div>

      <el-button
        class="reveal__btn"
        type="primary"
        size="large"
        :loading="loading"
        @click="emit('confirm')"
      >
        知道了，继续
      </el-button>
      <p class="reveal__hint ww-muted">
        按下按钮或倒计时走完才会进入下一步。
      </p>
    </div>
  </div>
</template>

<style scoped>
.reveal {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(6, 10, 24, 0.82);
  backdrop-filter: blur(6px);
  animation: reveal-fade 0.25s ease-out;
}

@keyframes reveal-fade {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/* 好人蓝 / 狼人红：整块面板的色调都跟着变 */
.reveal--good {
  --reveal-main: #3b82f6;
  --reveal-soft: rgba(59, 130, 246, 0.22);
  --reveal-glow: rgba(59, 130, 246, 0.55);
}

.reveal--wolf {
  --reveal-main: #ef4444;
  --reveal-soft: rgba(239, 68, 68, 0.22);
  --reveal-glow: rgba(239, 68, 68, 0.6);
}

.reveal__panel {
  width: min(420px, 92vw);
  padding: 24px 24px 20px;
  border-radius: 18px;
  text-align: center;
  background: linear-gradient(180deg, #131a2e, #0d1220);
  border: 1px solid var(--reveal-main);
  box-shadow: 0 0 44px var(--reveal-glow), 0 18px 48px rgba(0, 0, 0, 0.5);
}

.reveal__head {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-bottom: 16px;
}

.reveal__badge {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--reveal-main);
  background: var(--reveal-soft);
  border: 1px solid var(--reveal-main);
}

.reveal__seat {
  font-weight: 700;
  color: var(--reveal-main);
}

/* ------------------------------------------------ 3D 翻牌 ---- */

.reveal__stage {
  perspective: 1100px;
  height: 236px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.reveal__card {
  position: relative;
  width: 168px;
  height: 236px;
  transform-style: preserve-3d;
  transition: transform 0.85s cubic-bezier(0.4, 0.2, 0.2, 1);
}

.reveal__card--flipped {
  transform: rotateY(180deg);
}

.reveal__face {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 14px;
  backface-visibility: hidden;
  overflow: hidden;
}

.reveal__face--back {
  background: radial-gradient(circle at 50% 30%, #1d2745, #0b1020);
  border: 1px solid rgba(255, 255, 255, 0.14);
}

.reveal__face--back img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.9;
}

.reveal__back-text {
  position: absolute;
  bottom: 12px;
  font-size: 12px;
  letter-spacing: 2px;
  color: rgba(255, 255, 255, 0.7);
}

.reveal__face--front {
  transform: rotateY(180deg);
  background: linear-gradient(180deg, var(--reveal-soft), rgba(10, 14, 28, 0.96));
  border: 2px solid var(--reveal-main);
  box-shadow: inset 0 0 34px var(--reveal-soft);
}

.reveal__name {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.85);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reveal__verdict {
  font-size: 34px;
  font-weight: 800;
  letter-spacing: 4px;
  color: var(--reveal-main);
  text-shadow: 0 0 18px var(--reveal-glow);
}

.reveal__camp {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
  color: var(--reveal-main);
  background: var(--reveal-soft);
}

.reveal__desc {
  margin: 16px 0 14px;
  font-size: 14px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.82);
}

.reveal__hl {
  color: var(--reveal-main);
}

/* ------------------------------------------------ 倒计时 ---- */

.reveal__timer {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.reveal__bar {
  flex: 1;
  height: 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  overflow: hidden;
}

.reveal__bar-fill {
  height: 100%;
  background: var(--reveal-main);
  transition: width 0.9s linear;
}

.reveal__bar-fill--urgent {
  animation: reveal-blink 1s ease-in-out infinite;
}

@keyframes reveal-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

.reveal__seconds {
  flex: none;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--reveal-main);
}

.reveal__seconds--urgent {
  color: #fbbf24;
}

.reveal__btn {
  width: 100%;
}

.reveal__hint {
  margin: 10px 0 0;
  font-size: 12px;
}
</style>
