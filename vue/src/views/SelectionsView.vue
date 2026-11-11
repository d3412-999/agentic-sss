<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from '../components/Icon.vue'
import StatusPill from '../components/StatusPill.vue'
import EmptyState from '../components/EmptyState.vue'
import Drawer from '../components/Drawer.vue'
import { api, DIFFICULTY_LABEL } from '../api/endpoints'
import { confirm } from '../stores/confirm'
import { track } from '../stores/tasks'
import { toast, toastError } from '../stores/toast'

const loading = ref(true)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20

const open = ref(false)
const detail = ref(null)
const submissions = ref([])
const detailLoading = ref(false)
const uploadState = reactive({ SOURCE: { progress: 0, busy: false }, REPORT: { progress: 0, busy: false } })
const selectedRow = ref(null)

const MAX = { SOURCE: 100 * 1024 ** 2, REPORT: 50 * 1024 ** 2 }
const ACCEPT = { SOURCE: '.zip,.jar', REPORT: '.pdf,.docx,.doc' }

const steps = computed(() => {
  const p = detail.value
  const progress = p?.progress || selectedRow.value?.progress || {}
  const status = p?.status || selectedRow.value?.status
  return [
    { key: 'source', label: '源码', done: progress.sourceUploaded || false },
    { key: 'report', label: '报告', done: progress.reportUploaded || false },
    { key: 'review', label: 'AI 评审', done: Boolean(progress.codeAnalyzed && progress.reportEvaluated) },
    { key: 'defense', label: '答辩', done: ['DEFENDED', 'GRADED'].includes(progress.defenseStatus) || status === 'GRADED' },
    { key: 'grade', label: '成绩', done: status === 'GRADED' },
  ]
})

