<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-title">选择课程</div>
        <div class="page-sub">从教师发布的本学期课程设计中选择题目，选定后可查看提交要求</div>
      </div>
      <div class="page-actions">
        <div class="field">
          <select v-model="semester" class="select" style="height: 40px">
            <option>2026-2027-1</option>
            <option>2025-2026-2</option>
          </select>
        </div>
      </div>
    </div>

    <div class="toolbar card">
      <label class="search" style="width: 320px">
        <AppIcon name="search" :size="17" />
        <input v-model="keyword" placeholder="搜索课程名称或教师" />
      </label>
      <div class="row gap-8">
        <button
          v-for="f in filters"
          :key="f.value"
          class="btn btn-sm"
          :class="status === f.value ? 'btn-tonal' : 'btn-text'"
          @click="status = f.value"
        >
          {{ f.label }}
        </button>
      </div>
    </div>

    <div class="grid g2 mt-16">
      <article v-for="o in visible" :key="o.id" class="card hover course">
        <div class="between">
          <div class="row gap-10">
            <span class="course-mark">{{ o.code.slice(0, 2) }}</span>
            <div>
              <div class="course-name">{{ o.courseName }}</div>
              <div class="tiny dim mt-4">{{ o.code }} · {{ o.semester }}</div>
            </div>
          </div>
          <StatusChip :status="o.status" />
        </div>

        <div class="meta-grid">
          <div class="meta">
            <span class="tiny dim">指导教师</span>
            <span>{{ o.teacherName }}</span>
          </div>
          <div class="meta">
            <span class="tiny dim">教学班</span>
            <span>{{ o.className }}</span>
          </div>
          <div class="meta">
            <span class="tiny dim">可选题目</span>
            <span>{{ o.topicCount }} 道</span>
          </div>
          <div class="meta">
            <span class="tiny dim">提交截止</span>
            <span>{{ o.submitDeadline }}</span>
          </div>
        </div>

        <div class="mt-16">
          <div class="between small">
            <span class="muted">我的选题</span>
            <span class="strong">{{ o.myCount }} / {{ o.topicLimit }}</span>
          </div>
          <div class="bar mt-8">
            <span :style="{ width: (o.myCount / o.topicLimit) * 100 + '%' }" />
          </div>
        </div>

        <div class="between mt-20">
          <div class="row gap-6 tiny dim">
            <AppIcon name="clock" :size="14" />
            选题 {{ o.selectStart }} ~ {{ o.selectEnd }}
          </div>
          <RouterLink
            class="btn btn-sm"
            :class="o.status === 'PUBLISHED' ? 'btn-primary' : 'btn-outline'"
            :to="o.status === 'PUBLISHED' ? `/student/courses/${o.id}/topics` : '/student/selections'"
          >
            {{ o.status === 'PUBLISHED' ? '进入选题' : '查看结果' }}
            <AppIcon name="chevronRight" :size="15" />
          </RouterLink>
        </div>
      </article>
    </div>

    <div v-if="!visible.length" class="card mt-16">
      <div class="empty">
        <span class="empty-icon"><AppIcon name="search" :size="24" /></span>
        <div class="empty-title">没有匹配的课程</div>
        <div class="small">换个关键词，或切换学期试试</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import StatusChip from '@/components/StatusChip.vue'
import { offering } from '@/data/mock'

const keyword = ref('')
const semester = ref('2026-2027-1')
const status = ref('all')

const filters = [
  { value: 'all', label: '全部' },
  { value: 'PUBLISHED', label: '进行中' },
  { value: 'CLOSED', label: '已结束' }
]

const list = [
  {
    ...offering,
    myCount: 2
  },
  {
    id: '1839201930199990002',
    courseName: '数据结构课程设计',
    code: 'CS2102',
    semester: '2026-2027-1',
    className: '计科2302',
    teacherName: '王敏',
    status: 'PUBLISHED',
    selectStart: '2026-09-12',
    selectEnd: '2026-09-22',
    submitDeadline: '2026-10-18',
    topicCount: 6,
    topicLimit: 2,
    myCount: 0
  },
  {
    id: '1839201930199990003',
    courseName: 'Java Web 应用开发课程设计',
    code: 'CS3105',
    semester: '2025-2026-2',
    className: '计科2201',
    teacherName: '陈立',
    status: 'CLOSED',
    selectStart: '2026-03-01',
    selectEnd: '2026-03-10',
    submitDeadline: '2026-06-10',
    topicCount: 10,
    topicLimit: 1,
    myCount: 1
  }
]

const visible = computed(() =>
  list.filter((o) => {
    const kw = keyword.value.trim()
    const matchKw = !kw || o.courseName.includes(kw) || o.teacherName.includes(kw)
    const matchStatus = status.value === 'all' || o.status === status.value
    return matchKw && matchStatus
  })
)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 16px; flex-wrap: wrap; }

.course { padding: 20px; display: flex; flex-direction: column; }

.course-mark {
  width: 40px; height: 40px; border-radius: 12px;
  display: grid; place-items: center;
  background: var(--c-blue-50);
  color: var(--c-blue-700);
  font-size: 14px; font-weight: 600;
  letter-spacing: .5px;
}

.course-name { font-size: 17px; font-weight: 600; letter-spacing: -.2px; }

.meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
  margin-top: 18px;
  padding: 14px;
  background: var(--c-grey-50);
  border-radius: 12px;
}
.meta { display: flex; flex-direction: column; gap: 3px; font-size: 14px; }
</style>
