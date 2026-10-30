<script setup lang="ts">
/**
 * 战绩页：个人战绩 + 对局说明（角色 / 阶段）。
 *
 * 设计取舍：
 * - 战绩只依赖 `auth.refreshProfile()`（后端 /users/me），不额外维护统计接口；
 * - 角色与阶段图例来自 `game.loadMeta()`，前端不写死，后端改板子时页面自动跟着变；
 * - 接口失败只显示空态与重试按钮，页面不白屏。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useGameStore } from '@/stores/game'

const auth = useAuthStore()
const game = useGameStore()
const router = useRouter()

const loadingProfile = ref(false)
const profileError = ref(false)
const metaLoading = ref(false)

const totalGames = computed(() => auth.user?.totalGames ?? 0)
const winGames = computed(() => auth.user?.winGames ?? 0)
const loseGames = computed(() => Math.max(0, totalGames.value - winGames.value))
const winRate = computed(() => {
  if (totalGames.value <= 0) {
    return 0
  }
  return Math.round((winGames.value / totalGames.value) * 100)
})

const progressColor = computed(() => {
  if (winRate.value >= 60) {
    return 'var(--ww-moon)'
  }
  return winRate.value >= 40 ? 'var(--ww-primary)' : 'var(--ww-seer)'
})

async function loadProfile() {
  loadingProfile.value = true
  profileError.value = false
  try {
    await auth.refreshProfile()
  } catch (error) {
    profileError.value = true
    ElMessage.error(error instanceof Error ? error.message : '战绩加载失败')
  } finally {
    loadingProfile.value = false
  }
}

async function loadMeta() {
  metaLoading.value = true
  try {
    await game.loadMeta()
  } catch {
    ElMessage.error('角色与阶段说明加载失败')
  } finally {
    metaLoading.value = false
  }
}

function roleCardSrc(name: string): string {
  return `/assets/roles/${name.toLowerCase()}-card.svg?art=night-v1`
}

function roleClass(name: string): string {
  return `role-${name.toLowerCase()}`
}

/** 直接从 meta 取图例（用 computed 避免模板里对可能为 null 的 meta 做深层访问）。 */
const metaRoles = computed(() => game.meta?.roles ?? [])
const metaPhases = computed(() => game.meta?.phases ?? [])

onMounted(() => {
  void loadProfile()
  void loadMeta()
})
</script>

