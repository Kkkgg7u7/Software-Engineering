<script setup lang="ts">
/**
 * 404 页面（公开路由，未登录也能看到）。
 *
 * 设计取舍：装饰全部用 CSS + 现成品牌素材完成，不引入新图片，
 * 主按钮直接把用户送回大厅（未登录时路由守卫会自动转到登录页并带 redirect）。
 */
import { useRouter } from 'vue-router'

const router = useRouter()

function goLobby() {
  void router.push({ name: 'lobby' })
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  goLobby()
}
</script>

<template>
  <div class="not-found">
    <div class="not-found__sky" aria-hidden="true">
      <div class="not-found__moon" />
      <div class="not-found__wolf">🐺</div>
      <div class="not-found__hills" />
    </div>

    <img class="not-found__logo ww-float" src="/assets/brand/jiujiu-256.png" alt="AI 狼人杀" />

    <h1 class="not-found__code">404</h1>
    <p class="not-found__title">这个房间不存在…</p>
    <p class="not-found__desc">
      也许它已经被解散，或者你输错了房间码。回到大厅重新选一个房间吧。
    </p>

    <div class="not-found__actions">
      <el-button type="primary" size="large" @click="goLobby">返回大厅</el-button>
      <el-button size="large" text @click="goBack">返回上一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.not-found {
  position: relative;
  min-height: calc(100vh - 64px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px 20px 60px;
  text-align: center;
  overflow: hidden;
}

/* ------------------------------------------------------------ 装饰层 -- */
.not-found__sky {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.not-found__moon {
  position: absolute;
  top: 8%;
  right: 14%;
  width: 180px;
  height: 180px;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 35%, var(--ww-text) 0%, var(--ww-moon) 48%, rgba(251, 191, 36, 0) 74%);
  opacity: 0.42;
  filter: blur(1px);
  animation: ww-float 6s ease-in-out infinite;
}

.not-found__wolf {
  position: absolute;
  left: 12%;
  bottom: 22%;
  font-size: clamp(80px, 12vw, 150px);
  opacity: 0.12;
  filter: grayscale(1) brightness(1.6);
  transform: scaleX(-1);
}

.not-found__hills {
  position: absolute;
  left: -10%;
  right: -10%;
  bottom: -8%;
  height: 34%;
  background: radial-gradient(ellipse at 50% 0%, rgba(124, 58, 237, 0.22), rgba(7, 10, 20, 0) 70%),
    linear-gradient(180deg, rgba(7, 10, 20, 0) 0%, rgba(5, 7, 15, 0.85) 100%);
}

/* ------------------------------------------------------------ 内容层 -- */
.not-found__logo {
  object-fit: contain;
  position: relative;
  width: 84px;
  height: 84px;
  border-radius: var(--ww-radius-lg);
  box-shadow: var(--ww-shadow-glow);
}

.not-found__code {
  position: relative;
  margin: 8px 0 0;
  font-family: var(--ww-font-mono);
  font-size: clamp(56px, 10vw, 108px);
  letter-spacing: 10px;
  background: linear-gradient(120deg, var(--ww-moon), var(--ww-primary));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.not-found__title {
  position: relative;
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 3px;
  color: var(--ww-text);
}

.not-found__desc {
  position: relative;
  margin: 0;
  max-width: 460px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--ww-text-dim);
}

.not-found__actions {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  flex-wrap: wrap;
  justify-content: center;
}
</style>
