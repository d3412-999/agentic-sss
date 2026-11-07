<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import StatusPill from '../components/StatusPill.vue'
import EmptyState from '../components/EmptyState.vue'
import ProgressRing from '../components/ProgressRing.vue'
import { api } from '../api/endpoints'
import { currentRole, session } from '../stores/session'
import { toastError } from '../stores/toast'

const router = useRouter()
const loading = ref(true)
const role = computed(() => currentRole.value)
const isTeacher = computed(() => role.value === 'TEACHER')

const offerings = ref([])
const dashboard = ref(null)
const focus = ref(null)
const selections = ref([])
const grades = ref([])
const notifications = ref({ list: [], unreadCount: 0 })

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 19) return '下午好'
  return '晚上好'
})
const name = computed(() => session.user?.name || session.me?.name || '')

const teachKpis = computed(() => {
  const d = dashboard.value
  return [
    { label: '教学班', value: offerings.value.length, unit: '个', icon: 'book', hint: '本学期在管' },
    { label: '在册学生', value: d?.studentCount ?? 0, unit: '人', icon: 'users', hint: d ? `${d.selectedStudentCount ?? 0} 人已选题` : '' },
    { label: '选题数', value: d?.selectionCount ?? 0, unit: '个', icon: 'target', hint: d ? `源码提交率 ${Math.round((d.submissionRate ?? 0) * 100)}%` : '' },
    { label: '待答辩', value: d?.defensePendingCount ?? 0, unit: '人', icon: 'flag', hint: d ? `已完成 ${d.defenseFinishedCount ?? 0} 人` : '' },
  ]
})

const studentKpis = computed(() => {
  const submitted = selections.value.filter((s) => s.progress?.sourceUploaded && s.progress?.reportUploaded).length
  return [
    { label: '可选课程', value: offerings.value.length, unit: '门', icon: 'book', hint: `已选 ${selections.value.length} 个题目` },
    { label: '材料齐全', value: submitted, unit: '/', icon: 'upload', hint: `共 ${selections.value.length} 个选题` },
    { label: 'AI 已评审', value: selections.value.filter((s) => s.progress?.codeAnalyzed).length, unit: '个', icon: 'wand', hint: '源码评审完成' },
    { label: '已出成绩', value: grades.value.length, unit: '门', icon: 'chart', hint: '仅显示已发布成绩' },
  ]
})

function daysLeft(value) {
  if (!value) return null
  const ms = new Date(value).getTime() - Date.now()
  return Math.ceil(ms / 86400000)
}

function deadlineTone(list) {
  const days = list.map((s) => daysLeft(s.submitDeadline)).filter((d) => d !== null)
  if (!days.length) return 'info'
  const min = Math.min(...days)
  if (min < 0) return 'danger'
  if (min <= 3) return 'warn'
  return 'info'
}

/** 后端在无数据时返回 0，这里显示成「—」，避免误读成真实 0 分。 */
function avg(value) {
  if (value === null || value === undefined) return '—'
  return Number(value) === 0 ? '—' : value
}

function stepState(selection, key) {
  const p = selection.progress || {}
  const done = {
    source: p.sourceUploaded,
    report: p.reportUploaded,
    review: p.codeAnalyzed && p.reportEvaluated,
    defense: ['DEFENDED', 'GRADED', 'COMPLETED', 'EVALUATED'].includes(p.defenseStatus || selection.status),
    grade: selection.status === 'GRADED' || selection.scores?.finalScore != null,
  }[key]
  return done ? 'is-done' : ''
}

async function loadTeacher() {
  const page = await api.teacherOfferings({ page: 1, size: 6 })
  offerings.value = page?.list || []
  const preferred = offerings.value.find((o) => o.status === 'PUBLISHED') || offerings.value[0]
  if (preferred) {
    focus.value = preferred
    const [d, detail] = await Promise.all([
      api.offeringDashboard(preferred.id),
      api.offering(preferred.id).catch(() => null),
    ])
    dashboard.value = d
    if (detail) focus.value = { ...preferred, ...detail }
  }
}

async function loadStudent() {
  const [page, sel] = await Promise.all([
    api.studentOfferings({ page: 1, size: 8 }),
    api.selections({ page: 1, size: 8 }),
  ])
  offerings.value = page?.list || []
  selections.value = sel?.list || []
  try {
    const g = await api.studentGrades()
    grades.value = g?.list || []
  } catch {
    grades.value = []
  }
}

onMounted(async () => {
  loading.value = true
  try {
    if (isTeacher.value) await loadTeacher()
    else await loadStudent()
  } catch (err) {
    toastError(err, '加载概览失败')
  } finally {
    loading.value = false
  }
  try {
    notifications.value = await api.notifications({ page: 1, size: 4 })
  } catch {
    // 通知失败不阻塞页面
  }
})

