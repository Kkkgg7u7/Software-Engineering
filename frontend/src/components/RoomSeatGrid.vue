<script setup lang="ts">
/**
 * 房间座位网格：按 `room.maxPlayers` 渲染 1..N 个格子，空位显示虚线占位。
 *
 * 设计取舍：
 * - SeatView -> PlayerView 的转换只在这里做一次，保证 PlayerSeat 组件在房间页与对局页
 *   使用同一套数据结构；
 * - 踢人只 emit 事件，真实请求由页面调用 `room.kick()`，便于加入二次确认与错误提示。
 */
import { computed } from 'vue'
import PlayerSeat from '@/components/PlayerSeat.vue'
import { useRoomStore } from '@/stores/room'
import type { PlayerView, RoomView, SeatView } from '@/types'

const props = defineProps<{
  room: RoomView
  isHost: boolean
}>()

const emit = defineEmits<{ (e: 'kick', seatId: number): void }>()

const roomStore = useRoomStore()

/**
 * 我坐哪个座位。
 *
 * <p>优先取 store 里的权威值（来自私有队列 `/user/queue/room` 的 `mySeatId`），
 * 并用「座位 userId == 我的 userId」兜底。
 *
 * <p><b>绝不能依赖"谁是最后一个加入的人"</b>：早期后端把每个人的私有视图
 * 广播到公共主题，客户端最后收到的是最后加入者的视角，
 * 导致房主的「我」标记落到别人座位上（F-12）。
 */
const mySeatId = computed<number | null>(() => roomStore.mySeatId)

interface GridSeat {
  seatId: number
  player: PlayerView | null
  host: boolean
}

/** 把房间座位转换成对局页同款的 PlayerView（等待阶段一定是存活、未投票、未发言）。 */
function toPlayerView(seat: SeatView): PlayerView {
  return {
    seatId: seat.seatId,
    userId: seat.userId,
    nickname: seat.nickname,
    avatar: seat.avatar,
    alive: true,
    ready: seat.ready,
    ai: seat.ai,
    speaking: false,
    voted: false,
    role: (seat.role ?? null) as PlayerView['role']
  }
}

const gridSeats = computed<GridSeat[]>(() => {
  const total = Math.max(0, props.room.maxPlayers || 0)
  const source = props.room.seats ?? []
  const list: GridSeat[] = []
  for (let seatId = 1; seatId <= total; seatId += 1) {
    const found = source.find((seat) => seat.seatId === seatId)
    list.push({
      seatId,
      player: found ? toPlayerView(found) : null,
      host: !!found?.host
    })
  }
  return list
})

const occupiedCount = computed(() => gridSeats.value.filter((seat) => !!seat.player).length)

function canKick(seat: GridSeat): boolean {
  if (!props.isHost || !seat.player) {
    return false
  }
  // 房主不能把自己移出房间
  return seat.seatId !== mySeatId.value
}
</script>

<template>
  <div class="seat-grid-wrap">
    <div class="seat-grid">
      <template v-for="seat in gridSeats" :key="seat.seatId">
        <!-- 有人的座位 -->
        <div v-if="seat.player" class="seat-grid__cell">
          <PlayerSeat
            :player="seat.player"
            :seat-id="seat.seatId"
            :is-me="seat.seatId === mySeatId"
            :is-host="seat.host"
            compact
          />
          <el-popconfirm
            v-if="canKick(seat)"
            :title="`确定把 ${seat.player?.nickname ?? ''} 移出房间吗？`"
            confirm-button-text="移出"
            cancel-button-text="取消"
            confirm-button-type="danger"
            width="220"
            @confirm="emit('kick', seat.seatId)"
          >
            <template #reference>
              <el-button class="seat-grid__kick" size="small" type="danger" text>移出</el-button>
            </template>
          </el-popconfirm>
        </div>

        <!-- 空位 -->
        <div v-else class="seat-grid__cell seat-grid__cell--empty">
          <div class="seat-grid__empty">
            <span class="ww-seat-badge">{{ seat.seatId }}</span>
            <span class="seat-grid__empty-text">空位</span>
          </div>
        </div>
      </template>
    </div>

    <p class="ww-muted seat-grid__summary">
      已入座 {{ occupiedCount }} / {{ room.maxPlayers }} 个座位
    </p>
  </div>
</template>

<style scoped>
.seat-grid-wrap {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.seat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 10px;
}

.seat-grid__cell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.seat-grid__cell--empty {
  justify-content: center;
}

.seat-grid__empty {
  width: 100%;
  min-height: 104px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: var(--ww-radius);
  border: 1px dashed var(--ww-border-soft);
  background: rgba(8, 12, 24, 0.28);
  color: var(--ww-text-mute);
  transition: border-color 0.2s ease, color 0.2s ease;
}

.seat-grid__empty:hover {
  border-color: var(--ww-border);
  color: var(--ww-text-dim);
}

.seat-grid__empty-text {
  font-size: 12px;
  letter-spacing: 2px;
}

.seat-grid__kick {
  font-size: 11px;
}

.seat-grid__summary {
  margin: 0;
  text-align: right;
}
</style>
