<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { authApi } from '@/api/auth'
import { mediaApi } from '@/api/social'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'
import { useGameStore } from '@/stores/game'
import { useChatStore } from '@/stores/chat'

const auth = useAuthStore()
const room = useRoomStore()
const game = useGameStore()
const chat = useChatStore()
const router = useRouter()
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()
const changingPassword = ref(false)
const profileForm = ref<FormInstance>()
const passwordForm = ref<FormInstance>()
const profile = reactive({ nickname: '', signature: '', avatar: '' })
const password = reactive({ currentPassword: '', newPassword: '', confirmation: '' })
const avatars = Array.from({ length: 12 }, (_, i) => `/assets/avatars/bot-${String(i + 1).padStart(2, '0')}.svg`)
const wins = computed(() => auth.user?.winGames ?? 0)
const games = computed(() => auth.user?.totalGames ?? 0)
const winRate = computed(() => games.value ? Math.round(wins.value / games.value * 100) : 0)
const profileRules: FormRules = {
  nickname: [{ required: true, whitespace: true, message: '请输入昵称', trigger: 'blur' }, { max: 16, message: '昵称最多 16 个字符', trigger: 'blur' }],
  signature: [{ max: 120, message: '签名最多 120 个字符', trigger: 'blur' }],
  avatar: [{ required: true, message: '请选择默认头像或填写头像地址', trigger: 'blur' }, { pattern: /^(https?:\/\/[^\s]+|\/assets\/avatars\/[A-Za-z0-9._-]+|\/api\/media\/avatars\/[a-f0-9]{32})$/, message: '请填写 HTTP(S) 图片地址或选择默认头像', trigger: 'blur' }, { max: 255, message: '头像地址过长', trigger: 'blur' }]
}
const passwordRules: FormRules = {
  currentPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { min: 6, max: 32, message: '新密码长度为 6–32 位', trigger: 'blur' }],
  confirmation: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }, {
    validator: (_rule, value, callback) => callback(value === password.newPassword ? undefined : new Error('两次新密码不一致')),
    trigger: 'blur'
  }]
}
function message(error: unknown, fallback: string) { return error instanceof Error ? error.message : fallback }
function displayTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '暂无记录' }
async function loadProfile() {
  loading.value = true
  loadError.value = ''
  try {
    const info = await auth.refreshProfile()
    if (info) Object.assign(profile, { nickname: info.nickname, signature: info.signature ?? '', avatar: info.avatar ?? avatars[0] })
  } catch (error) { loadError.value = message(error, '资料加载失败') }
  finally { loading.value = false }
}
async function uploadAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) { ElMessage.error('图片不能超过 5MB'); input.value = ''; return }
  uploading.value = true
  try {
    const image = await mediaApi.upload(file, 'AVATAR')
    profile.avatar = image.url
    ElMessage.success('头像已上传，保存资料后生效')
  } catch (error) { ElMessage.error(message(error, '上传失败')) }
  finally { uploading.value = false; input.value = '' }
}
async function saveProfile() {
  if (!await profileForm.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    const info = await auth.updateProfile({ ...profile, nickname: profile.nickname.trim() })
    Object.assign(profile, { nickname: info.nickname, signature: info.signature ?? '', avatar: info.avatar })
    ElMessage.success('资料已保存')
  } catch (error) { ElMessage.error(message(error, '保存失败，请重试')) }
  finally { saving.value = false }
}
async function changePassword() {
  if (!await passwordForm.value?.validate().catch(() => false)) return
  changingPassword.value = true
  try {
    await authApi.changePassword({ currentPassword: password.currentPassword, newPassword: password.newPassword })
    Object.assign(password, { currentPassword: '', newPassword: '', confirmation: '' })
    auth.clear()
    room.resetLocal()
    game.reset()
    chat.leaveRoom()
    ElMessage.success('密码已修改，请使用新密码登录')
    await router.replace({ name: 'login' })
  } catch (error) { ElMessage.error(message(error, '密码修改失败')) }
  finally { changingPassword.value = false }
}
onMounted(loadProfile)
</script>

