<script setup lang="ts">
/**
 * 对局信息面板：事件日志 + 发言记录。
 *
 * 设计取舍：
 * - 事件日志按时间正序（新消息在底部）并自动滚到底，符合“看直播”的直觉；
 * - 页签用自绘按钮而不是 el-tabs，暗色主题下无需额外覆写 Element Plus 的浅色变量；
 * - 数据只来自 game store（`eventLog` / `speeches`），组件不发起任何请求。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useGameStore } from '@/stores/game'
import type { SpeechRecord } from '@/types'

type LogTab = 'events' | 'speeches'

interface LogItem {
  time: number
  text: string
  type: string
}

const game = useGameStore()

const activeTab = ref<LogTab>('events')
const eventsRef = ref<HTMLDivElement | null>(null)
const speechesRef = ref<HTMLDivElement | null>(null)

const events = computed<LogItem[]>(() => game.eventLog)

/** 按轮次分组，轮次升序、组内保持发言顺序。 */
const speechGroups = computed<Array<{ round: number; items: SpeechRecord[] }>>(() => {
  const map = new Map<number, SpeechRecord[]>()
  game.speeches.forEach((speech) => {
    const key = speech.round ?? 0
    const list = map.get(key)
    if (list) {
      list.push(speech)
    } else {
      map.set(key, [speech])
    }
  })
  return Array.from(map.entries())
    .sort((a, b) => a[0] - b[0])
    .map(([round, items]) => ({ round, items }))
})

function formatTime(time: number): string {
  const date = new Date(time)
  if (Number.isNaN(date.getTime())) {
    return '--:--:--'
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function setTab(tab: LogTab) {
  activeTab.value = tab
}

async function scrollToBottom(el: HTMLDivElement | null) {
  await nextTick()
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

watch(
  () => events.value.length,
  () => {
    if (activeTab.value === 'events') {
      void scrollToBottom(eventsRef.value)
    }
  }
)

watch(
  () => game.speeches.length,
  () => {
    if (activeTab.value === 'speeches') {
      void scrollToBottom(speechesRef.value)
    }
  }
)

watch(activeTab, (tab) => {
  if (tab === 'events') {
    void scrollToBottom(eventsRef.value)
  } else {
    void scrollToBottom(speechesRef.value)
  }
})
</script>

<template>
  <section class="log-panel ww-panel">
    <div class="log-panel__tabs">
      <button
        type="button"
        class="log-panel__tab"
        :class="{ 'log-panel__tab--active': activeTab === 'events' }"
        @click="setTab('events')"
      >
        事件日志
        <span class="log-panel__count">{{ events.length }}</span>
      </button>
      <button
        type="button"
        class="log-panel__tab"
        :class="{ 'log-panel__tab--active': activeTab === 'speeches' }"
        @click="setTab('speeches')"
      >
        发言记录
        <span class="log-panel__count">{{ game.speeches.length }}</span>
      </button>
    </div>

    <!-- 事件日志 -->
    <div v-show="activeTab === 'events'" ref="eventsRef" class="log-panel__body ww-scroll">
      <template v-if="events.length">
        <div v-for="(item, index) in events" :key="index" class="log-panel__item">
          <span class="log-panel__dot" :class="`log-panel__dot--${item.type}`" />
          <span class="log-panel__time ww-mono">{{ formatTime(item.time) }}</span>
          <span class="log-panel__text" :class="`log-panel__text--${item.type}`">
            {{ item.text }}
          </span>
        </div>
      </template>
      <p v-else class="ww-muted log-panel__empty">暂无事件，等待游戏开始…</p>
    </div>

    <!-- 发言记录 -->
    <div v-show="activeTab === 'speeches'" ref="speechesRef" class="log-panel__body ww-scroll">
      <template v-if="speechGroups.length">
        <div v-for="group in speechGroups" :key="group.round" class="log-panel__group">
          <div class="log-panel__group-title">第 {{ group.round }} 轮</div>
          <div v-for="(speech, index) in group.items" :key="index" class="log-panel__speech">
            <div class="log-panel__speech-head">
              <span class="ww-seat-badge">{{ speech.seatId }}</span>
              <span class="log-panel__speaker">{{ speech.nickname }}</span>
              <span v-if="speech.ai" class="ww-tag ww-tag--ai">AI</span>
            </div>
            <p class="log-panel__speech-text">{{ speech.content }}</p>
          </div>
        </div>
      </template>
      <p v-else class="ww-muted log-panel__empty">暂无发言记录</p>
    </div>
  </section>
</template>

<style scoped>
.log-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.log-panel__tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--ww-border-soft);
  background: rgba(8, 12, 24, 0.35);
}

.log-panel__tab {
  appearance: none;
  border: 1px solid transparent;
  background: transparent;
  color: var(--ww-text-dim);
  font-family: inherit;
  font-size: 13px;
  padding: 4px 10px;
  border-radius: 999px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  transition: all 0.18s ease;
}

.log-panel__tab:hover {
  color: var(--ww-text);
  background: var(--ww-primary-soft);
}

.log-panel__tab--active {
  color: var(--ww-moon);
  background: var(--ww-moon-soft);
  border-color: rgba(251, 191, 36, 0.45);
  font-weight: 700;
}

.log-panel__count {
  font-family: var(--ww-font-mono);
  font-size: 10px;
  padding: 0 5px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.18);
  color: inherit;
}

.log-panel__body {
  flex: 1;
  min-height: 120px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.log-panel__empty {
  margin: 0;
}

.log-panel__item {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  font-size: 12px;
  line-height: 1.55;
}

.log-panel__dot {
  flex: none;
  width: 7px;
  height: 7px;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--ww-text-mute);
  box-shadow: 0 0 6px currentColor;
}

.log-panel__dot--wolf {
  background: var(--ww-wolf);
}

.log-panel__dot--private {
  background: var(--ww-seer);
}

.log-panel__dot--important {
  background: var(--ww-moon);
}

.log-panel__time {
  flex: none;
  font-size: 10px;
  color: var(--ww-text-mute);
  margin-top: 1px;
}

.log-panel__text {
  color: var(--ww-text-dim);
  word-break: break-word;
}

.log-panel__text--wolf {
  color: var(--ww-wolf);
}

.log-panel__text--private {
  color: var(--ww-seer);
}

.log-panel__text--important {
  color: var(--ww-moon);
  font-weight: 700;
}

.log-panel__group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.log-panel__group-title {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--ww-moon);
  border-bottom: 1px dashed var(--ww-border-soft);
  padding-bottom: 3px;
}

.log-panel__speech {
  padding: 6px 8px;
  border-radius: var(--ww-radius-sm);
  background: var(--ww-panel-2);
  border: 1px solid var(--ww-border-soft);
}

.log-panel__speech-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 3px;
}

.log-panel__speaker {
  font-size: 12px;
  font-weight: 700;
  color: var(--ww-text);
}

.log-panel__speech-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ww-text-dim);
  word-break: break-word;
  white-space: pre-wrap;
}
</style>
