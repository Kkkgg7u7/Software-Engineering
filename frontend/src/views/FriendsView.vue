<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { socialApi } from '@/api/social'
import { conversationApi } from '@/api/conversation'
import { useAuthStore } from '@/stores/auth'
import ws from '@/websocket/stomp'
import type { FriendRequest, PublicUser } from '@/types/social'

const auth = useAuthStore()
const router = useRouter()
const friends = ref<PublicUser[]>([])
const requests = ref<FriendRequest[]>([])
const results = ref<PublicUser[]>([])
const query = ref('')
const searched = ref(false)
const searchLoading = ref(false)
const loading = ref(false)
const error = ref('')
const busy = ref<number | null>(null)
const friendPage = ref(1)
const friendTotal = ref(0)
const requestPage = ref(1)
const requestTotal = ref(0)
const searchPage = ref(1)
const searchTotal = ref(0)
const direction = ref<'incoming' | 'outgoing'>('incoming')
const profile = ref<PublicUser | null>(null)
const profileOpen = ref(false)
let off: (() => void) | undefined
let refreshSequence = 0
let searchSequence = 0
function message(e: unknown) { return e instanceof Error ? e.message : '操作失败，请重试' }
async function refresh() {
  const sequence = ++refreshSequence
  loading.value = true; error.value = ''
  try {
    const [list, applications] = await Promise.all([socialApi.friends(friendPage.value), socialApi.requests(direction.value, requestPage.value)])
    if (sequence !== refreshSequence) return
    friends.value = list.items; friendTotal.value = list.total
    requests.value = applications.items; requestTotal.value = applications.total
  } catch (e) { if (sequence === refreshSequence) error.value = message(e) }
  finally { if (sequence === refreshSequence) loading.value = false }
}
async function search(reset = false) {
  const sequence = ++searchSequence
  if (reset) searchPage.value = 1
  if (!query.value.trim()) { ElMessage.warning('请输入昵称、登录名或用户 ID'); return }
  searchLoading.value = true
  try {
    const list = await socialApi.search(query.value.trim(), searchPage.value)
    if (sequence !== searchSequence) return
    results.value = list.items; searchTotal.value = list.total; searched.value = true
  } catch (e) { if (sequence === searchSequence) ElMessage.error(message(e)) }
  finally { if (sequence === searchSequence) searchLoading.value = false }
}
async function act(id: number, action: () => Promise<unknown>, text: string) {
  if (busy.value !== null) return
  busy.value = id
  try { await action(); ElMessage.success(text); await refresh() }
  catch (e) { ElMessage.error(message(e)) }
  finally { busy.value = null }
}
async function openChat(user: PublicUser) {
  try { const c = await conversationApi.direct(user.id); await router.push(`/chat/${c.id}`) }
  catch (e) { ElMessage.error(message(e)) }
}
async function remove(user: PublicUser) {
  try { await ElMessageBox.confirm(`删除与「${user.nickname}」的好友关系？`, '删除好友', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  await act(user.id, () => socialApi.remove(user.id), '好友关系已解除')
}
async function showProfile(user: PublicUser) {
  profile.value = user; profileOpen.value = true
  try { profile.value = await socialApi.user(user.id) }
  catch (e) { ElMessage.error(message(e)) }
}
function status(value: FriendRequest['status']) { return { PENDING: '等待处理', ACCEPTED: '已成为好友', REJECTED: '已拒绝', DELETED: '关系已解除' }[value] }
onMounted(() => {
  void refresh()
  off = ws.subscribeWith('/user/queue/social', envelope => { if (envelope.type === 'FRIEND_UPDATE') void refresh() })
})
onUnmounted(() => off?.())
</script>

<template>
  <div class="friends-page">
    <header class="friends-heading"><div><span>JIUJIU / 好友</span><h1>一起玩的朋友</h1><p class="ww-muted">找到熟悉的名字，也认识新的桌游伙伴。</p></div><el-button @click="router.push({ name: 'profile' })">返回个人中心</el-button></header>
    <el-alert v-if="error" type="error" :title="error" :closable="false" class="friends-error" />
    <div class="friends-grid">
      <section class="ww-panel ww-panel--pad">
        <h2>发现朋友</h2>
        <form class="friends-search" @submit.prevent="search(true)"><el-input v-model="query" maxlength="50" placeholder="昵称、登录名或用户 ID" aria-label="搜索用户" clearable /><el-button native-type="submit" type="primary" :loading="searchLoading">搜索</el-button></form>
        <el-empty v-if="searched && !results.length" description="没有找到匹配的用户" :image-size="70" />
        <ul v-if="results.length" class="people-list">
          <li v-for="user in results" :key="user.id"><button type="button" class="person-info" @click="showProfile(user)"><el-avatar :size="42" :src="user.avatar" /><span><strong>{{ user.nickname }}</strong><small>ID {{ user.id }} · {{ user.signature || '还没有签名' }}</small></span></button><el-tag v-if="user.id === auth.user?.id" size="small">我</el-tag><el-button v-else size="small" :disabled="busy !== null" @click="act(user.id, () => socialApi.add(user.id), '好友申请已发送')">加好友</el-button></li>
        </ul>
        <el-pagination v-if="searchTotal > 20" v-model:current-page="searchPage" :total="searchTotal" :page-size="20" layout="prev, pager, next" small @current-change="search(false)" />
        <el-divider />
        <h2>好友申请</h2>
        <el-radio-group v-model="direction" size="small" @change="requestPage = 1; refresh()"><el-radio-button value="incoming">收到的</el-radio-button><el-radio-button value="outgoing">发出的</el-radio-button></el-radio-group>
        <el-empty v-if="!requests.length && !loading" description="暂无申请" :image-size="65" />
        <ul class="people-list">
          <li v-for="item in requests" :key="item.id"><button type="button" class="person-info" @click="showProfile(direction === 'incoming' ? item.requester : item.recipient)"><el-avatar :size="40" :src="(direction === 'incoming' ? item.requester : item.recipient).avatar" /><span><strong>{{ (direction === 'incoming' ? item.requester : item.recipient).nickname }}</strong><small>{{ item.createdAt.replace('T', ' ').slice(0, 16) }}</small></span></button><div class="request-actions" v-if="direction === 'incoming' && item.status === 'PENDING'"><el-button size="small" type="primary" :disabled="busy !== null" @click="act(item.id, () => socialApi.accept(item.id), '已添加好友')">同意</el-button><el-button size="small" :disabled="busy !== null" @click="act(item.id, () => socialApi.reject(item.id), '已拒绝申请')">拒绝</el-button></div><el-tag v-else size="small">{{ status(item.status) }}</el-tag></li>
        </ul>
        <el-pagination v-if="requestTotal > 20" v-model:current-page="requestPage" :total="requestTotal" :page-size="20" layout="prev, pager, next" small @current-change="refresh" />
      </section>
      <section class="ww-panel ww-panel--pad">
        <div class="friends-list-title"><h2>我的好友 <span>{{ friendTotal }}</span></h2><el-button size="small" :loading="loading" @click="refresh">刷新</el-button></div>
        <p class="ww-muted friends-hint">在线状态由实时连接判断，刷新可获取最新状态。</p>
        <el-empty v-if="!friends.length && !loading" description="还没有好友，从左侧搜索开始吧" :image-size="90" />
        <ul class="people-list"><li v-for="user in friends" :key="user.id"><button type="button" class="person-info" @click="showProfile(user)"><el-avatar :size="46" :src="user.avatar" /><span><strong>{{ user.nickname }} <i :class="{ online: user.online }" :title="user.online ? '在线' : '离线'" /></strong><small>{{ user.signature || `ID ${user.id}` }}</small></span></button><el-button size="small" type="primary" plain @click="openChat(user)">发消息</el-button><el-button size="small" text type="danger" :disabled="busy !== null" @click="remove(user)">删除</el-button></li></ul>
        <el-pagination v-if="friendTotal > 20" v-model:current-page="friendPage" :total="friendTotal" :page-size="20" layout="prev, pager, next" small @current-change="refresh" />
      </section>
    </div>
    <el-dialog v-model="profileOpen" title="用户资料" width="min(440px, 92vw)">
      <div v-if="profile" class="public-profile"><el-avatar :size="72" :src="profile.avatar" /><h2>{{ profile.nickname }}</h2><p class="ww-muted">ID {{ profile.id }} · {{ profile.online ? '在线' : '离线' }}</p><p>{{ profile.signature || '还没有签名' }}</p><p class="ww-muted">{{ profile.totalGames }} 场对局 · {{ profile.winGames }} 场获胜</p></div>
    </el-dialog>
  </div>
</template>

<style scoped>
.friends-page { max-width: 1208px; padding: 24px; margin: auto; }
.friends-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin: 8px 0 24px; }
.friends-heading span { font-size: 11px; color: var(--ww-moon); letter-spacing: 2px; }
h1 { margin: 10px 0; font-size: clamp(24px,4vw,32px); } h2 { margin: 0 0 18px; font-size: 19px; }
.friends-heading p { margin: 0; } .friends-error { margin-bottom: 18px; }
.friends-grid { display: grid; grid-template-columns: minmax(0,1fr) minmax(0,1fr); gap: 22px; align-items: start; }
.friends-search { display: flex; gap: 10px; }
.people-list { list-style: none; margin: 18px 0 0; padding: 0; }
.people-list li { display: flex; align-items: center; gap: 12px; padding: 14px 0; border-bottom: 1px solid var(--ww-border-soft); }
.person-info { background: none; border: 0; color: inherit; text-align: left; display: flex; align-items: center; flex: 1; gap: 12px; min-width: 0; cursor: pointer; padding: 0; font: inherit; }
.person-info > span { min-width: 0; } .person-info strong { display: block; font-size: 14px; } .person-info small { display: block; color: var(--ww-text-dim); margin-top: 6px; font-size: 12px; overflow-wrap: anywhere; }
.person-info i { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--ww-text-mute); margin-left: 4px; } .person-info i.online { background: var(--ww-success); }
.request-actions { display: flex; gap: 4px; flex-wrap: wrap; justify-content: flex-end; }.request-actions .el-button { margin: 0; }
.friends-list-title { display: flex; justify-content: space-between; align-items: start; }.friends-list-title h2 span { color: var(--ww-moon); margin-left: 5px; }.friends-hint { margin: 0; font-size: 12px; line-height: 1.6; }
.public-profile { text-align: center; overflow-wrap: anywhere; }.public-profile h2 { margin: 14px 0 0; }
@media(max-width:760px) { .friends-page { padding: 18px 16px; }.friends-grid { grid-template-columns: 1fr; }.friends-heading { flex-direction: column; align-items: start; }.people-list li { gap: 7px; }.person-info { gap: 8px; } }
</style>
