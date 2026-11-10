<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from './Icon.vue'
import CommandPalette from './CommandPalette.vue'
import ToastHost from './ToastHost.vue'
import ConfirmHost from './ConfirmHost.vue'
import { api, ROLE_LABEL } from '../api/endpoints'
import { session, currentRole, clearSession, setMe } from '../stores/session'
import { nextThemeMode, theme, themeName, toggleTheme } from '../stores/theme'
import { activeCount, resumeTracking } from '../stores/tasks'
import { toast } from '../stores/toast'

const route = useRoute()
const router = useRouter()

const navOpen = ref(false)
const paletteOpen = ref(false)
const userMenu = ref(false)
const unread = ref(0)
let timer = null

const NAV = [
  { group: '工作台', items: [
    { to: '/', label: '概览', icon: 'home', roles: ['TEACHER', 'STUDENT', 'ADMIN'] },
    { to: '/offerings', label: '教学班', icon: 'book', roles: ['TEACHER'] },
    { to: '/courses', label: '我的课程', icon: 'book', roles: ['STUDENT'] },
    { to: '/selections', label: '我的选题', icon: 'target', roles: ['STUDENT'] },
    { to: '/grades', label: '成绩中心', icon: 'chart', roles: ['TEACHER', 'STUDENT'] },
  ] },
  { group: '协作', items: [
    { to: '/tasks', label: '任务中心', icon: 'clock', roles: ['TEACHER', 'STUDENT', 'ADMIN'], badge: 'tasks' },
    { to: '/notifications', label: '站内通知', icon: 'bell', roles: ['TEACHER', 'STUDENT', 'ADMIN'], badge: 'unread' },
  ] },
  { group: '个人', items: [
    { to: '/settings', label: '设置', icon: 'gear', roles: ['TEACHER', 'STUDENT', 'ADMIN'] },
  ] },
]

const role = computed(() => currentRole.value)
const roleText = computed(() => ROLE_LABEL[role.value] || role.value || '—')
const user = computed(() => session.user || session.me || {})
const displayName = computed(() => user.value.name || user.value.username || '未登录')
const initial = computed(() => (displayName.value || 'S').slice(0, 1).toUpperCase())
const title = computed(() => route.meta.title || '概览')

const navGroups = computed(() =>
  NAV.map((g) => ({ ...g, items: g.items.filter((i) => i.roles.includes(role.value)) })).filter(
    (g) => g.items.length,
  ),
)

function isActive(to) {
  if (to === '/') return route.path === '/'
  return route.path === to || route.path.startsWith(`${to}/`)
}

async function loadUnread() {
  if (!session.accessToken) {
    unread.value = 0
    return
  }
  try {
    const data = await api.notifications({ page: 1, size: 1 })
    unread.value = data?.unreadCount || 0
  } catch {
    unread.value = 0
  }
}

async function bootstrapSession() {
  if (!session.accessToken) return
  if (!session.me) {
    try {
      setMe(await api.me())
    } catch {
      // 401 已由 http.js 统一处理
    }
  }
}

async function logout() {
  try {
    await api.logout()
  } catch {
    // 后端不可达时也允许本地退出
  }
  clearSession()
  userMenu.value = false
  router.push({ name: 'login' })
}

function onGlobalKey(e) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    paletteOpen.value = true
  }
}

function onDocClick(e) {
  if (userMenu.value && !e.target.closest('.menu-wrap')) userMenu.value = false
}

watch(() => route.path, () => {
  navOpen.value = false
  userMenu.value = false
})

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKey)
  document.addEventListener('click', onDocClick)
  await bootstrapSession()
  resumeTracking()
  loadUnread()
  timer = setInterval(loadUnread, 60000)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKey)
  document.removeEventListener('click', onDocClick)
  if (timer) clearInterval(timer)
})

defineExpose({ refreshUnread: loadUnread })
</script>

