<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from '../components/Icon.vue'
import EmptyState from '../components/EmptyState.vue'
import PageHeader from '../components/layout/PageHeader.vue'
import StepFlow from '../components/layout/StepFlow.vue'
import StudentSummary from '../components/summary/StudentSummary.vue'
import { STUDENT_FLOW } from '../config/flow'
import { api, reasonText } from '../api/endpoints'
import { toastError } from '../stores/toast'

const loading = ref(true)
const rows = ref([])
const keyword = ref('')

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter((r) => `${r.courseName} ${r.teacherName} ${r.className}`.toLowerCase().includes(q))
})

function fmt(value) {
  if (!value) return '—'
  return new Date(value).toLocaleDateString('zh-CN')
}

function windowState(o) {
  const now = Date.now()
  if (o.selectStart && new Date(o.selectStart).getTime() > now) return { tone: 'info', text: '尚未开始' }
  if (o.selectEnd && new Date(o.selectEnd).getTime() < now) return { tone: 'warn', text: '已结束' }
  return { tone: 'ok', text: '进行中' }
}

function daysLeft(value) {
  if (!value) return null
  return Math.ceil((new Date(value).getTime() - Date.now()) / 86400000)
}

async function load() {
  loading.value = true
  try {
    const data = await api.studentOfferings({ page: 1, size: 50 })
    rows.value = data?.list || []
  } catch (err) {
    toastError(err, '加载课程失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader
      title="我的课程"
      description="只展示教师已发布的教学班。选题窗口内可以挑题，名额由数据库原子预占。"
    >
      <template #steps>
        <StepFlow :steps="STUDENT_FLOW" current="course" dense />
      </template>
      <template #actions>
        <input v-model="keyword" class="input" style="width: 220px" placeholder="搜索课程 / 教师 / 班级" />
        <RouterLink to="/selections" class="btn btn--ghost"><Icon name="target" :size="15" />我的选题</RouterLink>
      </template>
    </PageHeader>

    <StudentSummary />

    <div v-if="loading" class="grid grid--2">
      <div v-for="i in 4" :key="i" class="skeleton" style="height: 180px" />
    </div>

    <EmptyState v-else-if="!filtered.length" icon="book" title="暂无课程" hint="等教师发布教学班后即可在这里看到" />

    <div v-else class="grid grid--2">
      <article v-for="o in filtered" :key="o.offeringId" class="card" style="overflow: hidden">
        <div style="height: 4px; background: linear-gradient(90deg, var(--brand), var(--accent))" />
        <div class="card__body">
          <div class="row" style="align-items: flex-start">
            <div style="min-width: 0">
              <h3 style="font-size: 15px; font-weight: 650" class="truncate">{{ o.courseName }}</h3>
              <div class="small dim" style="margin-top: 2px">
                {{ o.teacherName || '任课教师' }} · {{ o.semester }} · {{ o.className }}
              </div>
            </div>
            <span class="pill spacer" :class="`pill--${windowState(o).tone}`">{{ windowState(o).text }}</span>
          </div>

          <div class="grid grid--3" style="gap: 10px; margin-top: 16px">
            <div>
              <div class="small dim">已选题目</div>
              <div class="num" style="font-size: 18px; font-weight: 650">
                {{ o.mySelectionCount }}<small class="dim" style="font-size: 12px"> / {{ o.topicSelectLimit ?? '不限' }}</small>
              </div>
            </div>
            <div>
              <div class="small dim">选题截止</div>
              <div class="small mono" style="margin-top: 4px">{{ fmt(o.selectEnd) }}</div>
            </div>
            <div>
              <div class="small dim">提交截止</div>
              <div class="small mono" style="margin-top: 4px">{{ fmt(o.submitDeadline) }}</div>
              <div v-if="daysLeft(o.submitDeadline) !== null" class="small" :class="daysLeft(o.submitDeadline) <= 3 ? 'pill pill--warn' : 'dim'" style="margin-top: 4px">
                还有 {{ daysLeft(o.submitDeadline) }} 天
              </div>
            </div>
          </div>

          <div v-if="!o.canSelect && o.cannotSelectReason" class="alert alert--warn" style="margin-top: 14px">
            <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ reasonText(o.cannotSelectReason) }}
          </div>

          <div class="row" style="margin-top: 16px">
            <RouterLink
              :to="{ name: 'topic-select', params: { offeringId: o.offeringId } }"
              class="btn"
              :class="o.canSelect ? 'btn--primary' : 'btn--ghost'"
            >
              <Icon name="target" :size="15" />{{ o.canSelect ? '去选题' : '查看题目' }}
            </RouterLink>
            <RouterLink to="/selections" class="btn btn--ghost">我的选题</RouterLink>
          </div>
        </div>
      </article>
    </div>
  </div>
</template>
