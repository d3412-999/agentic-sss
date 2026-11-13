import { createRouter, createWebHistory } from 'vue-router'
import { isAuthenticated, currentRole } from '../stores/session'
import { toast } from '../stores/toast'
import { resumeFor } from '../persist/resume'

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
  // 管理员平台唯一入口：教师账号与权限管理
  {
    path: '/admin/teachers',
    name: 'admin-teachers',
    component: () => import('../views/AdminTeachersView.vue'),
    meta: { title: '教师账号', roles: ['ADMIN'] },
  },
  {
    // 默认落地页：教师进「我的教学班」，学生进「我的课程」。
    // 原「概览」页的 KPI / 待办 / 通知摘要已收敛为列表页顶部的紧凑摘要条，
    // 不再占用一个独立路由，`/` 仅作为按角色分发的入口保留。
    path: '/',
    redirect: () => (currentRole.value === 'TEACHER' ? '/offerings' : '/courses'),
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
    meta: { title: '课程详情', roles: ['TEACHER'] },
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
    // 沉浸模式：答题时隐藏一级栏让出宽度，退出入口由页头面包屑提供
    meta: { title: '答辩', roles: ['TEACHER', 'STUDENT'], immersive: true },
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
    return currentRole.value === 'ADMIN' ? { name: 'admin-teachers' } : { path: '/' }
  }
  // 管理员平台只保留教师账号管理，禁止进入任何教师端 / 学生端页面
  if (currentRole.value === 'ADMIN' && !to.path.startsWith('/admin')) {
    return { name: 'admin-teachers' }
  }
  const roles = to.meta.roles
  if (roles?.length && !roles.includes(currentRole.value)) {
    toast.info('没有访问权限', '当前角色不能打开该页面，已返回首页')
    return { path: '/' }
  }
  document.title = to.meta.title ? `${to.meta.title} · 智能答辩与评分系统` : '智能答辩与评分系统'
  return true
})

/**
 * 进入页面后统一跑一次全局恢复（清理过期本地数据 + 续上后台任务轮询）。
 *
 * 放 afterEach 而不是 beforeEach：导航已经放行，恢复逻辑再慢也不会卡住页面切换；
 * 钩子内部所有异常都被吞掉，失败只打 warn。
 */
router.afterEach((to, from, failure) => {
  if (failure) return
  void resumeFor(to)
})

export default router
