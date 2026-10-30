<script setup lang="ts">
/**
 * 阶段倒计时环。
 *
 * 设计取舍：
 * - 后端只下发 `phaseSecondsLeft`（剩余秒数），不下发阶段总时长，因此这里按阶段维护一份
 *   本地总时长表仅用于画进度弧，数字始终显示后端下发的剩余秒数；
 * - 颜色按阶段用 computed 切换类名（夜晚紫蓝 / 白天金色 / 投票红色 / 结束灰色）。
 */
import { computed } from 'vue'
import type { GamePhase } from '@/types'

const props = defineProps<{
  phase: GamePhase
  phaseLabel: string
  round: number
  secondsLeft: number
  aliveCount: number
  /** 本阶段需要操作的总人数（夜晚 = 狼+预言家+女巫；投票 = 存活人数）。 */
  actorCount?: number
  /** 已点"完成"的人数。 */
  doneCount?: number
  /** 还没点"完成"的座位号。 */
  pendingSeats?: number[]
}>()

/** 圆环几何参数。 */
const RADIUS = 52
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/** 各阶段的名义总时长（仅影响弧长比例，不影响数字，必须与后端默认值一致）。 */
const PHASE_TOTAL: Record<GamePhase, number> = {
  WAITING: 30,
  STARTING: 10,
  // 身份确认：60 秒（后端 werewolf.phase.confirm-seconds）
  CONFIRM: 60,
  // 夜晚行动：狼人 / 预言家 / 女巫合计 3 分钟
  NIGHT: 180,
  DAY: 20,
  SHERIFF_VOTING: 90,
  DEATH_SKILL: 30,
  // 讨论发言：每人 5 分钟
  DISCUSSION: 300,
  // 投票：3 分钟
  VOTING: 180,
  CHECK_WINNER: 10,
  END: 30
}

const phaseTotal = computed(() => PHASE_TOTAL[props.phase] ?? 30)
const safeSeconds = computed(() => (Number.isFinite(props.secondsLeft) ? Math.max(0, props.secondsLeft) : 0))
const ratio = computed(() => {
  if (safeSeconds.value <= 0) {
    return 0
  }
  return Math.min(1, safeSeconds.value / Math.max(1, phaseTotal.value))
})
const dashOffset = computed(() => CIRCUMFERENCE * (1 - ratio.value))
const urgent = computed(() => safeSeconds.value > 0 && safeSeconds.value <= 15)

/** 线性倒计时条：mm:ss。 */
const clock = computed(() => {
  const total = safeSeconds.value
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
})

/** 需要操作人数的阶段才显示进度与"还等谁"。 */
const actorCount = computed(() => props.actorCount ?? 0)
const doneCount = computed(() => props.doneCount ?? 0)
const pendingSeats = computed(() => props.pendingSeats ?? [])
const hasActors = computed(() => actorCount.value > 0)
/** 等待/结束阶段不必显示进度条。 */
const showBar = computed(
  () => props.phase !== 'WAITING' && props.phase !== 'END' && safeSeconds.value > 0
)

/** 阶段配色：night / day / vote / over / idle。 */
const tone = computed(() => {
  switch (props.phase) {
    case 'NIGHT':
      return 'night'
    case 'DAY':
    case 'DISCUSSION':
    case 'STARTING':
      return 'day'
    case 'VOTING':
      return 'vote'
    case 'END':
      return 'over'
    default:
      return 'idle'
  }
})
</script>

