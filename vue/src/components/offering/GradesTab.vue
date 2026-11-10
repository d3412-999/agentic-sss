<script setup>
import { computed, onMounted, ref } from 'vue'
import Icon from '../Icon.vue'
import StatusPill from '../StatusPill.vue'
import EmptyState from '../EmptyState.vue'
import Modal from '../Modal.vue'
import Pager from '../Pager.vue'
import { api } from '../../api/endpoints'
import { confirm } from '../../stores/confirm'
import { track } from '../../stores/tasks'
import { toast, toastError } from '../../stores/toast'

const props = defineProps({
  offeringId: { type: String, required: true },
  offering: { type: Object, default: null },
})

const loading = ref(true)
const busy = ref('')
const rows = ref([])
const summary = ref(null)
const page = ref(1)
const size = 20
const total = ref(0)
const keyword = ref('')
const statusFilter = ref('')

/* ---------- 调整成绩 ---------- */
const adjustOpen = ref(false)
const detail = ref(null)
const adjustError = ref('')
const adjustForm = ref({ defenseScore: '', codeScore: '', reportScore: '', reason: '', version: 0 })

const weights = computed(() => props.offering?.weights || { defense: 40, code: 35, report: 25 })

const sumRows = computed(() => {
  const s = summary.value
  const empty = !s || total.value === 0
  return [
    { label: '平均分', value: empty ? '—' : (s.average ?? '—') },
    { label: '最高分', value: empty ? '—' : (s.max ?? '—') },
    { label: '最低分', value: empty ? '—' : (s.min ?? '—') },
    { label: '及格率', value: empty ? '—' : s.passRate != null ? `${Math.round(s.passRate * 100)}%` : '—' },
  ]
})

async function load() {
  loading.value = true
  try {
    const data = await api.teacherGrades(props.offeringId, {
      page: page.value,
      size,
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
    })
    rows.value = data?.list || []
    total.value = data?.total || 0
    summary.value = data?.summary || null
  } catch (err) {
    toastError(err, '加载成绩失败')
  } finally {
    loading.value = false
  }
}

async function recalc() {
  busy.value = 'recalc'
  try {
    const accepted = await api.recalculateGrades(props.offeringId, { scope: 'ALL', overwriteAdjusted: false })
    track(accepted.taskId, { label: '重算成绩', kind: 'RECALC', offeringId: props.offeringId })
    toast.info('已提交重算任务', '可在任务中心查看进度，完成后刷新本页')
  } catch (err) {
    toastError(err, '重算失败')
  } finally {
    busy.value = ''
  }
}

async function publish() {
  if (!(await confirm({
    title: '发布成绩',
    message: '发布前会按最新答辩 / 源码 / 报告结果重新计算（人工调整的成绩保留），发布后学生可见且成绩锁定。允许跳过缺失分项的学生，并发送站内通知。',
    confirmText: '确认发布',
  }))) return
  busy.value = 'publish'
  try {
    const result = await api.publishGrades(props.offeringId, {
      gradeIds: [],
      allowIncomplete: true,
      notifyStudents: true,
    })
    let msg = `已发布 ${result.published ?? 0} 条`
    if (result.skipped) msg += `，跳过 ${result.skipped} 条`
    toast.ok('成绩已发布', msg)
    await load()
  } catch (err) {
    toastError(err, '发布失败')
  } finally {
    busy.value = ''
  }
}

async function unpublish() {
  if (!(await confirm({ title: '撤销发布', message: '仅支持发布后 24 小时内撤销，撤销后学生立即看不到成绩。', danger: true }))) return
  busy.value = 'unpublish'
  try {
    const data = await api.unpublishGrades(props.offeringId, { gradeIds: [] })
    toast.ok('已撤销发布', `${data?.updated ?? 0} 条成绩回到可编辑状态`)
    await load()
  } catch (err) {
    toastError(err, '撤销失败')
  } finally {
    busy.value = ''
  }
}

async function exportCsv() {
  busy.value = 'export'
  try {
    const accepted = await api.exportGrades(props.offeringId, {
      format: 'CSV',
      includeEvidence: false,
      includeComments: true,
      includeIssues: true,
    })
    track(accepted.taskId, { label: '导出成绩 CSV', kind: 'EXPORT', offeringId: props.offeringId })
    toast.info('导出任务已受理', '完成后在任务中心下载')
  } catch (err) {
    toastError(err, '导出失败')
  } finally {
    busy.value = ''
  }
}

