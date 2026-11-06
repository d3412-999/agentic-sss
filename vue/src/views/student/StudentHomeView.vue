<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-title">下午好，{{ session.name }}</div>
        <div class="page-sub">计科2301 · 2026-2027 学年第一学期 · 已选 2 道题目</div>
      </div>
      <div class="page-actions">
        <button class="btn btn-outline">
          <AppIcon name="book" :size="18" />
          查看课程
        </button>
        <RouterLink class="btn btn-primary" to="/student/defense">
          <AppIcon name="mic" :size="18" />
          进入答辩
        </RouterLink>
      </div>
    </div>

    <div class="grid g4">
      <StatCard label="已选题目" value="2" suffix="/ 3" hint="还可选择 1 道" icon="list" />
      <StatCard label="待提交材料" value="1" hint="源码与报告尚未齐全" icon="upload" tone="amber" />
      <StatCard label="待完成答辩" value="1" hint="答辩截止 10 月 30 日" icon="mic" tone="violet" />
      <StatCard label="当前总评" value="83.7" suffix="分" hint="良好 · 待教师发布" icon="award" tone="green" />
    </div>

    <div class="home-grid">
      <div class="col gap-16">
        <section class="card">
          <div class="card-head">
            <div>
              <div class="card-title">面向对象程序设计课程设计(Java)</div>
              <div class="card-desc">李文博 · 计科2301 · 42 人</div>
            </div>
            <StatusChip status="PUBLISHED" />
          </div>

          <div class="card-pad">
            <div class="steps">
              <div v-for="(s, i) in steps" :key="s.label" class="step" :class="{ done: s.done, current: s.current }">
                <div class="step-dot">
                  <AppIcon v-if="s.done" name="check" :size="14" />
                  <span v-else>{{ i + 1 }}</span>
                </div>
                <div class="step-label">{{ s.label }}</div>
                <div class="step-meta">{{ s.meta }}</div>
              </div>
            </div>

            <hr class="divider" />

            <div class="col gap-12">
              <div v-for="s in selections" :key="s.id" class="topic-row">
                <div class="topic-main">
                  <div class="row gap-8">
                    <span class="strong">{{ s.topicTitle }}</span>
                    <StatusChip :status="s.status" />
                  </div>
                  <div class="row gap-8 mt-4 tiny dim">
                    <span>{{ s.difficulty === 'HARD' ? '较难' : s.difficulty === 'EASY' ? '简单' : '中等' }}</span>
                    <span>·</span>
                    <span>源码 {{ s.sourceUploaded ? `v${s.sourceVersion}` : '未提交' }}</span>
                    <span>·</span>
                    <span>报告 {{ s.reportUploaded ? `v${s.reportVersion}` : '未提交' }}</span>
                  </div>
                </div>
                <div class="row gap-8">
                  <span v-if="s.codeScore" class="score">{{ s.codeScore }}</span>
                  <RouterLink class="btn btn-sm btn-tonal" :to="`/student/selections/${s.id}`">
                    {{ s.sourceUploaded ? '查看提交' : '去提交' }}
                  </RouterLink>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section class="card">
          <div class="card-head">
            <div class="card-title">最近反馈</div>
            <span class="chip violet"><AppIcon name="sparkle" :size="13" /> AI 生成</span>
          </div>
          <div class="card-pad">
            <p class="feedback">
              源码整体分层清晰，<b>BookService</b> 与 <b>BorrowService</b> 职责划分合理；但存在 1 处空指针风险与 57 条编码规范告警，
              建议优先修复 <span class="mono">BookController.java:64</span> 的空值处理。报告第 5.2 节测试用例偏少，答辩时可能被追问。
            </p>
            <div class="row gap-8 mt-16">
              <RouterLink class="btn btn-sm btn-outline" to="/student/selections/s1">查看源码分析</RouterLink>
              <RouterLink class="btn btn-sm btn-outline" to="/student/selections/s1">查看报告评审</RouterLink>
            </div>
          </div>
        </section>
      </div>

      <div class="col gap-16">
        <section class="card">
          <div class="card-head">
            <div class="card-title">待办</div>
            <span class="chip amber">3 项</span>
          </div>
          <div class="todo-list">
            <div v-for="t in todos" :key="t.title" class="todo">
              <span class="todo-icon" :class="t.tone">
                <AppIcon :name="t.icon" :size="16" />
              </span>
              <div class="grow">
                <div class="todo-title">{{ t.title }}</div>
                <div class="tiny dim mt-4">{{ t.meta }}</div>
              </div>
              <RouterLink class="btn btn-sm btn-text" :to="t.to">去处理</RouterLink>
            </div>
          </div>
        </section>

        <section class="card">
          <div class="card-head">
            <div class="card-title">时间节点</div>
          </div>
          <div class="card-pad">
            <div class="timeline">
              <div v-for="n in nodes" :key="n.label" class="tl-item" :class="{ past: n.past }">
                <span class="tl-dot" />
                <div class="grow">
                  <div class="between">
                    <span class="tl-label">{{ n.label }}</span>
                    <span class="tiny dim">{{ n.date }}</span>
                  </div>
                  <div class="tiny dim mt-4">{{ n.meta }}</div>
                </div>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import AppIcon from '@/components/AppIcon.vue'
