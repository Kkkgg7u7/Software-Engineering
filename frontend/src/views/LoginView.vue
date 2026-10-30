<script setup lang="ts">
/**
 * 登录 / 注册页。
 *
 * 设计取舍：
 * - 左侧品牌区 + 右侧卡片的两栏布局，窄屏自动堆叠；
 * - 表单校验全部在前端完成（用户名正则、密码长度、两次密码一致），
 *   后端错误通过 ApiError.message 直接提示，不用自己映射错误码；
 * - 健康检查失败只显示灰色提示条，不阻塞登录表单（后端没起也能看到界面）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** el-tabs 的 modelValue 是 string | number，这里保持宽类型避免类型不兼容。 */
const activeTab = ref<string | number>('login')

const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()

const loginForm = reactive({
  username: '',
  password: ''
})

const registerForm = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  avatar: '/assets/avatars/bot-01.svg'
})

/** 预置头像（12 个）供注册时选择。 */
const avatars = Array.from(
  { length: 12 },
  (_, index) => `/assets/avatars/bot-${String(index + 1).padStart(2, '0')}.svg`
)

const redirect = computed(() => {
  const target = route.query.redirect
  return typeof target === 'string' && target ? target : '/lobby'
})

/** 后端健康状态：null = 检测中，true/false = 结果。 */
const healthy = ref<boolean | null>(null)
const showLocalDemo=['localhost','127.0.0.1','[::1]'].includes(window.location.hostname)

const loginRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]+$/, message: '用户名只能包含字母、数字和下划线', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 位', trigger: 'blur' }
  ]
}

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]{3,20}$/,
      message: '用户名需为 3-20 位字母、数字或下划线',
      trigger: 'blur'
    }
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 12, message: '昵称长度为 2-12 位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== registerForm.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return '请求失败，请稍后重试'
}

/** el-radio-group 的 change 回调类型较宽，这里统一收敛成 string。 */
function handleAvatarChange(value: string | number | boolean | undefined) {
  registerForm.avatar = String(value ?? '')
}

async function checkHealth() {
  try {
    await authApi.health()
    healthy.value = true
  } catch {
    healthy.value = false
  }
}

