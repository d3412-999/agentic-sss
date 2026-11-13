<script setup>
/**
 * 教师端紧凑摘要条：接替原「概览」首页的 KPI + 待办 + 通知，
 * 压成一行数字 + 可折叠待办，放在教学班列表顶部，不占用首屏主体。
 *
 * 数据源与改造前的教师首页完全一致，只是不再渲染成大卡片。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from '../Icon.vue'
import { api } from '../../api/endpoints'

const loading = ref(true)
const offerings = ref([])
const dashboard = ref(null)
const unread = ref(0)
const open = ref(false)

const focus = computed(
  () => offerings.value.find((o) => o.status === 'PUBLISHED') || offerings.value[0] || null,
)

const stats = computed(() => [
  { key: 'offerings', label: '教学班', value: offerings.value.length },
  { key: 'students', label: '在册学生', value: dashboard.value?.studentCount ?? 0 },
  { key: 'selections', label: '选题', value: dashboard.value?.selectionCount ?? 0 },
  {
    key: 'defense',
    label: '待答辩',
    value: dashboard.value?.defensePendingCount ?? 0,
    alert: (dashboard.value?.defensePendingCount ?? 0) > 0,
  },
])

const TONE_RANK = { danger: 0, warn: 1, info: 2, ok: 3 }

const todos = computed(() => {
  const list = []
  const drafts = offerings.value.filter((o) => o.status === 'DRAFT')
  if (drafts.length) {
    list.push({ title: `${drafts.length} 个教学班待发布`, hint: '补齐名单与题目后发布', to: '/offerings', tone: 'warn' })
  }
  if (offerings.value.length && (dashboard.value?.studentCount ?? 0) === 0) {
    list.push({ title: '教学班还没有学生', hint: '进入列表批量添加', to: '/offerings', tone: 'info' })
  }
  if ((dashboard.value?.defensePendingCount ?? 0) > 0) {
    list.push({
      title: `${dashboard.value.defensePendingCount} 人待答辩`,
      hint: '进入课程详情「答辩」发起',
      to: focus.value ? `/offerings/${focus.value.id}?section=defense` : '/offerings',
      tone: 'info',
    })
  }
  if (!list.length) list.push({ title: '暂无待办', hint: '可继续维护教学班', to: '/offerings', tone: 'ok' })
  return [...list].sort((a, b) => (TONE_RANK[a.tone] ?? 9) - (TONE_RANK[b.tone] ?? 9)).slice(0, 4)
})

const pendingCount = computed(() => todos.value.filter((t) => t.tone !== 'ok').length)

onMounted(async () => {
  try {
    const page = await api.teacherOfferings({ page: 1, size: 6 })
    offerings.value = page?.list || []
    offersSecond()
  } catch {
    // 摘要失败不影响列表本身
  } finally {
    loading.value = false
  }
  try {
    const n = await api.notifications({ page: 1, size: 1 })
    unread.value = n?.unreadCount || 0
  } catch {
    unread.value = 0
  }
})

async function offersSecond() {
  if (!focus.value) return
  try {
    dashboard.value = await api.offeringDashboard(focus.value.id)
  } catch {
    dashboard.value = null
  }
}
</script>

<template>
  <div v-if="!loading" class="summary-bar">
    <span v-for="s in stats" :key="s.key" class="summary-bar__item">
      {{ s.label }}
      <b class="summary-bar__num" :class="{ 'is-alert': s.alert }">{{ s.value }}</b>
    </span>

    <span class="summary-bar__sep" />

    <button type="button" class="summary-bar__todo" :aria-expanded="open" @click="open = !open">
      <Icon name="flag" :size="13" />
      {{ pendingCount ? `${pendingCount} 条待办` : '暂无待办' }}
      <Icon :name="open ? 'chevron-up' : 'chevron-down'" :size="12" />
    </button>

    <RouterLink to="/notifications" class="summary-bar__item summary-bar__link">
      未读通知
      <b class="summary-bar__num">{{ unread }}</b>
    </RouterLink>

    <div v-if="open" class="summary-bar__list">
      <RouterLink v-for="t in todos" :key="`${t.title}-${t.to}`" :to="t.to" class="summary-bar__row">
        <span class="pill" :class="`pill--${t.tone}`">{{ t.tone === 'ok' ? '完成' : '待处理' }}</span>
        <span class="truncate">{{ t.title }}</span>
        <span class="summary-bar__row-hint truncate dim">{{ t.hint }}</span>
        <Icon name="chevron-right" :size="13" class="dim" />
      </RouterLink>
    </div>
  </div>
</template>

<!-- 样式在 styles/main.css 的 .summary-bar 段，与学生端摘要条共用同一套 -->
