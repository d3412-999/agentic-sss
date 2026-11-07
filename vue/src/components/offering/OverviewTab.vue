<script setup>
import { computed } from 'vue'
import Icon from '../Icon.vue'
import ProgressRing from '../ProgressRing.vue'
import EmptyState from '../EmptyState.vue'

const props = defineProps({
  data: { type: Object, default: null },
  offering: { type: Object, default: null },
  loading: { type: Boolean, default: false },
})

const kpis = computed(() => {
  const d = props.data || {}
  return [
    { label: '在册学生', value: d.studentCount ?? 0, unit: '人', icon: 'users', hint: `${d.selectedStudentCount ?? 0} 人已选题` },
    { label: '选题记录', value: d.selectionCount ?? 0, unit: '条', icon: 'target', hint: '所有学生的选题总数' },
    { label: '源码提交', value: d.sourceSubmittedCount ?? 0, unit: '份', icon: 'upload', hint: `报告 ${d.reportSubmittedCount ?? 0} 份` },
    { label: '完成答辩', value: d.defenseFinishedCount ?? 0, unit: '人', icon: 'flag', hint: `待答辩 ${d.defensePendingCount ?? 0} 人` },
  ]
})

const rate = computed(() => Math.round((props.data?.submissionRate ?? 0) * 100))
const maxDist = computed(() => Math.max(1, ...(props.data?.scoreDistribution || []).map((d) => d.count)))

/** 后端在无数据时返回 0，这里统一显示成「—」，避免误读成真实 0 分。 */
function avg(value) {
  if (value === null || value === undefined) return '—'
  return Number(value) === 0 ? '—' : value
}
</script>

<template>
  <div v-if="loading" class="grid grid--4">
    <div v-for="i in 4" :key="i" class="skeleton" style="height: 104px" />
  </div>

  <div v-else>
    <div class="grid grid--4">
      <div v-for="k in kpis" :key="k.label" class="kpi">
        <div class="kpi__label"><Icon :name="k.icon" :size="14" />{{ k.label }}</div>
        <div class="kpi__value num">{{ k.value }}<small>{{ k.unit }}</small></div>
        <div class="kpi__foot">{{ k.hint }}</div>
      </div>
    </div>

    <div class="grid grid--sidebar" style="margin-top: 16px">
      <div style="display: grid; gap: 16px">
        <section class="card card--pad">
          <div class="section-title"><Icon name="chart" :size="14" />分数分布</div>
          <div v-if="data?.scoreDistribution?.length" style="display: grid; gap: 10px">
            <div v-for="d in data.scoreDistribution" :key="d.range" class="row" style="gap: 10px">
              <span class="small dim mono" style="width: 62px">{{ d.range }}</span>
              <div class="bar" style="flex: 1; height: 9px">
                <div class="bar__fill" :style="{ width: (d.count / maxDist) * 100 + '%' }" />
              </div>
              <span class="small num" style="width: 30px; text-align: right">{{ d.count }}</span>
            </div>
          </div>
          <EmptyState v-else icon="chart" title="还没有成绩数据" hint="在「成绩」页签重算成绩后生成" />
        </section>

        <section class="card">
          <div class="card__head">
            <h3>高频源码问题</h3>
            <span class="small dim spacer">来自 AI / 静态分析</span>
          </div>
          <div v-if="data?.topIssues?.length" class="table-wrap">
            <table class="table">
              <thead>
                <tr><th>工具</th><th>规则</th><th class="ta-r">出现次数</th></tr>
              </thead>
              <tbody>
                <tr v-for="(i, idx) in data.topIssues" :key="idx">
                  <td><span class="tag">{{ i.tool }}</span></td>
                  <td class="mono small">{{ i.rule }}</td>
                  <td class="ta-r num">{{ i.count }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <EmptyState v-else icon="code" title="暂无分析明细" hint="code_issue 表在 AI 评审返回 issues 后写入" />
        </section>
      </div>

      <div style="display: grid; gap: 16px; align-content: start">
        <section class="card card--pad" style="text-align: center">
          <div class="small dim">源码提交率</div>
          <div style="display: grid; place-items: center; margin: 12px 0">
            <ProgressRing :value="rate" :size="104" :stroke="8" label="已提交" :tone="rate >= 80 ? 'ok' : rate >= 40 ? 'brand' : 'warn'" />
          </div>
          <div class="small muted">
            源码 {{ data?.sourceSubmittedCount ?? 0 }} 份 · 报告 {{ data?.reportSubmittedCount ?? 0 }} 份
          </div>
        </section>

        <section class="card card--pad">
          <div class="section-title"><Icon name="chart" :size="14" />平均分</div>
          <dl class="dl" style="grid-template-columns: 64px 1fr">
            <dt>综合</dt><dd class="mono">{{ avg(data?.averageScore?.finalScore) }}</dd>
            <dt>答辩</dt><dd class="mono">{{ avg(data?.averageScore?.defense) }}</dd>
            <dt>源码</dt><dd class="mono">{{ avg(data?.averageScore?.code) }}</dd>
            <dt>报告</dt><dd class="mono">{{ avg(data?.averageScore?.report) }}</dd>
          </dl>
        </section>

        <section v-if="offering?.timeline" class="card card--pad">
          <div class="section-title"><Icon name="clock" :size="14" />时间线</div>
          <dl class="dl" style="grid-template-columns: 68px 1fr">
            <dt>选题</dt><dd class="small mono">{{ offering.timeline.selectStart }} → {{ offering.timeline.selectEnd }}</dd>
            <dt>提交截止</dt><dd class="small mono">{{ offering.timeline.submitDeadline || '—' }}</dd>
            <dt>答辩</dt><dd class="small mono">{{ offering.timeline.defenseStart || '—' }} → {{ offering.timeline.defenseEnd || '—' }}</dd>
          </dl>
        </section>
      </div>
    </div>
  </div>
</template>
