<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from './api/endpoints'
import { session, clearSession, displayName, currentRole } from './stores/session'

const route = useRoute()
const router = useRouter()

const isPublicPage = computed(() => route.meta.public === true)
const roleText = computed(
  () => ({ STUDENT: '学生', TEACHER: '教师', ADMIN: '管理员' })[currentRole.value] || currentRole.value,
)

async function logout() {
  try {
    await api.logout()
  } catch {
    // 后端不可达时也要允许本地退出
  } finally {
    clearSession()
    router.push({ name: 'login' })
  }
}
</script>

<template>
  <div class="app-shell">
    <header v-if="!isPublicPage" class="topbar">
      <div class="topbar__brand">
        <span class="brand-mark">S</span>
        <span>课程设计智能答辩与评分系统</span>
      </div>

      <nav class="topbar__nav">
        <RouterLink to="/">概览</RouterLink>
        <RouterLink to="/topics">开课与题目</RouterLink>
        <RouterLink to="/notifications">站内通知</RouterLink>
        <a href="/swagger-ui.html" target="_blank" rel="noopener">API 文档</a>
      </nav>

      <div class="topbar__user">
        <span class="user-name">{{ displayName }}</span>
        <span v-if="roleText" class="role-badge">{{ roleText }}</span>
        <button class="btn btn--ghost" type="button" @click="logout">退出</button>
      </div>
    </header>

    <main :class="isPublicPage ? 'page page--public' : 'page'">
      <RouterView />
    </main>

    <footer v-if="!isPublicPage" class="footer">
      agentic-sss · 演示环境 ·
      <a href="/api/v1/health" target="_blank" rel="noopener">后端健康检查</a>
    </footer>
  </div>
</template>