<template>
  <div class="shell" :class="{ 'is-nav-open': navOpen }">
    <aside class="sidebar">
      <RouterLink to="/" class="sidebar__brand">
        <span class="brand-mark">S</span>
        <span class="sidebar__brand-text">
          <strong>智能答辩与评分</strong>
          <span>agentic-sss</span>
        </span>
      </RouterLink>

      <nav v-for="group in navGroups" :key="group.group" class="nav-group">
        <div class="nav-group__label">{{ group.group }}</div>
        <RouterLink
          v-for="item in group.items"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          :class="{ 'is-active': isActive(item.to) }"
        >
          <Icon :name="item.icon" :size="16" />
          <span>{{ item.label }}</span>
          <span v-if="item.badge === 'unread' && unread" class="nav-item__badge">{{ unread > 99 ? '99+' : unread }}</span>
          <span v-else-if="item.badge === 'tasks' && activeCount" class="nav-item__badge">{{ activeCount }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar__footer">
        Java 17 · Spring Boot 3<br />
        <a href="/swagger-ui.html" target="_blank" rel="noopener">Swagger API 文档 ↗</a>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <button class="btn btn--icon topbar__burger" @click="navOpen = !navOpen">
          <Icon name="menu" :size="18" />
        </button>

        <div style="min-width: 0">
          <div class="topbar__title">{{ title }}</div>
          <div class="topbar__crumbs">
            <span>{{ roleText }}工作台</span>
            <Icon name="chevron-right" :size="12" />
            <span>{{ title }}</span>
          </div>
        </div>

        <div class="topbar__actions">
          <button class="btn btn--ghost btn--sm" @click="paletteOpen = true">
            <Icon name="search" :size="14" />
            <span class="topbar__search-text dim" style="font-size: 12.5px">搜索</span>
            <span class="kbd topbar__search-text">Ctrl K</span>
          </button>

          <button class="btn btn--icon" :title="`切换到${themeName(nextThemeMode(theme.mode))}主题`" @click="toggleTheme">
            <Icon :name="theme.mode === 'dark' ? 'sun' : 'moon'" :size="17" />
          </button>

          <RouterLink to="/notifications" class="btn btn--icon" style="position: relative" title="站内通知">
            <Icon name="bell" :size="17" />
            <span v-if="unread" class="badge" style="position: absolute; top: 2px; right: 0">{{ unread > 9 ? '9+' : unread }}</span>
          </RouterLink>

          <div class="menu-wrap">
            <button class="user-chip" @click="userMenu = !userMenu">
              <span class="avatar">{{ initial }}</span>
              <span class="user-chip__meta">
                <strong>{{ displayName }}</strong>
                <span>{{ roleText }}</span>
              </span>
              <Icon name="chevron-down" :size="13" />
            </button>
            <div v-if="userMenu" class="menu">
              <div style="padding: 8px 10px 10px">
                <div style="font-weight: 600">{{ displayName }}</div>
                <div class="small dim mono">{{ user.username || user.email || '—' }} · {{ roleText }}</div>
              </div>
              <div class="menu__sep" />
              <RouterLink to="/settings" class="menu__item"><Icon name="gear" :size="15" />账号设置</RouterLink>
              <RouterLink to="/tasks" class="menu__item">
                <Icon name="clock" :size="15" />任务中心
                <span v-if="activeCount" class="badge" style="margin-left: auto">{{ activeCount }}</span>
              </RouterLink>
              <a class="menu__item" href="/swagger-ui.html" target="_blank" rel="noopener">
                <Icon name="external" :size="15" />API 文档
              </a>
              <div class="menu__sep" />
              <button class="menu__item menu__item--danger" @click="logout">
                <Icon name="logout" :size="15" />退出登录
              </button>
            </div>
          </div>
        </div>
      </header>

      <main class="content">
        <RouterView v-slot="{ Component }">
          <component :is="Component" @refresh-unread="loadUnread" />
        </RouterView>
      </main>
    </div>

    <CommandPalette v-if="paletteOpen" @close="paletteOpen = false" />
    <ToastHost />
    <ConfirmHost />
  </div>
</template>

<style scoped>
/* 移动端顶栏细节由 styles/main.css 的媒体查询统一处理 */
</style>