async function openAdjust(row) {
  adjustError.value = ''
  adjustForm.value = { defenseScore: '', codeScore: '', reportScore: '', reason: '', version: 0 }
  try {
    const d = await api.gradeDetail(row.gradeId)
    detail.value = d
    adjustForm.value = {
      defenseScore: d.items?.length ? '' : '',
      codeScore: '',
      reportScore: '',
      reason: '',
      version: d.version,
    }
    adjustOpen.value = true
  } catch (err) {
    toastError(err, '读取成绩详情失败')
  }
}

function pickWeighted(row, key) {
  return row?.[key] ?? '—'
}

async function submitAdjust() {
  adjustError.value = ''
  const f = adjustForm.value
  if ((f.reason || '').trim().length < 5) return (adjustError.value = '调整原因至少 5 个字（会写入审计日志）')
  const payload = { reason: f.reason.trim(), version: f.version }
  if (f.defenseScore !== '') payload.defenseScore = Number(f.defenseScore)
  if (f.codeScore !== '') payload.codeScore = Number(f.codeScore)
  if (f.reportScore !== '') payload.reportScore = Number(f.reportScore)
  if (payload.defenseScore === undefined && payload.codeScore === undefined && payload.reportScore === undefined) {
    return (adjustError.value = '至少填写一个要调整的分项')
  }
  busy.value = 'adjust'
  try {
    await api.adjustGrade(detail.value.gradeId, payload)
    toast.ok('成绩已调整', '已重算综合分并写入审计日志')
    adjustOpen.value = false
    await load()
  } catch (err) {
    adjustError.value = err.message
  } finally {
    busy.value = ''
  }
}