import StatCard from '@/components/StatCard.vue'
import StatusChip from '@/components/StatusChip.vue'
import { session } from '@/stores/session'
import { selections } from '@/data/mock'

const steps = [
  { label: '选题', meta: '已完成 2 道', done: true },
  { label: '提交材料', meta: '1 / 2 已齐', current: true },
  { label: '在线答辩', meta: '待开始', done: false },
  { label: '成绩公布', meta: '预计 11 月初', done: false }
]

const todos = [
  { title: '提交「学生成绩管理系统」的源码与报告', meta: '截止 10 月 15 日 23:59', icon: 'upload', tone: 'amber', to: '/student/selections/s2' },
  { title: '完成在线语音答辩', meta: '系统已生成 6 道答辩题', icon: 'mic', tone: 'violet', to: '/student/defense' },
  { title: '查看源码分析报告', meta: '1 个高优先级问题待修复', icon: 'file', tone: 'blue', to: '/student/selections/s1' }
]

const nodes = [
  { label: '选题开放', date: '09-10', meta: '已结束', past: true },
  { label: '选题截止', date: '09-20', meta: '已结束', past: true },
  { label: '材料提交截止', date: '10-15', meta: '剩余 3 天', past: false },
  { label: '答辩窗口', date: '10-20 ~ 10-30', meta: '尚未开始', past: false },
  { label: '成绩发布', date: '11-01', meta: '待教师操作', past: false }
]
</script>

<style scoped>
.home-grid { display: grid; grid-template-columns: minmax(0, 1.9fr) minmax(0, 1fr); gap: 16px; margin-top: 16px; }

.steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.step { position: relative; padding-right: 8px; }
.step::after {
  content: '';
  position: absolute;
  top: 13px; left: 30px; right: 4px;
  height: 2px;
  background: var(--c-grey-200);
}
.step:last-child::after { display: none; }
.step.done::after { background: var(--c-blue-100); }

.step-dot {
  position: relative;
  z-index: 1;
  width: 28px; height: 28px;
  border-radius: 50%;
  display: grid; place-items: center;
  background: var(--c-grey-100);
  color: var(--c-text-3);
  font-size: 13px;
  font-weight: 600;
}
.step.done .step-dot { background: var(--c-blue); color: #fff; }
.step.current .step-dot { background: var(--c-surface); border: 2px solid var(--c-blue); color: var(--c-blue-700); }
.step-label { margin-top: 8px; font-size: 14px; font-weight: 500; }
.step-meta { font-size: 12.5px; color: var(--c-text-3); margin-top: 2px; }

.topic-row {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 14px 2px;
  border-bottom: 1px solid var(--c-line-2);
}
.topic-row:last-child { border-bottom: none; }
.topic-main { min-width: 0; }
.score { font-size: 19px; font-weight: 500; color: var(--c-green-700); font-variant-numeric: tabular-nums; }

.feedback { font-size: 14.5px; line-height: 1.75; color: var(--c-text-2); }
.feedback b { color: var(--c-text); font-weight: 600; }

.todo-list { padding: 6px 8px 10px; }
.todo { display: flex; align-items: center; gap: 12px; padding: 12px 12px; border-radius: 12px; transition: background .15s var(--ease); }
.todo:hover { background: var(--c-grey-50); }
.todo-icon { width: 32px; height: 32px; border-radius: 10px; display: grid; place-items: center; background: var(--c-blue-50); color: var(--c-blue-700); flex: none; }
.todo-icon.amber { background: var(--c-amber-50); color: var(--c-amber-700); }
.todo-icon.violet { background: var(--c-violet-50); color: var(--c-violet); }
.todo-title { font-size: 14.5px; }

.timeline { display: flex; flex-direction: column; gap: 16px; }
.tl-item { display: flex; gap: 12px; position: relative; }
.tl-item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 5px; top: 16px; bottom: -14px;
  width: 1px;
  background: var(--c-line-2);
}
.tl-dot { width: 11px; height: 11px; border-radius: 50%; border: 2px solid var(--c-blue); background: var(--c-surface); margin-top: 4px; flex: none; }
.tl-item.past .tl-dot { border-color: var(--c-line); background: var(--c-grey-200); }
.tl-label { font-size: 14px; font-weight: 500; }
.tl-item.past .tl-label { color: var(--c-text-3); font-weight: 400; }

@media (max-width: 1080px) {
  .home-grid { grid-template-columns: 1fr; }
  .steps { grid-template-columns: repeat(2, 1fr); row-gap: 20px; }
  .step:nth-child(2)::after { display: none; }
}
</style>
