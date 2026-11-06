import { createRouter, createWebHistory } from 'vue-router'
import { session } from '@/stores/session'

const AppShell = () => import('@/components/AppShell.vue')

const routes = [
  { path: '/', redirect: () => (session.role === 'teacher' ? '/teacher/offerings' : '/student') },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/student',
    component: AppShell,
    meta: { role: 'student' },
    children: [
      { path: '', name: 'student-home', component: () => import('@/views/student/StudentHomeView.vue'), meta: { title: '学习台' } },
      { path: 'courses', name: 'student-courses', component: () => import('@/views/student/CourseListView.vue'), meta: { title: '选择课程' } },
      { path: 'courses/:offeringId/topics', name: 'student-topics', component: () => import('@/views/student/TopicSelectView.vue'), meta: { title: '选择题目' } },
      { path: 'selections', name: 'student-selections', component: () => import('@/views/student/SelectionListView.vue'), meta: { title: '我的选题' } },
      { path: 'selections/:selectionId', name: 'student-selection', component: () => import('@/views/student/SelectionDetailView.vue'), meta: { title: '提交与评分' } },
      { path: 'defense', name: 'student-defense', component: () => import('@/views/student/DefenseView.vue'), meta: { title: '在线答辩' } },
      { path: 'grades', name: 'student-grades', component: () => import('@/views/student/GradeView.vue'), meta: { title: '我的成绩' } }
    ]
  },
  {
    path: '/teacher',
    component: AppShell,
    meta: { role: 'teacher' },
    children: [
      { path: '', redirect: { name: 'teacher-offerings' } },
      { path: 'offerings', name: 'teacher-offerings', component: () => import('@/views/teacher/OfferingListView.vue'), meta: { title: '我的开课' } },
      { path: 'offerings/new', name: 'teacher-offering-new', component: () => import('@/views/teacher/OfferingCreateView.vue'), meta: { title: '新建开课' } },
      { path: 'offerings/:offeringId', name: 'teacher-offering', component: () => import('@/views/teacher/OfferingDetailView.vue'), meta: { title: '开课概览' } },
      { path: 'offerings/:offeringId/students', name: 'teacher-students', component: () => import('@/views/teacher/StudentListView.vue'), meta: { title: '学生名单' } },
      { path: 'offerings/:offeringId/topics', name: 'teacher-topics', component: () => import('@/views/teacher/TopicManageView.vue'), meta: { title: '题目管理' } },
      { path: 'offerings/:offeringId/defenses', name: 'teacher-defenses', component: () => import('@/views/teacher/DefenseReviewListView.vue'), meta: { title: '答辩复核' } },
      { path: 'defenses/:defenseId', name: 'teacher-defense', component: () => import('@/views/teacher/DefenseReviewView.vue'), meta: { title: '答辩详情' } },
      { path: 'offerings/:offeringId/grades', name: 'teacher-grades', component: () => import('@/views/teacher/GradeTableView.vue'), meta: { title: '成绩管理' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || { top: 0 }
  }
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!session.authenticated) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (to.meta.role && to.meta.role !== session.role) {
    return { path: session.role === 'teacher' ? '/teacher' : '/student' }
  }
  return true
})

export default router