async function unlock(row) {
  if (!(await confirm({ title: '解锁成绩', message: `解锁「${row.name}」的成绩以便重新调整？`, danger: true }))) return
  try {
    await api.unlockGrade(row.gradeId)
    toast.ok('已解锁', row.name)
    await load()
  } catch (err) {
    toastError(err, '解锁失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="grid grid--4" style="margin-bottom: 14px">
      <div v-for="s in sumRows" :key="s.label" class="kpi" style="padding: 13px 16px">
        <div class="kpi__label">{{ s.label }}</div>
        <div class="kpi__value num" style="font-size: 21px">{{ s.value }}</div>
      </div>
    </div>

    <div class="row row--wrap" style="margin-bottom: 14px">
      <span class="pill pill--brand">权重 答辩 {{ weights.defense }}% · 源码 {{ weights.code }}% · 报告 {{ weights.report }}%</span>
      <input v-model="keyword" class="input" style="width: 190px" placeholder="搜索学号 / 姓名" @keyup.enter="page = 1; load()" />
      <select v-model="statusFilter" class="select" style="width: 130px" @change="page = 1; load()">
        <option value="">全部状态</option>
        <option value="AUTO">自动计算</option>
        <option value="ADJUSTED">已调整</option>
        <option value="LOCKED">已发布</option>
      </select>
      <button class="btn btn--ghost btn--sm" @click="page = 1; load()"><Icon name="search" :size="14" />查询</button>
      <div class="spacer" />
      <button class="btn btn--ghost btn--sm" :disabled="busy === 'recalc'" @click="recalc">
        <Icon name="refresh" :size="14" />重算
      </button>
      <button class="btn btn--ghost btn--sm" :disabled="busy === 'export'" @click="exportCsv">
        <Icon name="download" :size="14" />导出 CSV
      </button>
      <button class="btn btn--ghost btn--sm" :disabled="busy === 'unpublish'" @click="unpublish">撤销发布</button>
      <button class="btn btn--primary btn--sm" :disabled="busy === 'publish'" @click="publish">
        <Icon name="check" :size="14" />发布成绩
      </button>
    </div>

    <div class="card">
      <div v-if="loading" class="card__body"><div class="skeleton" style="height: 220px" /></div>
      <EmptyState
        v-else-if="!rows.length"
        icon="chart"
        title="还没有成绩"
        hint="点击「重算」根据答辩 / 源码 / 报告生成成绩"
      >
        <button class="btn btn--primary btn--sm" style="margin-top: 10px" @click="recalc">重算成绩</button>
      </EmptyState>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>学生</th>
              <th class="ta-c">答辩</th>
              <th class="ta-c">源码</th>
              <th class="ta-c">报告</th>
              <th class="ta-c">综合</th>
              <th>等级</th>
              <th>状态</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.gradeId">
              <td>
                <div class="table__primary">{{ r.name }}</div>
                <div class="table__sub mono">{{ r.studentNo }} · {{ r.topicCount }} 个选题</div>
              </td>
              <td class="ta-c num mono">{{ pickWeighted(r, 'defenseScore') }}</td>
              <td class="ta-c num mono">{{ pickWeighted(r, 'codeScore') }}</td>
              <td class="ta-c num mono">{{ pickWeighted(r, 'reportScore') }}</td>
              <td class="ta-c num mono" style="font-weight: 650">{{ r.finalScore ?? '—' }}</td>
              <td>
                <span class="pill" :class="r.level === '优秀' ? 'pill--ok' : r.level === '不及格' ? 'pill--danger' : 'pill--info'">
                  {{ r.level || '—' }}
                </span>
              </td>
              <td>
                <div class="row" style="gap: 4px">
                  <StatusPill :status="r.status" />
                  <span v-if="r.adjusted" class="tag">已调整</span>
                </div>
              </td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 4px">
                  <button class="btn btn--ghost btn--sm" @click="openAdjust(r)">调整</button>
                  <button v-if="r.published" class="btn btn--icon btn--sm" title="解锁" @click="unlock(r)">
                    <Icon name="lock" :size="14" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager v-if="total > size" v-model:page="page" :size="size" :total="total" @update:page="load" />
    </div>

    <Modal v-if="adjustOpen" title="调整成绩" size="lg" @close="adjustOpen = false">
      <div v-if="detail" style="display: grid; gap: 14px">
        <div class="row">
          <div class="avatar">{{ detail.student?.name?.slice(0, 1) }}</div>
          <div>
            <div style="font-weight: 600">{{ detail.student?.name }}</div>
            <div class="small dim mono">{{ detail.student?.studentNo }} · {{ detail.student?.className || '—' }}</div>
          </div>
          <div class="spacer" />
          <span class="pill pill--brand">当前综合 {{ detail.finalScore ?? '—' }} · {{ detail.level || '—' }}</span>
        </div>

        <div class="table-wrap" style="border: 1px solid var(--line); border-radius: 10px">
          <table class="table">
            <thead>
              <tr><th>选题</th><th class="ta-c">源码</th><th class="ta-c">报告</th><th class="ta-c">答辩</th><th class="ta-c">小计</th></tr>
            </thead>
            <tbody>
              <tr v-for="(it, i) in detail.items" :key="i">
                <td class="truncate" style="max-width: 240px">{{ it.topicTitle }}</td>
                <td class="ta-c mono">{{ it.codeScore ?? '—' }}</td>
                <td class="ta-c mono">{{ it.reportScore ?? '—' }}</td>
                <td class="ta-c mono">{{ it.defenseScore ?? '—' }}</td>
                <td class="ta-c mono">{{ it.finalScore ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="form-grid form-grid--3">
          <div class="field">
            <label>答辩分 ({{ detail.weights?.defense }}%)</label>
            <input v-model="adjustForm.defenseScore" type="number" min="0" max="100" step="0.5" class="input" placeholder="不改留空" />
          </div>
          <div class="field">
            <label>源码分 ({{ detail.weights?.code }}%)</label>
            <input v-model="adjustForm.codeScore" type="number" min="0" max="100" step="0.5" class="input" placeholder="不改留空" />
          </div>
          <div class="field">
            <label>报告分 ({{ detail.weights?.report }}%)</label>
            <input v-model="adjustForm.reportScore" type="number" min="0" max="100" step="0.5" class="input" placeholder="不改留空" />
          </div>
        </div>

        <div class="field">
          <label>调整原因（写入审计日志，至少 5 个字）</label>
          <textarea v-model="adjustForm.reason" class="textarea" style="min-height: 70px" placeholder="例如：源码缺失核心模块，人工复核后下调 5 分" />
        </div>

        <div v-if="adjustError" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ adjustError }}
        </div>
      </div>
      <template #foot>
        <button class="btn btn--ghost" @click="adjustOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy === 'adjust'" @click="submitAdjust">
          <span v-if="busy === 'adjust'" class="spinner" />保存调整
        </button>
      </template>
    </Modal>
  </div>
</template>
