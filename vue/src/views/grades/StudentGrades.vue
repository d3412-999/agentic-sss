<script setup>
/**
 * 学生端成绩中心：只读。教师发布后可见，含答辩 / 源码 / 报告三项构成。
 */
import { onMounted, ref } from 'vue'
import Icon from '../../components/Icon.vue'
import PageHeader from '../../components/layout/PageHeader.vue'
import StepFlow from '../../components/layout/StepFlow.vue'
import EmptyState from '../../components/EmptyState.vue'
import { STUDENT_FLOW } from '../../config/flow'
import { api } from '../../api/endpoints'
import { toastError } from '../../stores/toast'

const loading = ref(true)
const grades = ref([])

function levelTone(level) {
  if (level === '优秀') return 'pill--ok'
  if (level === '良好') return 'pill--info'
  if (level === '中等') return 'pill--brand'
  if (level === '不及格') return 'pill--danger'
  return ''
}

function bar(value) {
  return Math.max(0, Math.min(100, Number(value) || 0))
}

onMounted(async () => {
  try {
    const data = await api.studentGrades()
    grades.value = data?.list || []
  } catch (err) {
    toastError(err, '加载成绩失败')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <PageHeader title="我的成绩" description="只有教师发布后才会显示成绩，包含答辩 / 源码 / 报告三项构成。">
      <template #steps>
        <StepFlow :steps="STUDENT_FLOW" current="grade" dense />
      </template>
    </PageHeader>

    <div v-if="loading" class="grid grid--2">
      <div v-for="i in 2" :key="i" class="skeleton" style="height: 240px" />
    </div>
    <EmptyState
      v-else-if="!grades.length"
      icon="chart"
      title="还没有已发布的成绩"
      hint="教师发布成绩后，这里会显示分项与综合分"
    />
    <div v-else class="grid" style="gap: 16px">
      <article v-for="g in grades" :key="g.gradeId" class="card">
        <div class="card__body">
          <div class="row" style="align-items: flex-start">
            <div style="min-width: 0">
              <h3 style="font-size: 15px; font-weight: 650">{{ g.courseName }}</h3>
              <div class="small dim" style="margin-top: 2px">
                {{ g.semester }} · 发布于
                {{ g.publishedAt ? new Date(g.publishedAt).toLocaleDateString('zh-CN') : '—' }}
              </div>
            </div>
            <div class="spacer" style="text-align: right">
              <div class="num" style="font-size: 32px; font-weight: 700; letter-spacing: -1px; line-height: 1">
                {{ g.finalScore ?? '—' }}
              </div>
              <span class="pill" :class="levelTone(g.level)" style="margin-top: 6px">{{ g.level || '—' }}</span>
            </div>
          </div>

          <div class="grid grid--3" style="gap: 14px; margin-top: 18px">
            <div v-for="item in [
              { label: '答辩', value: g.defenseScore, weight: g.weights?.defense },
              { label: '源码', value: g.codeScore, weight: g.weights?.code },
              { label: '报告', value: g.reportScore, weight: g.weights?.report },
            ]" :key="item.label">
              <div class="row small dim">
                <span>{{ item.label }}（{{ item.weight }}%）</span>
                <span class="spacer num" style="color: var(--text)">{{ item.value ?? '—' }}</span>
              </div>
              <div class="bar" style="margin-top: 6px">
                <div class="bar__fill" :style="{ width: bar(item.value) + '%' }" />
              </div>
            </div>
          </div>

          <div v-if="g.items?.length" style="margin-top: 18px">
            <div class="section-title"><Icon name="layers" :size="14" />分项明细</div>
            <div class="table-wrap" style="border: 1px solid var(--line); border-radius: 12px">
              <table class="table">
                <thead>
                  <tr>
                    <th>题目</th>
                    <th class="ta-c">源码</th>
                    <th class="ta-c">报告</th>
                    <th class="ta-c">答辩</th>
                    <th class="ta-c">小计</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(it, i) in g.items" :key="i">
                    <td class="truncate" style="max-width: 320px">{{ it.topicTitle }}</td>
                    <td class="ta-c mono">{{ it.codeScore ?? '—' }}</td>
                    <td class="ta-c mono">{{ it.reportScore ?? '—' }}</td>
                    <td class="ta-c mono">{{ it.defenseScore ?? '—' }}</td>
                    <td class="ta-c mono">{{ it.finalScore ?? '—' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div v-if="g.teacherComment" class="alert alert--info" style="margin-top: 16px">
            <Icon name="flag" :size="15" style="flex: none; margin-top: 2px" />
            <span><strong>教师评语：</strong>{{ g.teacherComment }}</span>
          </div>
        </div>
      </article>
    </div>
  </div>
</template>