<template>
  <div class="records">
    <!-- 个人战绩 -->
    <section class="ww-panel ww-panel--pad">
      <div class="ww-panel__title">
        我的战绩
        <span class="ww-spacer" />
        <el-button size="small" text type="primary" :loading="loadingProfile" @click="loadProfile">
          刷新
        </el-button>
        <el-button size="small" text @click="router.push({ name: 'lobby' })">返回大厅</el-button>
      </div>

      <div class="records__grid">
        <div class="records__card">
          <el-statistic title="总场次" :value="totalGames" />
        </div>
        <div class="records__card">
          <el-statistic title="胜场" :value="winGames" />
        </div>
        <div class="records__card">
          <el-statistic title="负场" :value="loseGames" />
        </div>
        <div class="records__card records__card--rate">
          <el-progress
            type="dashboard"
            :percentage="winRate"
            :width="120"
            :stroke-width="10"
            :color="progressColor"
          >
            <template #default="{ percentage }">
              <div class="records__rate">
                <strong>{{ percentage }}%</strong>
                <span class="ww-muted">胜率</span>
              </div>
            </template>
          </el-progress>
        </div>
      </div>

      <p v-if="profileError" class="ww-muted records__tip">
        战绩数据获取失败，可能是后端未启动；已显示本地缓存数据。
      </p>
      <p v-else-if="totalGames === 0" class="ww-muted records__tip">
        还没有完成的对局，去大厅开一局吧。
      </p>
    </section>

    <!-- 对局说明 -->
    <section class="ww-panel ww-panel--pad">
      <div class="ww-panel__title">
        对局说明
        <span class="ww-spacer" />
        <el-button size="small" text type="primary" :loading="metaLoading" @click="loadMeta">
          重新加载
        </el-button>
      </div>

      <template v-if="game.meta">
        <h4 class="records__subtitle">角色</h4>
        <div class="records__roles">
          <article
            v-for="role in metaRoles"
            :key="role.name"
            class="role-tile"
            :class="roleClass(role.name)"
          >
            <img
              class="role-tile__card"
              :src="roleCardSrc(role.name)"
              :alt="role.label"
              loading="lazy"
            />
            <div class="role-tile__body">
              <div class="role-tile__head">
                <strong>{{ role.label }}</strong>
                <span class="ww-tag" :class="`ww-tag--${role.camp === 'WEREWOLF' ? 'wolf' : 'villager'}`">
                  {{ role.campLabel }}
                </span>
              </div>
              <p class="role-tile__skill">{{ role.skill }}</p>
            </div>
          </article>
        </div>

        <h4 class="records__subtitle">阶段</h4>
        <ol class="records__phases">
          <li v-for="phase in metaPhases" :key="phase.name" class="records__phase">
            <span class="records__phase-dot" :class="{ 'records__phase-dot--night': phase.isNight }" />
            <strong>{{ phase.label }}</strong>
            <span class="ww-muted">{{ phase.isNight ? '夜晚' : '白天' }}</span>
          </li>
        </ol>
      </template>

      <el-empty v-else :image-size="80" description="说明数据加载失败或后端未启动">
        <el-button type="primary" :loading="metaLoading" @click="loadMeta">重新加载</el-button>
      </el-empty>
    </section>
  </div>
</template>

<style scoped>
.records {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px clamp(14px, 4vw, 32px) 32px;
  max-width: 1280px;
  margin: 0 auto;
}

/* -------------------------------------------------------------- 战绩 -- */
.records__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 14px;
  align-items: center;
}

.records__card {
  padding: 16px 18px;
  border-radius: var(--ww-radius);
  border: 1px solid var(--ww-border-soft);
  background: var(--ww-panel-2);
}

.records__card--rate {
  display: flex;
  align-items: center;
  justify-content: center;
}

.records__rate {
  display: flex;
  flex-direction: column;
  align-items: center;
  line-height: 1.3;
}

.records__rate strong {
  font-family: var(--ww-font-mono);
  font-size: 22px;
  color: var(--ww-moon);
}

.records__tip {
  margin: 14px 0 0;
  line-height: 1.7;
}

/* ---------------------------------------------------------- 对局说明 -- */
.records__subtitle {
  margin: 18px 0 10px;
  font-size: 14px;
  color: var(--ww-text);
  letter-spacing: 1px;
}

.records__subtitle:first-of-type {
  margin-top: 4px;
}

.records__roles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}

.role-tile {
  display: flex;
  gap: 12px;
  padding: 12px;
  border-radius: var(--ww-radius);
  border: 1px solid var(--ww-border-soft);
  background: var(--ww-panel-2);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.role-tile:hover {
  border-color: var(--role-color, var(--ww-border));
  transform: translateY(-2px);
}

.role-tile__card {
  width: 74px;
  flex: none;
  border-radius: var(--ww-radius-sm);
  border: 1px solid var(--role-color, var(--ww-border));
  background: var(--ww-bg-soft);
}

.role-tile__body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.role-tile__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.role-tile__head strong {
  font-size: 15px;
  color: var(--role-color, var(--ww-text));
}

.role-tile__skill {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ww-text-dim);
}

.records__phases {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.records__phase {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--ww-border-soft);
  background: var(--ww-panel-2);
  font-size: 13px;
}

.records__phase strong {
  color: var(--ww-text);
  font-weight: 700;
}

.records__phase-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ww-moon);
  box-shadow: 0 0 8px var(--ww-moon);
}

.records__phase-dot--night {
  background: var(--ww-primary);
  box-shadow: 0 0 8px var(--ww-primary);
}
</style>
