<template>
  <div>
    <div class="crumb">
      <RouterLink to="/teacher/offerings">我的开课</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <RouterLink :to="`/teacher/offerings/${offering.id}`">面向对象程序设计课程设计(Java)</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <span>答辩复核</span>
    </div>

    <div class="page-head">
      <div>
        <div class="page-title">答辩复核</div>
        <div class="page-sub">共 42 人 · 已完成 30 人 · 待复核 12 条</div>
      </div>
      <div class="page-actions">
        <button class="btn btn-outline">
          <AppIcon name="download" :size="18" />
          导出复核记录
        </button>
      </div>
    </div>


    <div class="grid g3">
      <div class="card card-pad mini">
        <span class="tiny dim">已完成答辩</span>
        <b>30</b>
        <div class="bar thin mt-8"><span style="width: 71%" /></div>
      </div>
      <div class="card card-pad mini">
        <span class="tiny dim">待复核</span>
        <b>12</b>
        <div class="bar thin mt-8"><span style="width: 40%; background: #f9ab00" /></div>
      </div>
      <div class="card card-pad mini">
        <span class="tiny dim">异常标记</span>
        <b>2</b>
        <div class="row gap-6 mt-8 tiny">
          <span class="chip red">疑似照读 1</span>
          <span class="chip amber">答非所问 1</span>
        </div>
      </div>
    </div>

    <div class="card mt-16">
      <div class="toolbar">
        <label class="search" style="width: 280px">
          <AppIcon name="search" :size="17" />
          <input v-model="keyword" placeholder="搜索学生姓名或学号" />
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
              <th style="min-width: 180px">学生</th>
              <th style="min-width: 220px">选题</th>
              <th>状态</th>
              <th>时长</th>
              <th>得分</th>
              <th>异常</th>
              <th>复核</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in visible" :key="d.id">
              <td>
                <div class="row gap-10">
                  <span class="avatar">{{ d.studentName.slice(0, 1) }}</span>
                  <div>
                    <div class="strong">{{ d.studentName }}</div>
                    <div class="tiny dim mono mt-2">{{ d.studentNo }}</div>
                  </div>
                </div>
              </td>
              <td>
                <div v-for="t in d.topics" :key="t" class="small ellipsis">{{ t }}</div>
              </td>
              <td><StatusChip :status="d.status" /></td>
              <td class="num">{{ d.duration }}</td>
              <td class="num strong">{{ d.score ?? '—' }}</td>
              <td>
                <span v-if="d.flags" class="chip red"><AppIcon name="alert" :size="12" /> {{ d.flags }}</span>
                <span v-else class="dim small">—</span>
              </td>
              <td>
                <span class="chip" :class="d.reviewed ? 'green' : 'amber'">{{ d.reviewed ? '已复核' : '待复核' }}</span>
              </td>
              <td class="right">
                <RouterLink class="btn btn-sm btn-tonal" :to="`/teacher/defenses/${d.id}`">查看</RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import StatusChip from '@/components/StatusChip.vue'
import { defenses, offering } from '@/data/mock'

const keyword = ref('')
const status = ref('all')

const filters = [
  { value: 'all', label: '全部' },
  { value: 'EVALUATED', label: '待复核' },
  { value: 'READY', label: '未开始' }
]

const visible = computed(() =>
  defenses.filter((d) => {
    const kw = keyword.value.trim()
    const matchKw = !kw || d.studentName.includes(kw) || d.studentNo.includes(kw)
    const matchStatus = status.value === 'all' || d.status === status.value
    return matchKw && matchStatus
  })
)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 16px; flex-wrap: wrap; }
.mini { display: flex; flex-direction: column; gap: 6px; }
.mini b { font-size: 29px; font-weight: 500; letter-spacing: -1px; }
</style>
