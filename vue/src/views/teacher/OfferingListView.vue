<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-title">我的开课</div>
        <div class="page-sub">每门课程设计按学期与教学班开课，学生名单与题目都挂在开课下</div>
      </div>
      <div class="page-actions">
        <RouterLink class="btn btn-primary" to="/teacher/offerings/new">
          <AppIcon name="plus" :size="18" />
          新建开课
        </RouterLink>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <label class="search" style="width: 280px">
          <AppIcon name="search" :size="17" />
          <input v-model="keyword" placeholder="搜索课程或班级" />
        </label>
        <div class="row gap-6">
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

      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th style="min-width: 260px">开课</th>
              <th>状态</th>
              <th>学生</th>
              <th>题目</th>
              <th>选题</th>
              <th>答辩完成</th>
              <th>平均分</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in visible" :key="o.id">
              <td>
                <div class="strong">{{ o.courseName }}</div>
                <div class="tiny dim mt-4">{{ o.semester }} · {{ o.className }} · {{ o.code }}</div>
              </td>
              <td><StatusChip :status="o.status" /></td>
              <td class="num">{{ o.studentCount }}</td>
              <td class="num">{{ o.topicCount }}</td>
              <td class="num">{{ o.selectionCount }}</td>
              <td>
                <div class="row gap-8">
                  <span class="num">{{ o.defenseFinished }}/{{ o.studentCount }}</span>
                  <div class="bar thin" style="width: 56px">
                    <span :style="{ width: (o.defenseFinished / o.studentCount) * 100 + '%' }" />
                  </div>
                </div>
              </td>
              <td class="num">{{ o.avgScore ?? '—' }}</td>
              <td class="right">
                <RouterLink class="btn btn-sm btn-tonal" :to="`/teacher/offerings/${o.id}`">管理</RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="!visible.length" class="empty">
        <span class="empty-icon"><AppIcon name="book" :size="24" /></span>
        <div class="empty-title">还没有开课</div>
        <div class="small">点击右上角「新建开课」开始</div>
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
const status = ref('all')
const filters = [
  { value: 'all', label: '全部' },
  { value: 'PUBLISHED', label: '进行中' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'CLOSED', label: '已关闭' }
]

const list = [
  { ...offering, defenseFinished: 30 },
  {
    ...offering,
    id: '1839201930199990002',
    courseName: 'Java 程序设计基础课程设计',
    code: 'CS1103',
    semester: '2025-2026-2',
    className: '计科2201',
    status: 'CLOSED',
    studentCount: 38,
    topicCount: 6,
    selectionCount: 62,
    defenseFinished: 38,
    avgScore: 84.2
  },
  {
    ...offering,
    id: '1839201930199990003',
    courseName: '软件工程综合实践',
    code: 'CS4102',
    semester: '2026-2027-1',
    className: '软工2301',
    status: 'DRAFT',
    studentCount: 0,
    topicCount: 0,
    selectionCount: 0,
    defenseFinished: 0,
    avgScore: null
  }
]

const visible = computed(() =>
  list.filter((o) => {
    const kw = keyword.value.trim()
    const matchKw = !kw || o.courseName.includes(kw) || o.className.includes(kw)
    return matchKw && (status.value === 'all' || o.status === status.value)
  })
)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 16px; flex-wrap: wrap; }
</style>