<template>
  <div class="phase-timer" :class="[`phase-timer--${tone}`, { 'phase-timer--urgent': urgent }]">
    <div class="phase-timer__ring">
      <svg viewBox="0 0 120 120" class="phase-timer__svg" aria-hidden="true">
        <circle class="phase-timer__track" cx="60" cy="60" :r="RADIUS" />
        <circle
          class="phase-timer__value"
          cx="60"
          cy="60"
          :r="RADIUS"
          :stroke-dasharray="CIRCUMFERENCE"
          :stroke-dashoffset="dashOffset"
          transform="rotate(-90 60 60)"
        />
      </svg>
      <div class="phase-timer__center">
        <strong class="phase-timer__seconds">{{ safeSeconds }}</strong>
        <span class="phase-timer__unit">秒</span>
      </div>
    </div>

    <div class="phase-timer__meta">
      <div class="phase-timer__label">{{ phaseLabel || '等待中' }}</div>
      <div class="phase-timer__sub">
        <span class="phase-timer__chip ww-mono">第 {{ round }} 轮</span>
        <span class="phase-timer__chip">
          <span class="phase-timer__alive" />存活 {{ aliveCount }} 人
        </span>
      </div>

      <!-- 线性倒计时条：mm:ss + 进度。行动阶段还会显示"x/y 已完成" -->
      <div v-if="showBar" class="phase-timer__bar-wrap">
        <div class="phase-timer__bar">
          <div
            class="phase-timer__bar-fill"
            :class="{ 'phase-timer__bar-fill--urgent': urgent }"
            :style="{ width: `${Math.round(ratio * 100)}%` }"
          />
        </div>
        <span class="phase-timer__mmss ww-mono">{{ clock }}</span>
        <span v-if="hasActors" class="phase-timer__progress ww-mono">
          {{ doneCount }}/{{ actorCount }} 已完成
        </span>
      </div>

      <p v-if="hasActors && pendingSeats.length" class="phase-timer__pending">
        还等 {{ pendingSeats.map((s) => `${s} 号`).join('、') }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.phase-timer {
  --timer-color: var(--ww-primary);
  --timer-soft: var(--ww-primary-soft);
  display: flex;
  align-items: center;
  gap: 16px;
}

/* 阶段配色 */
.phase-timer--night {
  --timer-color: var(--ww-primary);
  --timer-soft: var(--ww-primary-soft);
}

.phase-timer--day {
  --timer-color: var(--ww-moon);
  --timer-soft: var(--ww-moon-soft);
}

.phase-timer--vote {
  --timer-color: var(--ww-wolf);
  --timer-soft: var(--ww-wolf-soft);
}

/* ------------------------------------------------ 线性倒计时条 ---- */

.phase-timer__bar-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  min-width: 220px;
}

.phase-timer__bar {
  flex: 1;
  height: 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--timer-color) 18%, transparent);
  overflow: hidden;
}

.phase-timer__bar-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--timer-color);
  transition: width 0.9s linear;
}

.phase-timer__bar-fill--urgent {
  background: var(--ww-danger, var(--el-color-danger));
  animation: phase-timer-blink 1s ease-in-out infinite;
}

@keyframes phase-timer-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

.phase-timer__mmss {
  flex: none;
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  font-size: 13px;
  color: var(--timer-color);
}

.phase-timer__progress {
  flex: none;
  font-size: 12px;
  opacity: 0.85;
}

.phase-timer__pending {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-warning);
}

.phase-timer--over {
  --timer-color: var(--ww-text-mute);
  --timer-soft: rgba(148, 163, 184, 0.16);
}

.phase-timer__ring {
  position: relative;
  width: 108px;
  height: 108px;
  flex: none;
}

.phase-timer__svg {
  width: 100%;
  height: 100%;
  transform: rotate(0deg);
}

.phase-timer__track {
  fill: none;
  stroke: rgba(148, 163, 184, 0.18);
  stroke-width: 9;
}

.phase-timer__value {
  fill: none;
  stroke: var(--timer-color);
  stroke-width: 9;
  stroke-linecap: round;
  filter: drop-shadow(0 0 8px var(--timer-color));
  transition: stroke-dashoffset 0.6s linear, stroke 0.4s ease;
}

.phase-timer__center {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 2px;
}

.phase-timer__seconds {
  font-family: var(--ww-font-mono);
  font-size: 38px;
  line-height: 108px;
  color: var(--timer-color);
  text-shadow: 0 0 18px var(--timer-soft);
}

.phase-timer__unit {
  font-size: 12px;
  color: var(--ww-text-mute);
}

.phase-timer__meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.phase-timer__label {
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 3px;
  color: var(--timer-color);
  text-shadow: 0 0 16px var(--timer-soft);
}

.phase-timer__sub {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.phase-timer__chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--ww-text-dim);
  background: rgba(148, 163, 184, 0.12);
  border: 1px solid var(--ww-border-soft);
}

.phase-timer__alive {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ww-villager);
  box-shadow: 0 0 8px var(--ww-villager);
}

/* 最后 5 秒脉冲提醒 */
.phase-timer--urgent .phase-timer__seconds {
  animation: ww-pulse 0.9s ease-in-out infinite;
}

.phase-timer--urgent .phase-timer__value {
  stroke: var(--ww-danger);
}

@media (max-width: 720px) {
  .phase-timer {
    width: 100%;
    min-width: 0;
    gap: 8px;
  }

  .phase-timer__meta {
    flex: 1;
    min-width: 0;
  }

  .phase-timer__bar-wrap {
    min-width: 0;
  }

  .phase-timer__ring {
    width: 84px;
    height: 84px;
  }

  .phase-timer__seconds {
    font-size: 28px;
    line-height: 84px;
  }

  .phase-timer__label {
    font-size: 16px;
    letter-spacing: 2px;
  }
}
</style>