<template>
  <div class="profile-page">
    <header class="profile-heading">
      <div><span class="profile-eyebrow">JIUJIU / 我的</span><h1>你的啾啾名片</h1><p class="ww-muted">换个头像，写下今天想说的话。</p></div>
      <div class="profile-heading-actions"><el-button @click="router.push({ name: 'wall', query: { authorId: auth.user?.id } })">我的动态</el-button><el-button @click="router.push({ name: 'friends' })">好友管理</el-button><el-button @click="router.push({ name: 'records' })">游戏记录</el-button></div>
    </header>
    <el-skeleton v-if="loading" :rows="8" animated class="ww-panel ww-panel--pad" />
    <el-result v-else-if="loadError" icon="warning" title="暂时无法读取资料" :sub-title="loadError">
      <template #extra><el-button type="primary" @click="loadProfile">重新加载</el-button></template>
    </el-result>
    <template v-else>
      <section class="profile-summary ww-panel ww-panel--pad" aria-label="账号概览">
        <el-avatar :size="76" :src="auth.user?.avatar">{{ auth.displayName.slice(0, 1) }}</el-avatar>
        <div class="profile-identity"><h2>{{ auth.displayName }}</h2><p class="ww-muted">@{{ auth.user?.username }} · ID {{ auth.user?.id }}</p><p>{{ auth.user?.signature || '还没有写签名，先从一局游戏开始吧。' }}</p></div>
        <div class="profile-stats"><div><strong>{{ games }}</strong><span>总场次</span></div><div><strong>{{ wins }}</strong><span>胜场</span></div><div><strong>{{ winRate }}%</strong><span>胜率</span></div></div>
      </section>
      <div class="profile-columns">
        <section class="ww-panel ww-panel--pad">
          <h2>个人资料</h2><p class="ww-muted">昵称和头像用于展示，账号登录名保持独立。</p>
          <el-form ref="profileForm" :model="profile" :rules="profileRules" label-position="top" @submit.prevent="saveProfile">
            <el-form-item label="昵称" prop="nickname"><el-input v-model="profile.nickname" maxlength="16" show-word-limit autocomplete="nickname" /></el-form-item>
            <el-form-item label="个性签名" prop="signature"><el-input v-model="profile.signature" type="textarea" :rows="3" maxlength="120" show-word-limit placeholder="分享你的桌游心情" /></el-form-item>
            <fieldset class="avatar-picker"><legend>选择默认头像</legend><div><button v-for="avatar in avatars" :key="avatar" type="button" :aria-pressed="profile.avatar === avatar" :aria-label="`选择头像 ${avatars.indexOf(avatar) + 1}`" @click="profile.avatar = avatar"><img :src="avatar" alt="" /></button></div></fieldset>
            <div class="profile-upload"><el-avatar :size="42" :src="profile.avatar" /><el-button :loading="uploading" @click="fileInput?.click()">上传头像</el-button><small class="ww-muted">JPEG / PNG / WebP，最多 5MB。头像为公开图片。</small><input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" hidden @change="uploadAvatar" /></div>
            <el-form-item label="头像图片地址" prop="avatar"><el-input v-model="profile.avatar" maxlength="255" placeholder="https://… 或选择上方默认头像" /></el-form-item>
            <el-button type="primary" native-type="submit" :loading="saving" :disabled="uploading">保存资料</el-button>
          </el-form>
          <dl class="profile-dates"><div><dt>注册时间</dt><dd>{{ displayTime(auth.user?.createdAt) }}</dd></div><div><dt>最近更新</dt><dd>{{ displayTime(auth.user?.updatedAt) }}</dd></div></dl>
        </section>
        <section class="ww-panel ww-panel--pad">
          <h2>账号安全</h2><p class="ww-muted">修改密码后，需要重新登录，当前实时连接也会断开。</p>
          <el-alert v-if="room.current" :closable="false" type="warning" title="你仍在房间中，建议结束对局后再修改密码。" class="profile-room-warning" />
          <el-form ref="passwordForm" :model="password" :rules="passwordRules" label-position="top" @submit.prevent="changePassword">
            <el-form-item label="原密码" prop="currentPassword"><el-input v-model="password.currentPassword" type="password" show-password autocomplete="current-password" maxlength="32" /></el-form-item>
            <el-form-item label="新密码" prop="newPassword"><el-input v-model="password.newPassword" type="password" show-password autocomplete="new-password" maxlength="32" placeholder="6–32 位" /></el-form-item>
            <el-form-item label="确认新密码" prop="confirmation"><el-input v-model="password.confirmation" type="password" show-password autocomplete="new-password" maxlength="32" /></el-form-item>
            <el-button native-type="submit" :loading="changingPassword">修改密码并重新登录</el-button>
          </el-form>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.profile-page { max-width: 1208px; margin: 0 auto; width: 100%; padding: 24px; }
.profile-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin: 8px 0 24px; }
.profile-eyebrow { color: var(--ww-moon); font-size: 11px; letter-spacing: 2px; }
h1 { font-size: clamp(24px, 4vw, 32px); margin: 10px 0; }
h2 { font-size: 19px; margin: 0 0 10px; }
.profile-heading p { margin: 0; }
.profile-heading-actions, .profile-upload { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.profile-upload { margin-bottom: 20px; }.profile-upload small { font-size: 11px; width: 100%; }
.profile-summary { display: flex; align-items: center; gap: 20px; margin-bottom: 22px; }
.profile-identity { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.profile-identity p { margin: 6px 0; font-size: 13px; }
.profile-stats { display: flex; gap: 28px; text-align: center; }
.profile-stats strong, .profile-stats span { display: block; }
.profile-stats strong { font-size: 23px; color: var(--ww-moon); }
.profile-stats span { margin-top: 6px; font-size: 12px; color: var(--ww-text-dim); }
.profile-columns { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 22px; align-items: start; }
.profile-columns section > .ww-muted { font-size: 13px; line-height: 1.7; margin: 0 0 24px; }
.avatar-picker { margin: 0 0 20px; padding: 0; border: 0; }
.avatar-picker legend { font-size: 13px; margin-bottom: 10px; }
.avatar-picker > div { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; }
.avatar-picker button { padding: 5px; border: 2px solid transparent; border-radius: 12px; background: rgba(255,255,255,.04); cursor: pointer; }
.avatar-picker button[aria-pressed="true"] { border-color: var(--ww-moon); background: rgba(251,191,36,.1); }
.avatar-picker button:focus-visible { outline: 2px solid white; outline-offset: 3px; }
.avatar-picker img { width: 100%; display: block; border-radius: 8px; }
.profile-dates { margin: 24px 0 0; padding-top: 14px; border-top: 1px solid var(--ww-border); color: var(--ww-text-dim); font-size: 12px; }
.profile-dates > div { display: flex; justify-content: space-between; gap: 12px; margin-top: 8px; }
.profile-dates dd { margin: 0; }
.profile-room-warning { margin-bottom: 18px; }
@media (max-width: 760px) {
  .profile-page { padding: 18px 16px; }
  .profile-columns { grid-template-columns: 1fr; }
  .profile-summary { flex-wrap: wrap; gap: 16px; }
  .profile-stats { width: 100%; padding-top: 16px; border-top: 1px solid var(--ww-border); justify-content: space-around; }
  .profile-heading { align-items: flex-start; flex-direction: column; }
}
</style>