function fmtDate(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function daysLeft(value) {
  if (!value) return null
  return Math.ceil((new Date(value).getTime() - Date.now()) / 86400000)
}

function sizeText(bytes) {
  if (!bytes) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`
}

async function load() {
  loading.value = true
  try {
    const data = await api.selections({ page: page.value, size })
    rows.value = data?.list || []
    total.value = data?.total || 0
  } catch (err) {
    toastError(err, '加载选题失败')
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  selectedRow.value = row
  open.value = true
  detailLoading.value = true
  submissions.value = []
  try {
    const [d, subs] = await Promise.all([
      api.selectionDetail(row.id),
      api.submissions(row.id),
    ])
    detail.value = d
    submissions.value = subs?.list || []
  } catch (err) {
    toastError(err, '读取选题详情失败')
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!detail.value) return
  try {
    const [d, subs] = await Promise.all([api.selectionDetail(detail.value.id), api.submissions(detail.value.id)])
    detail.value = d
    submissions.value = subs?.list || []
    await load()
  } catch {
    // 忽略刷新错误
  }
}

function validate(file, type) {
  if (!file) return '没有选择文件'
  const size = file.size || 0
  if (size > MAX[type]) return `${type === 'SOURCE' ? '源码包' : '报告'}不能超过 ${sizeText(MAX[type])}`
  const lower = (file.name || '').toLowerCase()
  if (type === 'SOURCE' && !/\.(zip|jar)$/.test(lower)) return '源码只接受 .zip 或 .jar'
  if (type === 'REPORT' && !/\.(pdf|docx|doc)$/.test(lower)) return '报告只接受 .pdf / .docx / .doc'
  return ''
}

async function doUpload(type, file) {
  const invalid = validate(file, type)
  if (invalid) {
    toast.error('文件不符合要求', invalid)
    return
  }
  const state = uploadState[type]
  state.busy = true
  state.progress = 0
  try {
    const result = await api.uploadSubmission(detail.value.id, type, file, {
      onProgress: (p) => (state.progress = p),
    })
    toast.ok('上传成功', `${file.name} · 版本 v${result?.version ?? '-'}`)
    if (result?.parseTaskId) {
      track(result.parseTaskId, { label: `${type === 'SOURCE' ? '源码解析' : '报告解析'} ${file.name}`, kind: 'PARSE' })
    }
    await refreshDetail()
  } catch (err) {
    toastError(err, '上传失败')
  } finally {
    state.busy = false
    state.progress = 0
  }
}

function onPick(type, event) {
  const file = event.target.files?.[0]
  if (file) doUpload(type, file)
  event.target.value = ''
}

function onDrop(type, event) {
  event.preventDefault()
  const file = event.dataTransfer.files?.[0]
  if (file) doUpload(type, file)
}

async function reanalyze(sub) {
  if (!(await confirm({ title: '重新评审', message: '将再次调用 AI 服务分析该版本，会产生一条异步任务。', confirmText: '重新评审' }))) return
  try {
    const accepted = await api.reanalyzeSubmission(sub.id, true)
    track(accepted.taskId, { label: `重新评审 v${sub.version}`, kind: 'REANALYZE' })
    toast.info('已提交评审任务', '可在任务中心查看进度')
    await refreshDetail()
  } catch (err) {
    toastError(err, '提交失败')
  }
}

async function removeSubmission(sub) {
  if (!(await confirm({ title: '删除该版本', message: `删除 v${sub.version} 后不可恢复；若它是最新版本，会回退到上一版。`, danger: true, confirmText: '删除' }))) return
  try {
    await api.deleteSubmission(sub.id)
    toast.ok('已删除', `v${sub.version}`)
    await refreshDetail()
  } catch (err) {
    toastError(err, '删除失败')
  }
}

async function cancelSelection() {
  if (!(await confirm({
    title: '退选',
    message: '退选会立即释放题目名额，材料与评审记录将保留但不再参与评分。确定退选吗？',
    danger: true,
    confirmText: '退选',
  }))) return
  try {
    await api.cancelSelection(detail.value.id)
    toast.ok('已退选', '名额已释放')
    open.value = false
    await load()
  } catch (err) {
    toastError(err, '退选失败')
  }
}

function download(sub) {
  api.downloadSubmission(sub.id, `${sub.type === 'SOURCE' ? 'source' : 'report'}-v${sub.version}`).catch((e) =>
    toastError(e, '下载失败'),
  )
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>我的选题</h1>
        <p>上传源码与报告 → AI 评审 → 答辩 → 成绩。每个版本都保留历史，可随时回退查看。</p>
      </div>
      <div class="page-head__actions">
        <RouterLink to="/courses" class="btn btn--ghost"><Icon name="book" :size="15" />我的课程</RouterLink>
        <RouterLink to="/grades" class="btn btn--primary"><Icon name="chart" :size="15" />查看成绩</RouterLink>
      </div>
    </div>

    <div v-if="loading" class="grid grid--2">
      <div v-for="i in 4" :key="i" class="skeleton" style="height: 150px" />
    </div>

    <EmptyState v-else-if="!rows.length" icon="target" title="还没有选题" hint="先到课程里挑一个题目">
      <RouterLink to="/courses" class="btn btn--primary btn--sm" style="margin-top: 10px">去选题</RouterLink>
    </EmptyState>

    <div v-else class="grid grid--2">
      <article v-for="s in rows" :key="s.id" class="card" style="cursor: pointer" @click="openDetail(s)">
        <div class="card__body">
          <div class="row" style="align-items: flex-start">
            <div style="min-width: 0">
              <h3 style="font-size: 14.5px; font-weight: 650" class="truncate">{{ s.topic?.title || '题目' }}</h3>
              <div class="small dim" style="margin-top: 2px">
                {{ DIFFICULTY_LABEL[s.topic?.difficulty] || '不限难度' }}
                · 截止 {{ fmtDate(s.submitDeadline) }}
              </div>
            </div>
            <StatusPill :status="s.status" class="spacer" />
          </div>

          <div class="steps" style="margin-top: 14px; overflow-x: auto">
            <template v-for="(st, i) in [
              { label: '源码', done: s.progress?.sourceUploaded },
              { label: '报告', done: s.progress?.reportUploaded },
              { label: 'AI 评审', done: s.progress?.codeAnalyzed && s.progress?.reportEvaluated },
              { label: '答辩', done: ['DEFENDED', 'GRADED'].includes(s.progress?.defenseStatus) },
              { label: '成绩', done: s.status === 'GRADED' },
            ]" :key="st.label">
              <span v-if="i" class="step__line" />
              <span class="step" :class="st.done ? 'is-done' : ''">
                <span class="step__dot"><Icon v-if="st.done" name="check" :size="10" :stroke="3" /><template v-else>{{ i + 1 }}</template></span>
                {{ st.label }}
              </span>
            </template>
          </div>

          <div class="row" style="margin-top: 14px">
            <span v-if="daysLeft(s.submitDeadline) !== null" class="pill" :class="daysLeft(s.submitDeadline) <= 3 ? 'pill--warn' : 'pill--info'">
              <Icon name="clock" :size="12" />
              {{ daysLeft(s.submitDeadline) >= 0 ? `还有 ${daysLeft(s.submitDeadline)} 天` : '已过截止' }}
            </span>
            <span v-if="s.scores?.finalScore != null" class="pill pill--ok">综合 {{ s.scores.finalScore }}</span>
            <span class="spacer small" style="color: var(--brand)">打开材料 →</span>
          </div>
        </div>
      </article>
    </div>

    <!-- 详情抽屉 -->
    <Drawer v-if="open" :title="detail?.topicTitle || '选题详情'" wide @close="open = false">
      <div v-if="detailLoading" class="skeleton" style="height: 200px" />
      <div v-else-if="detail" style="display: grid; gap: 18px">
        <div class="row row--wrap">
          <StatusPill :status="detail.status" />
          <span class="pill pill--info"><Icon name="clock" :size="12" />截止 {{ fmtDate(detail.submitDeadline) }}</span>
          <span v-if="detail.finalScore != null" class="pill pill--ok">综合 {{ detail.finalScore }}</span>
        </div>

        <div class="steps" style="flex-wrap: wrap; gap: 6px">
          <template v-for="(st, i) in steps" :key="st.key">
            <span v-if="i" class="step__line" />
            <span class="step" :class="st.done ? 'is-done' : ''">
              <span class="step__dot"><Icon v-if="st.done" name="check" :size="10" :stroke="3" /><template v-else>{{ i + 1 }}</template></span>
              {{ st.label }}
            </span>
          </template>
        </div>

        <!-- 上传 -->
        <div class="grid grid--2">
          <div>
            <div class="section-title"><Icon name="code" :size="14" />源码包（.zip / .jar ≤ 100MB）</div>
            <label
              class="dropzone"
              :class="{ 'is-over': false }"
              @dragover.prevent
              @drop="onDrop('SOURCE', $event)"
            >
              <input type="file" :accept="ACCEPT.SOURCE" hidden @change="onPick('SOURCE', $event)" />
              <Icon name="upload" :size="22" />
              <strong style="font-size: 13px">点击或拖拽上传源码</strong>
              <span class="small dim">系统会校验 Zip Slip、可执行文件与解压体积</span>
            </label>
            <div v-if="uploadState.SOURCE.busy" style="margin-top: 8px">
              <div class="bar"><div class="bar__fill" :style="{ width: uploadState.SOURCE.progress + '%' }" /></div>
              <div class="small dim num" style="margin-top: 4px">上传中 {{ uploadState.SOURCE.progress }}%</div>
            </div>
          </div>

          <div>
            <div class="section-title"><Icon name="file" :size="14" />报告（.pdf / .docx ≤ 50MB）</div>
            <label class="dropzone" @dragover.prevent @drop="onDrop('REPORT', $event)">
              <input type="file" :accept="ACCEPT.REPORT" hidden @change="onPick('REPORT', $event)" />
              <Icon name="upload" :size="22" />
              <strong style="font-size: 13px">点击或拖拽上传报告</strong>
              <span class="small dim">与源码同版本一起参与 AI 评审</span>
            </label>
            <div v-if="uploadState.REPORT.busy" style="margin-top: 8px">
              <div class="bar"><div class="bar__fill" :style="{ width: uploadState.REPORT.progress + '%' }" /></div>
              <div class="small dim num" style="margin-top: 4px">上传中 {{ uploadState.REPORT.progress }}%</div>
            </div>
          </div>
        </div>

        <!-- 版本列表 -->
        <div>
          <div class="row section-title">
            <Icon name="layers" :size="14" />已提交材料
            <span class="spacer small dim">每类仅显示最新版本（{{ submissions.length }} 项）</span>
          </div>
          <div v-if="!submissions.length" class="card" style="border-style: dashed">
            <EmptyState icon="upload" title="还没有上传" hint="上传后会自动触发异步解析与 AI 评审" />
          </div>
          <div v-else class="card" style="overflow: hidden">
            <div v-for="sub in submissions" :key="sub.id" class="list-row">
              <span class="pill" :class="sub.type === 'SOURCE' ? 'pill--brand' : 'pill--info'">
                {{ sub.type === 'SOURCE' ? '源码' : '报告' }}
              </span>
              <div style="min-width: 0; flex: 1">
                <div class="list-row__title">最新版本 v{{ sub.version }} · {{ sizeText(sub.size) }}</div>
                <div class="list-row__meta">{{ fmtDate(sub.createdAt) }} · 重新上传会生成新版本并自动评审</div>
              </div>
              <StatusPill :status="sub.status" />
              <div class="row" style="gap: 2px">
                <button class="btn btn--icon btn--sm" title="下载" @click="download(sub)"><Icon name="download" :size="14" /></button>
                <button class="btn btn--icon btn--sm" title="重新评审" @click="reanalyze(sub)"><Icon name="refresh" :size="14" /></button>
                <button class="btn btn--icon btn--sm" title="删除" @click="removeSubmission(sub)"><Icon name="trash" :size="14" /></button>
              </div>
            </div>
          </div>
        </div>

        <!-- 分数 -->
        <div v-if="detail.scoresVisible && detail.scores" class="card card--pad">
          <div class="section-title"><Icon name="chart" :size="14" />成绩构成</div>
          <div class="grid grid--3">
            <div v-for="(v, k) in { 答辩: detail.scores.defense, 源码: detail.scores.code, 报告: detail.scores.report }" :key="k">
              <div class="small dim">{{ k }}</div>
              <div class="num" style="font-size: 19px; font-weight: 650">{{ v ?? '—' }}</div>
            </div>
          </div>
        </div>
        <div v-else class="alert alert--info">
          <Icon name="lock" :size="15" style="flex: none; margin-top: 2px" />
          成绩尚未发布，发布后这里会显示答辩 / 源码 / 报告分项。
        </div>
      </div>

      <template #foot>
        <button v-if="detail && ['SELECTED', 'SUBMITTED'].includes(detail.status)" class="btn btn--danger" @click="cancelSelection">
          退选
        </button>
        <span class="spacer" />
        <button class="btn btn--ghost" @click="open = false">关闭</button>
        <button class="btn btn--primary" @click="refreshDetail()"><Icon name="refresh" :size="14" />刷新状态</button>
      </template>
    </Drawer>
  </div>
</template>