async function submitLogin() {
  const form = loginFormRef.value
  if (!form) {
    return
  }
  const valid = await form.validate().catch(() => false)
  if (!valid) {
    return
  }
  try {
    await auth.login({ username: loginForm.username.trim(), password: loginForm.password })
    ElMessage.success('登录成功，欢迎回来')
    await router.push(redirect.value)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function submitRegister() {
  const form = registerFormRef.value
  if (!form) {
    return
  }
  const valid = await form.validate().catch(() => false)
  if (!valid) {
    return
  }
  try {
    await auth.register({
      username: registerForm.username.trim(),
      nickname: registerForm.nickname.trim(),
      password: registerForm.password,
      avatar: registerForm.avatar
    })
    ElMessage.success('注册成功，已自动登录')
    await router.push(redirect.value)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function handleTabChange(name: string | number) {
  activeTab.value = name
}

onMounted(() => {
  void checkHealth()
})
</script>

<template>
  <div class="login-view">
    <!-- 左侧品牌区 -->
    <section class="login-view__brand">
      <img class="login-view__logo ww-float" src="/assets/brand/jiujiu-256.png" alt="啾啾 JiuJiu" />
      <h1 class="login-view__title">啾啾 JiuJiu</h1>
      <p class="login-view__slogan">月圆之夜，谎言与推理同场竞技</p>
      <ul class="login-view__features">
        <li>服务器权威判定，多人实时同步</li>
        <li>支持 13 人标准局、九种角色与警长选举</li>
        <li>公共、私聊、狼人三频道即时沟通</li>
      </ul>
    </section>

    <!-- 右侧登录 / 注册卡片 -->
    <section class="login-view__card ww-panel ww-panel--pad">
      <el-tabs :model-value="activeTab" class="login-view__tabs" @tab-change="handleTabChange">
        <el-tab-pane label="登录" name="login">
          <div class="login-view__form" @keydown.enter.prevent="submitLogin">
            <el-form
              ref="loginFormRef"
              :model="loginForm"
              :rules="loginRules"
              label-position="top"
            >
              <el-form-item label="用户名" prop="username">
                <el-input v-model="loginForm.username" placeholder="请输入用户名" clearable />
              </el-form-item>
              <el-form-item label="密码" prop="password">
                <el-input
                  v-model="loginForm.password"
                  type="password"
                  placeholder="请输入密码"
                  show-password
                />
              </el-form-item>
            </el-form>
            <el-button
              type="primary"
              class="login-view__submit"
              :loading="auth.loading"
              @click="submitLogin"
            >
              登录
            </el-button>
          </div>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <div class="login-view__form" @keydown.enter.prevent="submitRegister">
            <el-form
              ref="registerFormRef"
              :model="registerForm"
              :rules="registerRules"
              label-position="top"
            >
              <el-form-item label="用户名" prop="username">
                <el-input
                  v-model="registerForm.username"
                  placeholder="3-20 位字母、数字或下划线"
                  clearable
                />
              </el-form-item>
              <el-form-item label="昵称" prop="nickname">
                <el-input v-model="registerForm.nickname" placeholder="对局中显示的名字" clearable />
              </el-form-item>
              <el-form-item label="密码" prop="password">
                <el-input
                  v-model="registerForm.password"
                  type="password"
                  placeholder="6-32 位"
                  show-password
                />
              </el-form-item>
              <el-form-item label="确认密码" prop="confirmPassword">
                <el-input
                  v-model="registerForm.confirmPassword"
                  type="password"
                  placeholder="请再次输入密码"
                  show-password
                />
              </el-form-item>
              <el-form-item label="头像">
                <el-radio-group
                  class="login-view__avatars"
                  :model-value="registerForm.avatar"
                  @change="handleAvatarChange"
                >
                  <el-radio
                    v-for="avatar in avatars"
                    :key="avatar"
                    :value="avatar"
                    class="login-view__avatar"
                  >
                    <img :src="avatar" alt="头像" />
                  </el-radio>
                </el-radio-group>
              </el-form-item>
            </el-form>
            <el-button
              type="primary"
              class="login-view__submit"
              :loading="auth.loading"
              @click="submitRegister"
            >
              注册并进入大厅
            </el-button>
          </div>
        </el-tab-pane>
      </el-tabs>

      <!-- 后端健康状态 -->
      <div
        class="login-view__health"
        :class="healthy === false ? 'login-view__health--down' : 'login-view__health--up'"
      >
        <span class="login-view__dot" />
        <template v-if="healthy === true">后端服务已连接</template>
        <template v-else-if="healthy === false">后端未连接，登录 / 注册会失败，请先启动服务</template>
        <template v-else>正在检测后端服务…</template>
      </div>

      <div v-if="showLocalDemo" class="login-view__demo">
        <div class="login-view__demo-title">演示账号（本地环境）</div>
        <p>
          4 人局请开 4 个浏览器窗口：<code>demo</code> / <code>player1</code> /
          <code>player2</code> / <code>player3</code>，密码均为 <code>demo1234</code>。
        </p>
        <p class="ww-muted">首次使用请先在上方「注册」标签页创建账号。</p>
      </div>

      <div class="login-view__notice">
        <strong>重要：一个账号只能同时在线一台设备</strong>
        <p>
          玩家的身份与座位绑定在<b>账号</b>上，不是绑定在设备上。如果多个人用同一个账号登录，
          大家会被分配到<b>同一个座位</b>，看起来就像"所有人共用了一个玩家"。
        </p>
        <p>
          所以每个人都要<b>各自注册一个账号</b>；用同一台电脑开多个窗口测试时，
          除第一个之外的窗口都要用<b>无痕 / 隐私模式</b>——否则多个标签页会共享登录状态。
        </p>
        <p class="ww-muted">
          后登录的设备会顶掉先登录的设备（先登录的那台会提示"已在其他设备登录"）。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-view {
  min-height: calc(100vh - 64px);
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  align-items: center;
  gap: 40px;
  padding: 40px clamp(20px, 6vw, 90px);
}

/* ------------------------------------------------------------ 品牌区 -- */
.login-view__brand {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 14px;
}

.login-view__logo {
  object-fit: contain;
  width: 96px;
  height: 96px;
  border-radius: var(--ww-radius-lg);
  box-shadow: var(--ww-shadow-glow);
}

.login-view__title {
  font-size: clamp(32px, 4.4vw, 52px);
  letter-spacing: 6px;
  background: linear-gradient(100deg, var(--ww-moon), var(--ww-primary));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.login-view__slogan {
  margin: 0;
  font-size: 16px;
  letter-spacing: 2px;
  color: var(--ww-text-dim);
}

.login-view__features {
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.login-view__features li {
  position: relative;
  padding-left: 20px;
  font-size: 13px;
  color: var(--ww-text-dim);
}

.login-view__features li::before {
  content: '🌙';
  position: absolute;
  left: 0;
  top: -1px;
  font-size: 12px;
}

/* ------------------------------------------------------------ 表单卡片 -- */
.login-view__card {
  width: 100%;
  max-width: 460px;
  justify-self: center;
}

.login-view__form {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-top: 6px;
}

.login-view__submit {
  width: 100%;
  margin-top: 6px;
}

.login-view__tabs :deep(.el-tabs__nav-wrap::after) {
  background-color: var(--ww-border-soft);
}

.login-view__tabs :deep(.el-tabs__item) {
  color: var(--ww-text-dim);
  font-size: 15px;
}

.login-view__tabs :deep(.el-tabs__item.is-active) {
  color: var(--ww-moon);
}

.login-view__tabs :deep(.el-tabs__active-bar) {
  background-color: var(--ww-moon);
}

/* -------------------------------------------------------------- 头像 -- */
.login-view__avatars {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
  width: 100%;
}

.login-view__avatar {
  margin-right: 0;
  height: auto;
  padding: 4px;
}

.login-view__avatar :deep(.el-radio__label) {
  padding-left: 0;
  display: block;
  line-height: 0;
}

.login-view__avatar :deep(.el-radio__input) {
  display: none;
}

.login-view__avatar img {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 2px solid transparent;
  background: rgba(8, 12, 24, 0.6);
  transition: all 0.18s ease;
}

.login-view__avatar.is-checked img {
  border-color: var(--ww-moon);
  box-shadow: 0 0 12px rgba(251, 191, 36, 0.55);
}

/* ---------------------------------------------------------- 健康状态 -- */
.login-view__health {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding: 7px 12px;
  border-radius: var(--ww-radius-sm);
  font-size: 12px;
}

.login-view__health--up {
  color: var(--ww-villager);
  background: var(--ww-villager-soft);
  border: 1px solid rgba(52, 211, 153, 0.3);
}

.login-view__health--down {
  color: var(--ww-text-mute);
  background: rgba(148, 163, 184, 0.12);
  border: 1px solid rgba(148, 163, 184, 0.28);
}

.login-view__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 8px currentColor;
}

/* ------------------------------------------------------------ 演示账号 -- */
.login-view__demo {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--ww-border-soft);
}

.login-view__demo-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ww-moon);
  letter-spacing: 1px;
  margin-bottom: 6px;
}

.login-view__demo p {
  margin: 0 0 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ww-text-dim);
}

/* ------------------------------------------------------- 单账号提示 -- */
.login-view__notice {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: var(--ww-radius-sm);
  background: rgba(251, 191, 36, 0.08);
  border: 1px solid rgba(251, 191, 36, 0.32);
}

.login-view__notice strong {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  letter-spacing: 0.5px;
  color: var(--ww-moon);
}

.login-view__notice p {
  margin: 0 0 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ww-text-dim);
}

.login-view__notice p:last-child {
  margin-bottom: 0;
}

.login-view__notice b {
  color: var(--ww-text);
}

.login-view__demo code {
  font-family: var(--ww-font-mono);
  font-size: 12px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(124, 58, 237, 0.18);
  color: var(--ww-moon);
}

@media (max-width: 900px) {
  .login-view {
    grid-template-columns: minmax(0, 1fr);
    gap: 24px;
    padding: 28px 18px 40px;
  }

  .login-view__brand {
    align-items: center;
    text-align: center;
  }

  .login-view__features {
    display: none;
  }
}
</style>
