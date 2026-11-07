import { createRouter, createWebHistory } from 'vue-router'
import { isAuthenticated } from '../stores/session'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    name: 'dashboard',
    component: () => import('../views/DashboardView.vue'),
    meta: { title: '概览' },
  },
  {
    path: '/topics',
    name: 'topics',
    component: () => import('../views/TopicsView.vue'),
    meta: { title: '开课与题目' },
  },
  {
    path: '/notifications',
    name: 'notifications',
    component: () => import('../views/NotificationsView.vue'),
    meta: { title: '站内通知' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  if (!to.meta.public && !isAuthenticated.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && isAuthenticated.value) {
    return { name: 'dashboard' }
  }
  document.title = to.meta.title ? `${to.meta.title} · 智能答辩与评分系统` : '智能答辩与评分系统'
})

export default router
