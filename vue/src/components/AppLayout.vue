<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from './Icon.vue'
import CommandPalette from './CommandPalette.vue'
import ToastHost from './ToastHost.vue'
import ConfirmHost from './ConfirmHost.vue'
import ForcePasswordChange from './ForcePasswordChange.vue'
import { api, ROLE_LABEL } from '../api/endpoints'
import { navGroupsForRole, activeGroupFor } from '../config/nav'
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

const role = computed(() => currentRole.value)
// 管理员平台只有「教师账号」一个模块，通知 / 任务 / 设置等入口一律隐藏
const isAdmin = computed(() => role.value === 'ADMIN')
const roleText = computed(() => ROLE_LABEL[role.value] || role.value || '—')
const user = computed(() => session.user || session.me || {})
const displayName = computed(() => user.value.name || user.value.username || '未登录')
const initial = computed(() => (displayName.value || 'S').slice(0, 1).toUpperCase())
const title = computed(() => route.meta.title || '概览')
/** 沉浸页（答辩）隐藏一级栏，把宽度全部让给答题区；退出入口在页头面包屑。 */
const immersive = computed(() => route.meta.immersive === true)
/** 品牌位与默认落地页：教师进教学班、学生进我的课程，管理员进教师账号。 */
const defaultHome = computed(() => {
  if (isAdmin.value) return '/admin/teachers'
  return role.value === 'TEACHER' ? '/offerings' : '/courses'
})

const navGroups = computed(() => navGroupsForRole(role.value))
const activeGroup = computed(() => activeGroupFor(role.value, route.path))

/**
 * 一级栏只做展示层偏好：折叠状态与集块展开状态都存 localStorage，
 * 不参与任何路由或业务判断，清掉缓存也只是回到默认展开。
 */
const COLLAPSE_KEY = 'sss.nav.collapsed'
const OPEN_KEY = 'sss.nav.closed-groups'

const collapsed = ref(false)
const closedGroups = ref({})

function readPrefs() {
  try {
    collapsed.value = localStorage.getItem(COLLAPSE_KEY) === '1'
    closedGroups.value = JSON.parse(localStorage.getItem(OPEN_KEY) || '{}') || {}
  } catch {
    collapsed.value = false
    closedGroups.value = {}
  }
}

function toggleCollapse() {
  collapsed.value = !collapsed.value
  try {
    localStorage.setItem(COLLAPSE_KEY, collapsed.value ? '1' : '0')
  } catch {
    // 隐私模式下写不进去，不影响本次会话
  }
}

/** 默认展开；只有用户显式收起过的集块才会被判为关闭。 */
function isGroupOpen(group) {
  return closedGroups.value[group.group] !== true
}

function toggleGroup(group) {
  const next = { ...closedGroups.value }
  if (isGroupOpen(group)) next[group.group] = true
  else delete next[group.group]
  closedGroups.value = next
  try {
    localStorage.setItem(OPEN_KEY, JSON.stringify(next))
  } catch {
    // 同上
  }
}

function isActive(to) {
  if (to === '/') return route.path === '/'
  return route.path === to || route.path.startsWith(`${to}/`)
}

async function loadUnread() {
  if (!session.accessToken || isAdmin.value) {
    // 管理员没有通知模块，后端也会拒绝该接口
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
  readPrefs()
  window.addEventListener('keydown', onGlobalKey)
  document.addEventListener('click', onDocClick)
  await bootstrapSession()
  if (isAdmin.value) {
    unread.value = 0
  } else {
    resumeTracking()
    loadUnread()
  }
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
  <div class="shell" :class="{ 'is-nav-open': navOpen, 'is-collapsed': collapsed, 'is-immersive': immersive }">
    <aside class="sidebar">
      <RouterLink :to="defaultHome" class="sidebar__brand" title="智能答辩与评分系统">
        <span class="brand-mark">S</span>
        <span class="sidebar__brand-text">
          <strong>智能答辩与评分</strong>
          <span>agentic-sss</span>
        </span>
      </RouterLink>

      <nav v-for="group in navGroups" :key="group.group" class="nav-group" :class="{ 'is-open': isGroupOpen(group) }">
        <div class="nav-group__row">
          <RouterLink
            :to="group.children[0].to"
            class="nav-group__head"
            :class="{ 'is-in-path': activeGroup && activeGroup.group === group.group }"
            :title="group.group"
          >
            <Icon :name="group.icon" :size="18" />
            <span class="nav-group__head-label">{{ group.group }}</span>
          </RouterLink>
          <button
            type="button"
            class="nav-group__toggle"
            :title="isGroupOpen(group) ? '收起' : '展开'"
            :aria-expanded="isGroupOpen(group)"
            @click="toggleGroup(group)"
          >
            <Icon name="chevron-right" :size="13" />
          </button>
        </div>

        <div v-show="isGroupOpen(group)" class="nav-group__items">
          <RouterLink
            v-for="item in group.children"
            :key="item.to"
            :to="item.to"
            class="nav-item"
            :class="{ 'is-active': isActive(item.to) }"
            :title="item.label"
          >
            <span>{{ item.label }}</span>
            <span v-if="item.badge === 'unread' && unread" class="nav-item__badge">{{ unread > 9 ? '9+' : unread }}</span>
            <span v-else-if="item.badge === 'tasks' && activeCount" class="nav-item__badge">{{ activeCount }}</span>
          </RouterLink>
        </div>
      </nav>

      <div class="sidebar__footer">
        <a v-if="!isAdmin" class="sidebar__footer-text" href="/swagger-ui.html" target="_blank" rel="noopener">API 文档</a>
        <span v-else class="sidebar__footer-text">v1</span>
        <button
          type="button"
          class="nav-group__toggle"
          :title="collapsed ? '展开侧栏' : '收起侧栏'"
          :aria-expanded="!collapsed"
          @click="toggleCollapse"
        >
          <Icon :name="collapsed ? 'chevron-right' : 'chevron-left'" :size="13" />
        </button>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <button class="btn btn--icon topbar__burger" @click="navOpen = !navOpen">
          <Icon name="menu" :size="18" />
        </button>

        <div class="topbar__crumbs" style="min-width: 0">
          <strong style="color: var(--text); font-weight: 620">{{ roleText }}端</strong>
          <Icon name="chevron-right" :size="12" />
          <span class="truncate">{{ title }}</span>
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

          <RouterLink v-if="!isAdmin" to="/notifications" class="btn btn--icon" style="position: relative" title="站内通知">
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
              <template v-if="!isAdmin">
                <RouterLink to="/settings" class="menu__item"><Icon name="gear" :size="15" />账号设置</RouterLink>
                <RouterLink to="/tasks" class="menu__item">
                  <Icon name="clock" :size="15" />任务中心
                  <span v-if="activeCount" class="badge" style="margin-left: auto">{{ activeCount }}</span>
                </RouterLink>
                <a class="menu__item" href="/swagger-ui.html" target="_blank" rel="noopener">
                  <Icon name="external" :size="15" />API 文档
                </a>
                <div class="menu__sep" />
              </template>
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
    <ForcePasswordChange />
  </div>
</template>

<style scoped>
/* 移动端顶栏细节由 styles/main.css 的媒体查询统一处理 */
</style>
