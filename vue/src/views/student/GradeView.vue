<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-title">我的成绩</div>
        <div class="page-sub">2026-2027 学年第一学期 · 教师发布后成绩即为最终结果</div>
      </div>
      <div class="page-actions">
        <span class="chip amber"><span class="dot" /> 尚未发布</span>
        <button class="btn btn-outline" disabled>
          <AppIcon name="download" :size="18" />
          导出成绩单
        </button>
      </div>
    </div>

    <div class="grade-grid">
      <section class="card">
        <div class="card-head">
          <div>
            <div class="card-title">面向对象程序设计课程设计(Java)</div>
            <div class="card-desc">李文博 · 计科2301 · 2 学分</div>
          </div>
          <StatusChip status="AUTO" label="待发布" />
        </div>

        <div class="card-pad">
          <div class="hero">
            <ProgressRing :value="83.7" :size="150" :stroke="11" />
            <div class="hero-meta">
              <div class="row gap-8">
                <span class="chip green">良好</span>
                <span class="chip">百分制</span>
              </div>
              <div class="hero-note mt-12">
                总评 = 答辩 85.0 × 40% + 源码 81.5 × 35% + 报告 84.5 × 25%
              </div>
              <div class="tiny dim mt-8">
                计算时间 2026-10-22 10:00 · 教师复核后发布
              </div>
            </div>
          </div>

          <hr class="divider" />

          <div class="col gap-14">
            <div v-for="b in breakdown" :key="b.label" class="b-row">
              <div class="b-head">
                <span class="small">{{ b.label }}</span>
                <span class="small muted"><b class="num" style="color: var(--c-text)">{{ b.score }}</b> · 权重 {{ b.weight }}%</span>
              </div>
              <div class="bar thin mt-6">
                <span :style="{ width: b.score + '%', background: b.color }" />
              </div>
            </div>
          </div>
        </div>
      </section>

      <div class="col gap-16">
        <section class="card card-pad">
          <div class="section-title">成绩构成</div>
          <div class="stack-bar mt-16">
            <span class="seg-a" :style="{ width: '34%' }" />
            <span class="seg-b" :style="{ width: '30%' }" />
            <span class="seg-c" :style="{ width: '36%' }" />
          </div>
          <div class="legend">
            <span><i class="dot-a" /> 答辩 34.0</span>
            <span><i class="dot-b" /> 源码 28.5</span>
            <span><i class="dot-c" /> 报告 21.1</span>
          </div>
          <div class="hint mt-12">按权重折算后的贡献分，合计 83.6（四舍五入后 83.7）</div>
        </section>

        <section class="card card-pad">
          <div class="row gap-8 strong"><AppIcon name="star" :size="16" /> 教师评语</div>
          <p class="small muted mt-8" style="line-height: 1.75">
            整体完成度较好，分层与命名规范；并发场景考虑不足，报告中测试部分需要加强。答辩表达清晰，能主动补充改进方案。
          </p>
          <div class="hint mt-12">评语将在成绩发布后可见 · 当前为预览</div>
        </section>
      </div>
    </div>

    <section class="card mt-16">
      <div class="card-head">
        <div class="card-title">分项成绩</div>
        <span class="tiny dim">多道题目时取平均</span>
      </div>
      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th style="min-width: 240px">题目</th>
              <th>答辩</th>
              <th>源码</th>
              <th>报告</th>
              <th>加权得分</th>
              <th class="right">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.title">
              <td>
                <div class="strong">{{ r.title }}</div>
                <div class="tiny dim mt-4">{{ r.meta }}</div>
              </td>
              <td class="num">{{ r.defense }}</td>
              <td class="num">{{ r.code }}</td>
              <td class="num">{{ r.report }}</td>
              <td class="num strong">{{ r.final }}</td>
              <td class="right"><StatusChip :status="r.status" /></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<script setup>
import AppIcon from '@/components/AppIcon.vue'
import ProgressRing from '@/components/ProgressRing.vue'
import StatusChip from '@/components/StatusChip.vue'

const breakdown = [
  { label: '在线答辩', score: 85, weight: 40, color: 'var(--c-blue)' },
  { label: '源码质量', score: 81.5, weight: 35, color: 'var(--c-green)' },
  { label: '报告质量', score: 84.5, weight: 25, color: 'var(--c-violet)' }
]

const rows = [
  { title: '基于 JavaFX 的图书管理系统', meta: '答辩 2026-10-21 · 6 道题', defense: 85, code: 81.5, report: 84.5, final: 83.7, status: 'EVALUATED' },
  { title: '学生成绩管理系统', meta: '材料未提交 · 未参加答辩', defense: '—', code: '—', report: '—', final: '—', status: 'SELECTED' }
]
</script>

<style scoped>
.grade-grid { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr); gap: 16px; }

.hero { display: flex; align-items: center; gap: 32px; padding: 8px 0 4px; }
.hero-meta { flex: 1; }
.hero-note { font-size: 14px; color: var(--c-text-2); line-height: 1.7; }

.b-row .b-head { display: flex; align-items: center; justify-content: space-between; }

.stack-bar { display: flex; height: 14px; border-radius: 999px; overflow: hidden; background: var(--c-grey-100); }
.stack-bar span { display: block; height: 100%; }
.seg-a { background: #1a73e8; }
.seg-b { background: #34a853; }
.seg-c { background: #a142f4; }

.legend { display: flex; gap: 14px; margin-top: 12px; font-size: 13px; color: var(--c-text-2); flex-wrap: wrap; }
.legend i { display: inline-block; width: 8px; height: 8px; border-radius: 2px; margin-right: 5px; }
.dot-a { background: #1a73e8; } .dot-b { background: #34a853; } .dot-c { background: #a142f4; }

@media (max-width: 1080px) {
  .grade-grid { grid-template-columns: 1fr; }
  .hero { flex-direction: column; align-items: flex-start; gap: 20px; }
}
</style>
