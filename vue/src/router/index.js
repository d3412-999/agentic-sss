import { createRouter, createWebHistory } from 'vue-router'
import { isAuthenticated, currentRole } from '../stores/session'
import { toast } from '../stores/toast'

/**
 * meta.roles 为页面允许的角色；空数组表示登录即可访问。
 * 角色以后端 sys_user 为准，这里只做前端体验层的守卫。
 */
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
    path: '/offerings',
    name: 'offerings',
    component: () => import('../views/OfferingsView.vue'),
    meta: { title: '教学班', roles: ['TEACHER'] },
  },
  {
    path: '/offerings/:offeringId',
    name: 'offering-workbench',
    component: () => import('../views/OfferingWorkbenchView.vue'),
    meta: { title: '开课工作台', roles: ['TEACHER'] },
  },
  {
    path: '/courses',
    name: 'courses',
    component: () => import('../views/CoursesView.vue'),
    meta: { title: '我的课程', roles: ['STUDENT'] },
  },
  {
    path: '/courses/:offeringId/topics',
    name: 'topic-select',
    component: () => import('../views/TopicSelectView.vue'),
    meta: { title: '选题中心', roles: ['STUDENT'] },
  },
  {
    path: '/selections',
    name: 'selections',
    component: () => import('../views/SelectionsView.vue'),
    meta: { title: '我的选题', roles: ['STUDENT'] },
  },
  {
    path: '/grades',
    name: 'grades',
    component: () => import('../views/GradesView.vue'),
    meta: { title: '成绩中心', roles: ['TEACHER', 'STUDENT'] },
  },
  {
    path: '/defense/:sessionId',
    name: 'defense',
    component: () => import('../views/DefenseView.vue'),
    meta: { title: '答辩', roles: ['TEACHER', 'STUDENT'] },
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('../views/TasksView.vue'),
    meta: { title: '任务中心' },
  },
  {
    path: '/notifications',
    name: 'notifications',
    component: () => import('../views/NotificationsView.vue'),
    meta: { title: '站内通知' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('../views/SettingsView.vue'),
    meta: { title: '账号设置' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('../views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  if (!to.meta.public && !isAuthenticated.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && isAuthenticated.value) {
    return { name: 'dashboard' }
  }
  const roles = to.meta.roles
  if (roles?.length && !roles.includes(currentRole.value)) {
    toast.info('没有访问权限', '当前角色不能打开该页面，已返回概览')
    return { name: 'dashboard' }
  }
  document.title = to.meta.title ? `${to.meta.title} · 智能答辩与评分系统` : '智能答辩与评分系统'
  return true
})

export default router
