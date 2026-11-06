<template>
  <div class="shell">
    <div v-if="mobileOpen" class="scrim" @click="mobileOpen = false" />

    <aside class="sidebar" :class="{ open: mobileOpen }">
      <div class="brand">
        <div class="brand-mark">S</div>
        <div class="brand-text">
          <div class="brand-name">智能答辩系统</div>
          <div class="brand-sub">课程设计 · 过程评价</div>
        </div>
      </div>

      <nav>
        <div v-for="group in nav" :key="group.title" class="nav-group">
          <div class="nav-group-title">{{ group.title }}</div>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            custom
            v-slot="{ href, navigate }"
          >
            <a
              :href="href"
              class="nav-item"
              :class="{ active: isActive(item) }"
              @click="go(navigate)"
            >
              <AppIcon :name="item.icon" :size="19" />
              <span>{{ item.label }}</span>
              <span v-if="item.badge" class="nav-badge">{{ item.badge }}</span>
            </a>
          </RouterLink>
        </div>
      </nav>

      <div class="sidebar-footer">
        <div class="between" style="margin-bottom: 6px">
          <span class="strong" style="color: var(--c-text)">{{ session.role === 'teacher' ? '计科2301' : '计科2301 · 张三' }}</span>
          <span class="chip" :class="session.role === 'teacher' ? 'blue' : 'green'">
            {{ session.role === 'teacher' ? '教师' : '学生' }}
          </span>
        </div>
        <div>2026-2027 学年 · 第一学期</div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar" :class="{ scrolled }">
        <button class="icon-btn menu-btn" aria-label="菜单" @click="mobileOpen = !mobileOpen">
          <AppIcon name="list" :size="22" />
        </button>
        <div class="topbar-title">{{ route.meta.title || '我的开课' }}</div>
        <div class="topbar-spacer" />

        <label class="search">
          <AppIcon name="search" :size="17" />
          <input v-model="keyword" placeholder="搜索课程、学生、题目…" />
        </label>

        <button class="icon-btn" aria-label="帮助" title="帮助">
          <AppIcon name="help" :size="20" />
        </button>

        <div class="topbar-divider" />

        <div class="user">
          <div class="avatar">{{ session.name.slice(0, 1) }}</div>
          <div class="user-meta">
            <div class="user-name">{{ session.name }}</div>
            <div class="user-role">{{ session.role === 'teacher' ? '指导教师' : '2023010101' }}</div>
          </div>
        </div>

        <button class="icon-btn" aria-label="退出登录" title="退出登录" @click="logout">
          <AppIcon name="logout" :size="20" />
        </button>
      </header>

      <main class="content">
        <div class="view-host">
          <RouterView v-slot="{ Component }">
            <Transition name="page" mode="out-in">
              <component :is="Component" :key="route.fullPath" />
            </Transition>
          </RouterView>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from './AppIcon.vue'
import { session, signOut } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const mobileOpen = ref(false)
const keyword = ref('')
const scrolled = ref(false)

function onScroll() {
  scrolled.value = window.scrollY > 6
}

onMounted(() => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
})

onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))

const OFFERING = '1839201930199990001'

const studentNav = [
  {
    title: '学习',
    items: [
      { to: '/student', icon: 'home', label: '学习台', exact: true },
      { to: '/student/courses', icon: 'book', label: '选择课程' },
      { to: '/student/selections', icon: 'list', label: '我的选题', badge: '2' }
    ]
  },
  {
    title: '答辩与成绩',
    items: [
      { to: '/student/defense', icon: 'mic', label: '在线答辩', badge: '1', exact: true },
      { to: '/student/grades', icon: 'award', label: '我的成绩', exact: true }
    ]
  }
]

const teacherNav = [
  {
    title: '教学',
    items: [
      { to: '/teacher/offerings', icon: 'book', label: '我的开课', exact: true, also: ['/teacher/offerings/new'] }
    ]
  },
  {
    title: '当前开课 · 计科2301',
    items: [
      { to: `/teacher/offerings/${OFFERING}`, icon: 'layers', label: '开课概览', exact: true },
      { to: `/teacher/offerings/${OFFERING}/students`, icon: 'users', label: '学生名单', badge: '42', exact: true },
      { to: `/teacher/offerings/${OFFERING}/topics`, icon: 'file', label: '题目管理', badge: '8', exact: true },
      { to: `/teacher/offerings/${OFFERING}/defenses`, icon: 'mic', label: '答辩复核', badge: '12', exact: true, also: ['/teacher/defenses'] },
      { to: `/teacher/offerings/${OFFERING}/grades`, icon: 'award', label: '成绩管理', exact: true }
    ]
  }
]

const nav = computed(() => (session.role === 'teacher' ? teacherNav : studentNav))

function isActive(item) {
  if (route.path === item.to) return true
  if (item.also?.some((prefix) => route.path.startsWith(prefix))) return true
  if (item.exact) return false
  return route.path.startsWith(item.to + '/')
}

function go(navigate) {
  navigate()
  mobileOpen.value = false
}

function logout() {
  signOut()
  router.push('/login')
}
</script>

<style scoped>
.scrim {
  position: fixed;
  inset: 0;
  background: rgba(32, 33, 36, .4);
  z-index: 35;
  animation: fade .18s var(--ease);
}

.nav-item.active {
  background: var(--c-blue-50);
  color: var(--c-blue-700);
  font-weight: 600;
}
.nav-item.active .nav-badge { background: var(--c-surface); }

.topbar { transition: box-shadow .22s var(--ease); }
.topbar.scrolled { box-shadow: 0 1px 2px rgba(74, 62, 45, .08), 0 4px 14px rgba(74, 62, 45, .06); }

.topbar-divider {
  width: 1px;
  height: 24px;
  background: var(--c-line-2);
  margin: 0 2px;
}

.user { display: flex; align-items: center; gap: 10px; padding: 4px 6px; border-radius: 999px; }
.user-meta { line-height: 1.25; }
.user-name { font-size: 14.5px; font-weight: 500; }
.user-role { font-size: 12.5px; color: var(--c-text-3); }

@media (max-width: 720px) {
  .user-meta { display: none; }
  .topbar-divider { display: none; }
}
</style>