function teacherOpen(id) {
  router.push({ name: 'offering-workbench', params: { offeringId: id } })
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>{{ greeting }}，{{ name }}</h1>
        <p>
          {{ isTeacher ? '这里是你的教学工作台：学生、题目、提交与成绩都在一个页面里。' : '选题、交材料、看评审与成绩 —— 每一步都在这条流水线上。' }}
        </p>
      </div>
      <div class="page-head__actions">
        <button v-if="isTeacher" class="btn btn--ghost" @click="router.push('/offerings')">
          <Icon name="book" :size="15" />教学班管理
        </button>
        <button v-else class="btn btn--ghost" @click="router.push('/courses')">
          <Icon name="target" :size="15" />去选题
        </button>
        <button class="btn btn--primary" @click="router.push('/tasks')">
          <Icon name="clock" :size="15" />任务中心
        </button>
      </div>
    </div>

    <!-- KPI -->
    <div v-if="loading" class="grid grid--4">
      <div v-for="i in 4" :key="i" class="skeleton" style="height: 104px" />
    </div>
    <div v-else class="grid grid--4">
      <div v-for="k in (isTeacher ? teachKpis : studentKpis)" :key="k.label" class="kpi">
        <div class="kpi__label"><Icon :name="k.icon" :size="14" />{{ k.label }}</div>
        <div class="kpi__value num">{{ k.value }}<small>{{ k.unit }}</small></div>
        <div class="kpi__foot">{{ k.hint }}</div>
      </div>
    </div>

    <div class="grid grid--sidebar" style="margin-top: 16px">
      <div style="display: grid; gap: 16px">
        <!-- 教师：教学班 -->
        <section v-if="isTeacher" class="card">
          <div class="card__head">
            <h3>教学班</h3>
            <span class="pill pill--brand">{{ offerings.length }}</span>
            <RouterLink to="/offerings" class="spacer small">全部 →</RouterLink>
          </div>
          <div v-if="loading" class="card__body">
            <div class="skeleton" style="height: 120px" />
          </div>
          <div v-else-if="!offerings.length" class="card__body">
            <EmptyState icon="book" title="还没有教学班" hint="先建课程模板，再开一个教学班">
              <RouterLink to="/offerings" class="btn btn--primary btn--sm" style="margin-top: 10px">
                新建教学班
              </RouterLink>
            </EmptyState>
          </div>
          <div v-else class="card__body card__body--flush">
            <div v-for="o in offerings.slice(0, 4)" :key="o.id" class="list-row" style="cursor: pointer" @click="teacherOpen(o.id)">
              <div style="min-width: 0; flex: 1">
                <div class="row" style="gap: 8px">
                  <span class="list-row__title truncate">{{ o.courseName }}</span>
                  <StatusPill :status="o.status" />
                </div>
                <div class="list-row__meta">
                  {{ o.semester }} · {{ o.className }} · {{ o.studentCount }} 名学生 · {{ o.topicCount }} 道题 ·
                  已选 {{ o.selectionCount }}
                </div>
              </div>
              <Icon name="chevron-right" :size="15" class="dim" />
            </div>
          </div>
        </section>

        <!-- 教师：总览统计 -->
        <section v-if="isTeacher && focus" class="card">
          <div class="card__head">
            <h3>{{ focus.courseName }} · 运行情况</h3>
            <StatusPill :status="focus.status" />
            <button class="btn btn--ghost btn--sm spacer" @click="teacherOpen(focus.id)">打开工作台</button>
          </div>
          <div class="card__body">
            <div class="grid grid--3" style="gap: 14px">
              <div>
                <div class="small dim">源码提交率</div>
                <div class="row" style="margin-top: 6px">
                  <ProgressRing :value="Math.round((dashboard?.submissionRate ?? 0) * 100)" :size="58" label="提交" />
                  <div class="small muted">
                    <div>源码 {{ dashboard?.sourceSubmittedCount ?? 0 }} 份</div>
                    <div>报告 {{ dashboard?.reportSubmittedCount ?? 0 }} 份</div>
                  </div>
                </div>
              </div>
              <div>
                <div class="small dim">平均分</div>
                <dl class="dl" style="grid-template-columns: 56px 1fr; margin-top: 6px">
                  <dt>综合</dt>
                  <dd class="mono">{{ avg(dashboard?.averageScore?.finalScore) }}</dd>
                  <dt>答辩</dt>
                  <dd class="mono">{{ avg(dashboard?.averageScore?.defense) }}</dd>
                  <dt>源码</dt>
                  <dd class="mono">{{ avg(dashboard?.averageScore?.code) }}</dd>
                  <dt>报告</dt>
                  <dd class="mono">{{ avg(dashboard?.averageScore?.report) }}</dd>
                </dl>
              </div>
              <div>
                <div class="small dim">权重构成</div>
                <div v-if="focus.weights" style="margin-top: 8px">
                  <div class="stat-line"><span>答辩</span><span class="spacer" /><span class="mono num">{{ focus.weights.defense }}%</span></div>
                  <div class="bar" style="margin: 4px 0 8px"><div class="bar__fill" :style="{ width: (focus.weights.defense || 0) + '%' }" /></div>
                  <div class="stat-line"><span>源码</span><span class="spacer" /><span class="mono num">{{ focus.weights.code }}%</span></div>
                  <div class="bar" style="margin: 4px 0 8px"><div class="bar__fill" :style="{ width: (focus.weights.code || 0) + '%' }" /></div>
                  <div class="stat-line"><span>报告</span><span class="spacer" /><span class="mono num">{{ focus.weights.report }}%</span></div>
                  <div class="bar" style="margin-top: 4px"><div class="bar__fill" :style="{ width: (focus.weights.report || 0) + '%' }" /></div>
                </div>
              </div>
            </div>

            <hr class="divider" />

            <div class="grid grid--2">
              <div>
                <div class="section-title"><Icon name="chart" :size="14" />成绩分布</div>
                <div v-if="dashboard?.scoreDistribution?.length" style="display: grid; gap: 8px">
                  <div v-for="d in dashboard.scoreDistribution" :key="d.range" class="row" style="gap: 8px">
                    <span class="small dim mono" style="width: 58px">{{ d.range }}</span>
                    <div class="bar" style="flex: 1">
                      <div class="bar__fill" :style="{ width: Math.min(100, (d.count / Math.max(1, dashboard.studentCount)) * 100) + '%' }" />
                    </div>
                    <span class="small num" style="width: 28px; text-align: right">{{ d.count }}</span>
                  </div>
                </div>
                <div v-else class="small dim">暂无成绩数据（需要先重算成绩）</div>
              </div>
              <div>
                <div class="section-title"><Icon name="alert" :size="14" />高频源码问题</div>
                <div v-if="dashboard?.topIssues?.length" style="display: grid; gap: 7px">
                  <div v-for="(i, idx) in dashboard.topIssues.slice(0, 5)" :key="idx" class="row small">
                    <span class="tag">{{ i.tool }}</span>
                    <span class="truncate muted">{{ i.rule }}</span>
                    <span class="spacer num dim">{{ i.count }}</span>
                  </div>
                </div>
                <div v-else class="small dim">还没有静态分析明细（AI 评审接入后会出现）</div>
              </div>
            </div>
          </div>
        </section>

        <!-- 学生：我的选题流水线 -->
        <section v-if="!isTeacher" class="card">
          <div class="card__head">
            <h3>材料与评审进度</h3>
            <span class="pill pill--brand">{{ selections.length }}</span>
            <RouterLink to="/selections" class="spacer small">全部 →</RouterLink>
          </div>
          <div v-if="loading" class="card__body"><div class="skeleton" style="height: 120px" /></div>
          <div v-else-if="!selections.length" class="card__body">
            <EmptyState icon="target" title="还没有选题" hint="进入课程后挑选题目，名额由数据库原子预占">
              <RouterLink to="/courses" class="btn btn--primary btn--sm" style="margin-top: 10px">去选课</RouterLink>
            </EmptyState>
          </div>
          <div v-else class="card__body card__body--flush">
            <div v-for="s in selections.slice(0, 4)" :key="s.id" class="list-row" style="align-items: flex-start">
              <div style="min-width: 0; flex: 1">
                <div class="row" style="gap: 8px">
                  <span class="list-row__title truncate">{{ s.topic?.title || '题目' }}</span>
                  <StatusPill :status="s.status" />
                </div>
                <div class="steps" style="margin-top: 8px; overflow-x: auto">
                  <span class="step" :class="stepState(s, 'source')"><span class="step__dot">1</span>源码</span>
                  <span class="step__line" />
                  <span class="step" :class="stepState(s, 'report')"><span class="step__dot">2</span>报告</span>
                  <span class="step__line" />
                  <span class="step" :class="stepState(s, 'review')"><span class="step__dot">3</span>AI 评审</span>
                  <span class="step__line" />
                  <span class="step" :class="stepState(s, 'grade')"><span class="step__dot">4</span>成绩</span>
                </div>
              </div>
              <div style="text-align: right">
                <div class="small dim">截止</div>
                <div class="small mono">{{ s.submitDeadline ? new Date(s.submitDeadline).toLocaleDateString('zh-CN') : '—' }}</div>
              </div>
            </div>
          </div>
        </section>

        <!-- 学生：课程 -->
        <section v-if="!isTeacher" class="card">
          <div class="card__head">
            <h3>我的课程</h3>
            <RouterLink to="/courses" class="spacer small">全部 →</RouterLink>
          </div>
          <div v-if="!loading && !offerings.length" class="card__body">
            <EmptyState icon="book" title="暂时没有可选课程" hint="等教师发布开课后即可见" />
          </div>
          <div v-else class="card__body card__body--flush">
            <div v-for="o in offerings.slice(0, 4)" :key="o.offeringId" class="list-row">
              <div class="avatar">{{ (o.courseName || '课').slice(0, 1) }}</div>
              <div style="min-width: 0; flex: 1">
                <div class="list-row__title truncate">{{ o.courseName }}</div>
                <div class="list-row__meta">{{ o.teacherName || '任课教师' }} · {{ o.semester }} · 已选 {{ o.mySelectionCount }} / {{ o.topicSelectLimit }}</div>
              </div>
              <RouterLink
                :to="{ name: 'topic-select', params: { offeringId: o.offeringId } }"
                class="btn btn--sm"
                :class="o.canSelect ? 'btn--primary' : 'btn--ghost'"
              >
                {{ o.canSelect ? '去选题' : '查看' }}
              </RouterLink>
            </div>
          </div>
        </section>
      </div>

      <!-- 右侧栏 -->
      <div style="display: grid; gap: 16px; align-content: start">
        <section class="card">
          <div class="card__head">
            <h3>最新通知</h3>
            <RouterLink to="/notifications" class="spacer small">全部 →</RouterLink>
          </div>
          <div v-if="!notifications.list?.length" class="card__body">
            <EmptyState icon="bell" title="没有新通知" />
          </div>
          <div v-else class="card__body card__body--flush">
            <div v-for="n in notifications.list.slice(0, 4)" :key="n.id" class="list-row" :class="{ 'is-unread': !n.read }">
              <div style="min-width: 0">
                <div class="list-row__title truncate">{{ n.title }}</div>
                <div class="list-row__meta truncate">{{ n.content }}</div>
              </div>
            </div>
          </div>
        </section>

        <section class="card card--pad">
          <div class="section-title"><Icon name="flag" :size="14" />流程说明</div>
          <div class="timeline">
            <div class="timeline__item is-done">
              <div class="timeline__title">教师开课出题</div>
              <div class="timeline__meta">草稿 → 发布（检查权重、时限与题目）</div>
            </div>
            <div class="timeline__item" :class="{ 'is-done': isTeacher ? focus?.status === 'PUBLISHED' : offerings.length }">
              <div class="timeline__title">学生选题</div>
              <div class="timeline__meta">名额原子预占，退选立即释放</div>
            </div>
            <div class="timeline__item" :class="{ 'is-done': isTeacher ? (dashboard?.sourceSubmittedCount ?? 0) > 0 : selections.some((s) => s.progress?.sourceUploaded) }">
              <div class="timeline__title">提交源码与报告</div>
              <div class="timeline__meta">SHA-256 秒传 + 版本历史</div>
            </div>
            <div class="timeline__item" :class="{ 'is-done': isTeacher ? (dashboard?.averageScore?.code ?? null) != null : selections.some((s) => s.progress?.codeAnalyzed) }">
              <div class="timeline__title">AI 评审</div>
              <div class="timeline__meta">异步任务，结果参与加权算分</div>
            </div>
            <div class="timeline__item" :class="{ 'is-done': isTeacher ? (dashboard?.defenseFinishedCount ?? 0) > 0 : grades.length > 0 }">
              <div class="timeline__title">答辩与成绩发布</div>
              <div class="timeline__meta">锁定后学生可见，撤销有 24h 窗口</div>
            </div>
          </div>
        </section>

        <section v-if="!isTeacher && selections.length" class="card card--pad">
          <div class="section-title"><Icon name="clock" :size="14" />截止提醒</div>
          <div
            class="alert"
            :class="`alert--${deadlineTone(selections)}`"
          >
            <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
            <span v-if="Math.min(...selections.map((s) => daysLeft(s.submitDeadline) ?? 99)) < 0">
              有选题已过提交截止时间，请尽快联系教师。
            </span>
            <span v-else>
              最近一次提交截止还有
              <strong>{{ Math.min(...selections.map((s) => daysLeft(s.submitDeadline) ?? 99)) }}</strong>
              天，请先上传源码 zip 与报告 PDF。
            </span>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>
