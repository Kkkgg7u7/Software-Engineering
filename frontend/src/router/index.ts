import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { TOKEN_KEY } from '@/api/request'

/**
 * 路由表。所有业务页面都要求登录（全局守卫统一拦截），
 * 未登录访问受保护页面会被重定向到 /login 并带上 redirect 参数。
 */
const routes: RouteRecordRaw[] = [
  { path: '/games', name: 'games', component: () => import('@/views/GamesView.vue'), meta: { title: '游戏' } },
  { path: '/join/:roomCode', name: 'room-join', component: () => import('@/views/RoomJoinView.vue'), props: true, meta: { title: '游戏邀请' } },
  { path: '/wall', name: 'wall', component: () => import('@/views/WallView.vue'), meta: { title: '表白墙' } },
  { path: '/chat/:id?', name: 'conversations', component: () => import('@/views/ConversationsView.vue'), meta: { title: '聊天' } },
  {
    path: '/',
    redirect: '/games'
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录 / 注册' }
  },
  {
    path: '/lobby',
    name: 'lobby',
    component: () => import('@/views/LobbyView.vue'),
    meta: { title: '游戏大厅' }
  },
  {
    path: '/room/:roomCode',
    name: 'room',
    component: () => import('@/views/RoomView.vue'),
    props: true,
    meta: { title: '房间' }
  },
  {
    path: '/game/:roomCode',
    name: 'game',
    component: () => import('@/views/GameView.vue'),
    props: true,
    meta: { title: '对局中' }
  },
  {
    path: '/friends',
    name: 'friends',
    component: () => import('@/views/FriendsView.vue'),
    meta: { title: '好友管理' }
  },
  {
    path: '/me',
    name: 'profile',
    component: () => import('@/views/ProfileView.vue'),
    meta: { title: '我的' }
  },
  {
    path: '/records',
    name: 'records',
    component: () => import('@/views/RecordsView.vue'),
    meta: { title: '战绩与排行' }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  const hasToken = !!localStorage.getItem(TOKEN_KEY)
  if (to.meta.public) {
    // 已登录用户不必再看登录页
    if (hasToken && to.name === 'login') {
      return { name: 'games' }
    }
    return true
  }
  if (!hasToken) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string) || '狼人杀'
  document.title = `${title} · 啾啾 JiuJiu`
})

export default router
