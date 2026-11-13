<script setup>
/**
 * 学生端紧凑摘要条：接替原「概览」首页的 KPI + 待办 + 截止提醒，
 * 压成一行数字 + 一句「下一步」+ 可折叠待办，放在我的课程列表顶部。
 *
 * 数据源与改造前的学生首页完全一致，不再渲染成大卡片。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from '../Icon.vue'
import { api } from '../../api/endpoints'

const loading = ref(true)
const offerings = ref([])
const selections = ref([])
const grades = ref([])
const unread = ref(0)
const open = ref(false)

const submitted = computed(
  () => selections.value.filter((s) => s.progress?.sourceUploaded && s.progress?.reportUploaded).length,
)

const stats = computed(() => [
  { key: 'courses', label: '可选课程', value: offerings.value.length },
  { key: 'selected', label: '已选题目', value: selections.value.length },
  { key: 'ready', label: '材料齐全', value: submitted.value },
  { key: 'graded', label: '已出成绩', value: grades.value.length },
])

/** 首屏「下一步」：与改造前的学生首页同一套推导，只换成一句话。 */
const nextAction = computed(() => {
  if (!selections.value.length) {
    return {
      title: offerings.value.length ? '先挑一个题目' : '暂无可选课程',
      to: '/courses',
    }
  }
  const pending = selections.value.find(
    (s) => !s.progress?.sourceUploaded || !s.progress?.reportUploaded,
  )
  if (pending) {
    const which = !pending.progress?.sourceUploaded ? '源码' : '报告'
    return { title: `待上传${which}：${pending.topic?.title || '题目'}`, to: '/selections' }
  }
  if (!grades.value.length) return { title: '材料已齐全，等待评审与答辩', to: '/selections' }
  return { title: '成绩已发布，可查看分项明细', to: '/grades' }
})

const TONE_RANK = { danger: 0, warn: 1, info: 2, ok: 3 }

const todos = computed(() => {
  const list = []
  selections.value.forEach((s) => {
    const title = s.topic?.title || '题目'
    if (!s.progress?.sourceUploaded) {
      list.push({ title: `待上传源码：${title}`, hint: '进入我的选题上传', to: '/selections', tone: 'warn' })
    } else if (!s.progress?.reportUploaded) {
      list.push({ title: `待上传报告：${title}`, hint: '进入我的选题上传', to: '/selections', tone: 'warn' })
    }
  })
  if (!selections.value.length) {
    list.push({ title: '还没有选题', hint: '先到课程里挑选题目', to: '/courses', tone: 'info' })
  }
  if (!list.length) list.push({ title: '材料已齐全', hint: '等待评审与答辩安排', to: '/selections', tone: 'ok' })
  return [...list].sort((a, b) => (TONE_RANK[a.tone] ?? 9) - (TONE_RANK[b.tone] ?? 9)).slice(0, 4)
})

const pendingCount = computed(() => todos.value.filter((t) => t.tone !== 'ok').length)

onMounted(async () => {
  try {
    const [page, sel] = await Promise.all([
      api.studentOfferings({ page: 1, size: 8 }),
      api.selections({ page: 1, size: 8 }),
    ])
    offerings.value = page?.list || []
    selections.value = sel?.list || []
  } catch {
    // 摘要失败不影响列表本身
  } finally {
    loading.value = false
  }
  try {
    grades.value = (await api.studentGrades())?.list || []
  } catch {
    grades.value = []
  }
  try {
    const n = await api.notifications({ page: 1, size: 1 })
    unread.value = n?.unreadCount || 0
  } catch {
    unread.value = 0
  }
})
</script>

<template>
  <div v-if="!loading" class="summary-bar">
    <span v-for="s in stats" :key="s.key" class="summary-bar__item">
      {{ s.label }}
      <b class="summary-bar__num">{{ s.value }}</b>
    </span>

    <span class="summary-bar__sep" />

    <RouterLink :to="nextAction.to" class="summary-bar__todo">
      <Icon name="arrow-right" :size="13" />
      <span class="truncate">{{ nextAction.title }}</span>
    </RouterLink>

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
