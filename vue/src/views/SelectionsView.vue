<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from '../components/Icon.vue'
import PageHeader from '../components/layout/PageHeader.vue'
import StepFlow from '../components/layout/StepFlow.vue'
import { STUDENT_FLOW } from '../config/flow'
import StatusPill from '../components/StatusPill.vue'
import EmptyState from '../components/EmptyState.vue'
import MultiFileUploader from '../components/submission/MultiFileUploader.vue'
import FilePreviewModal from '../components/submission/FilePreviewModal.vue'
import ReviewStatusPanel from '../components/submission/ReviewStatusPanel.vue'
import { api, DIFFICULTY_LABEL } from '../api/endpoints'
import { confirm } from '../stores/confirm'
import { track } from '../stores/tasks'
import { toast, toastError } from '../stores/toast'

const loading = ref(true)
const rows = ref([])

/** 动线定位：还有材料没传就在「提交材料」，都传完了停在「AI 评审」。纯展示。 */
const flowStep = computed(() => {
  const list = rows.value || []
  if (!list.length) return 'topic'
  return list.some((item) => !item.progress?.reportUploaded) ? 'submit' : 'review'
})
const total = ref(0)
const page = ref(1)
const size = 20

const open = ref(false)
const detail = ref(null)
const submissions = ref([])
const detailLoading = ref(false)
const selectedRow = ref(null)
/** 每个提交物的版本历史与当前查看版本：key = submissionId，'' 表示最新 */
const versionMap = ref({})
const activeVersion = ref({})
const previewOpen = ref(false)
const previewSubmissionId = ref('')

/**
 * 评审状态面板的锚点：优先源码提交物，没有源码就退回任意一条。
 * 后端按提交物反查选题，一次返回源码 + 报告两侧状态，所以这里只需要一个 ID。
 */
const reviewAnchorId = computed(() => {
  const source = submissions.value.find((item) => item.type === 'SOURCE')
  return source?.id || submissions.value[0]?.id || ''
})
const previewFile = ref(null)
const defense = ref(null) // 该选题已有的答辩会话（无则 null）

/**
 * 上传策略由后端下发（/meta/submission-policy），前端不写死 9 份 / 100MB。
 * 里面的默认值只是「后端没响应时的兜底」，正常路径永远用后端那份。
 */
const policy = ref({
  SOURCE: { maxFiles: 9, maxFileBytes: 100 * 1024 ** 2, allowedExtensions: ['zip', 'jar'] },
  REPORT: { maxFiles: 9, maxFileBytes: 50 * 1024 ** 2, allowedExtensions: ['pdf', 'docx'] },
})

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

/** 过了截止时间只读：能看、能下载，但不能再加/删文件。 */
const uploadLocked = computed(() => {
  const deadline = detail.value?.submitDeadline
  if (!deadline) return false
  return new Date(deadline).getTime() < Date.now()
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

/**
 * 列表刷新。
 * `silent` 用于「详情触发」的后台刷新（上传完成 / 评审完成）：不切 loading，
 * 避免左目录与右侧详情跟着闪一下。首次进入才走骨架屏。
 */
async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const data = await api.selections({ page: page.value, size })
    rows.value = data?.list || []
    total.value = data?.total || 0
  } catch (err) {
    toastError(err, '加载选题失败')
  } finally {
    if (!silent) loading.value = false
  }
  // 首次进入自动选中第一条：左侧是目录、右侧是详情，右侧不该一直空着
  if (!silent && !detail.value && rows.value.length) void openDetail(rows.value[0])
}

async function openDetail(row) {
  selectedRow.value = row
  open.value = true
  detailLoading.value = true
  submissions.value = []
  defense.value = null
  try {
    // 答辩会话可能还没创建（404），静默降级为「无答辩入口」。
    const [d, subs, def] = await Promise.all([
      api.selectionDetail(row.id),
      api.submissions(row.id),
      api.studentDefense(row.id).catch(() => null),
    ])
    detail.value = d
    submissions.value = await withFiles(subs?.list || [])
    defense.value = def
  } catch (err) {
    toastError(err, '读取选题详情失败')
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!detail.value) return
  try {
    const [d, subs, def] = await Promise.all([
      api.selectionDetail(detail.value.id),
      api.submissions(detail.value.id),
      api.studentDefense(detail.value.id).catch(() => null),
    ])
    detail.value = d
    submissions.value = await withFiles(subs?.list || [])
    defense.value = def
    await load(true)
  } catch {
    // 忽略刷新错误
  }
}

async function loadPolicy() {
  try {
    const p = await api.submissionPolicy()
    policy.value = {
      SOURCE: {
        maxFiles: p?.sourceMaxFiles ?? 9,
        maxFileBytes: p?.sourceMaxFileBytes ?? 100 * 1024 ** 2,
        allowedExtensions: p?.sourceExtensions ?? ['zip', 'jar'],
      },
      REPORT: {
        maxFiles: p?.reportMaxFiles ?? 9,
        maxFileBytes: p?.reportMaxFileBytes ?? 50 * 1024 ** 2,
        allowedExtensions: p?.reportExtensions ?? ['pdf', 'docx'],
      },
    }
  } catch {
    // 策略接口不可用时保留兜底默认值，不阻塞上传
  }
}

/** 列表接口只给「每类最新版本」的摘要，文件清单要逐条拉详情才有。 */
async function withFiles(list) {
  return Promise.all(
    (list || []).map(async (sub) => {
      try {
        const [d, versions] = await Promise.all([
          api.submissionDetail(sub.id),
          api.submissionVersions(sub.id).catch(() => []),
        ])
        versionMap.value = { ...versionMap.value, [sub.id]: versions || [] }
        return {
          ...sub,
          version: d?.version ?? sub.version,
          files: d?.files || [],
          parseResult: d?.parseResult || null,
        }
      } catch {
        return { ...sub, files: [] }
      }
    }),
  )
}

async function removeFile(sub, file) {
  const ok = await confirm({
    title: '删除文件',
    message: `删除「${file.filename}」后不可恢复。删除单个文件不会自动重新评审，需要时请手动点「重新评审」。`,
    danger: true,
    confirmText: '删除',
  })
  if (!ok) return
  try {
    await api.deleteSubmissionFile(sub.id, file.id)
    toast.ok('已删除', file.filename)
    await refreshDetail()
  } catch (err) {
    toastError(err, '删除失败')
  }
}

function downloadFile(sub, file) {
  api.downloadSubmissionFile(sub.id, file.id, file.filename).catch((e) => toastError(e, '下载失败'))
}

function openPreview(sub, file) {
  previewSubmissionId.value = sub.id
  previewFile.value = { id: file.id, filename: file.filename, contentType: file.contentType, size: file.size }
  previewOpen.value = true
}

/** 切换版本只刷新文件清单，不改当前选题上下文，也不产生任何写操作。 */
async function switchVersion(sub, versionId) {
  try {
    const d = await api.submissionDetail(sub.id, versionId || undefined)
    activeVersion.value = { ...activeVersion.value, [sub.id]: versionId || '' }
    const idx = submissions.value.findIndex((item) => item.id === sub.id)
    if (idx >= 0) {
      submissions.value[idx] = {
        ...submissions.value[idx],
        version: d?.version ?? submissions.value[idx].version,
        files: d?.files || [],
        parseResult: d?.parseResult || null,
      }
    }
  } catch (err) {
    toastError(err, '切换版本失败')
  }
}

/** 取某个文件的分文件评审结果（旧数据没有该字段，返回 null 即可）。 */
function fileReview(sub, file) {
  const files = sub?.parseResult?.files
  if (!Array.isArray(files)) return null
  return files.find((item) => item.name === file.filename) || null
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

onMounted(() => {
  load()
  loadPolicy()
})
</script>

<template>
  <div>
    <PageHeader
      title="我的选题"
      description="上传源码与报告 → AI 评审 → 答辩 → 成绩。每个版本都保留历史，可随时回退查看。"
    >
      <template #steps>
        <StepFlow :steps="STUDENT_FLOW" :current="flowStep" dense />
      </template>
      <template #actions>
        <RouterLink to="/courses" class="btn btn--ghost"><Icon name="book" :size="15" />我的课程</RouterLink>
        <RouterLink to="/grades" class="btn btn--primary"><Icon name="chart" :size="15" />查看成绩</RouterLink>
      </template>
    </PageHeader>

    <!--
      左目录 + 右详情常驻：loading 只控制左目录的骨架与右侧的空态，
      绝不能让 loading 把整个 page-split 卸载重挂 —— 否则 refreshDetail() 触发 load()
      会让详情区连同 ReviewStatusPanel / MultiFileUploader 一起销毁重建，
      形成「刷新 → 卸载 → 重挂 → 再刷新」的无限闪烁。
    -->
    <div class="page-split">
      <!-- 左：我的选题目录 -->
      <nav class="subnav" aria-label="我的选题">
        <div class="subnav__group">我的选题 · {{ rows.length }}</div>
        <template v-if="loading">
          <div v-for="i in 3" :key="i" class="skeleton" style="height: 30px; margin-bottom: 6px" />
        </template>
        <template v-else>
          <button
            v-for="s in rows"
            :key="s.id"
            type="button"
            class="subnav__item"
            :class="{ 'is-active': detail?.id === s.id }"
            :title="s.topic?.title || '题目'"
            @click="openDetail(s)"
          >
            <span class="subnav__item-label truncate">{{ s.topic?.title || '题目' }}</span>
            <span class="subnav__item-count">
              <template v-if="s.scores?.finalScore != null">{{ s.scores.finalScore }}</template>
              <template v-else-if="daysLeft(s.submitDeadline) !== null">剩 {{ daysLeft(s.submitDeadline) }}d</template>
            </span>
          </button>
        </template>
      </nav>

      <!-- 右：选中选题的详情 -->
      <div class="page-split__main">
        <div v-if="!loading && !rows.length" class="card card--pad">
          <EmptyState icon="target" title="还没有选题" hint="先到课程里挑一个题目">
            <RouterLink to="/courses" class="btn btn--primary btn--sm" style="margin-top: 10px">去选题</RouterLink>
          </EmptyState>
        </div>
        <div v-else-if="!detail && !detailLoading" class="card card--pad">
          <EmptyState icon="target" title="从左侧选择一条选题" hint="选中后可上传源码与报告、查看 AI 评审与答辩入口" />
        </div>
        <div v-else-if="detailLoading" class="skeleton" style="height: 260px" />
      <div v-else-if="detail" style="display: grid; gap: 18px">
        <div class="row row--wrap">
          <StatusPill :status="detail.status" />
          <span class="pill pill--info"><Icon name="clock" :size="12" />截止 {{ fmtDate(detail.submitDeadline) }}</span>
          <span v-if="detail.finalScore != null" class="pill pill--ok">综合 {{ detail.finalScore }}</span>
          <span v-if="defense" class="pill pill--brand"><Icon name="shield" :size="12" />答辩会话</span>
          <StatusPill v-if="defense" :status="defense.status" />
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

        <!-- 上传：源码与报告各自独立，各 1~9 份，逐文件进度/重试/排序 -->
        <div class="grid grid--2">
          <MultiFileUploader
            :selection-id="detail.id"
            type="SOURCE"
            :policy="policy.SOURCE"
            :disabled="uploadLocked"
            @submitted="refreshDetail"
          />
          <MultiFileUploader
            :selection-id="detail.id"
            type="REPORT"
            :policy="policy.REPORT"
            :disabled="uploadLocked"
            @submitted="refreshDetail"
          />
        </div>

        <!-- 评审状态：源码 / 报告两条异步链路各自独立展示，失败可单独重试 -->
        <ReviewStatusPanel
          v-if="reviewAnchorId"
          :submission-id="reviewAnchorId"
          @finished="refreshDetail"
        />

        <!-- 已提交材料：按源码 / 报告分组，展示每类最新版本下的全部文件 -->
        <div>
          <div class="row section-title">
            <Icon name="layers" :size="14" />已提交材料
            <span class="spacer small dim">每类显示最新版本（{{ submissions.length }} 项）</span>
          </div>
          <div v-if="!submissions.length" class="card" style="border-style: dashed">
            <EmptyState icon="upload" title="还没有上传" hint="上传后会自动触发异步解析与 AI 评审" />
          </div>
          <div v-else class="card" style="overflow: hidden">
            <div v-for="sub in submissions" :key="sub.id" style="border-bottom: 1px solid rgba(0, 0, 0, 0.06)">
              <div class="list-row">
                <span class="pill" :class="sub.type === 'SOURCE' ? 'pill--brand' : 'pill--info'">
                  {{ sub.type === 'SOURCE' ? '源码' : '报告' }}
                </span>
                <div style="min-width: 0; flex: 1">
                  <div class="list-row__title">
                    v{{ sub.version }} · {{ sub.files?.length || 0 }} 个文件 · {{ sizeText(sub.size) }}
                  </div>
                  <div class="list-row__meta">{{ fmtDate(sub.createdAt) }} · 重新提交会生成新版本并自动评审</div>
                </div>
                <select
                  v-if="(versionMap[sub.id] || []).length > 1"
                  class="select"
                  style="width: 168px"
                  :value="activeVersion[sub.id] || ''"
                  @change="switchVersion(sub, $event.target.value)"
                >
                  <option value="">最新 v{{ versionMap[sub.id]?.[0]?.version }}</option>
                  <option v-for="v in versionMap[sub.id]" :key="v.versionId" :value="v.versionId">
                    历史 v{{ v.version }} · {{ v.fileCount }} 个文件
                  </option>
                </select>
                <StatusPill :status="sub.status" />
                <div class="row" style="gap: 2px">
                  <button class="btn btn--icon btn--sm" title="重新评审" @click="reanalyze(sub)">
                    <Icon name="refresh" :size="14" />
                  </button>
                  <button class="btn btn--icon btn--sm" title="删除该版本" @click="removeSubmission(sub)">
                    <Icon name="trash" :size="14" />
                  </button>
                </div>
              </div>
              <div v-if="sub.files?.length" style="padding: 0 12px 10px 12px; display: grid; gap: 6px">
                <div v-for="(file, fi) in sub.files" :key="file.id">
                  <div class="row small" style="gap: 8px; min-width: 0">
                    <span class="num dim" style="width: 16px; text-align: right">{{ fi + 1 }}</span>
                    <span style="min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">
                      {{ file.filename }}
                    </span>
                    <span class="dim num">{{ sizeText(file.size) }}</span>
                    <button class="btn btn--icon btn--sm" title="预览" @click="openPreview(sub, file)">
                      <Icon name="eye" :size="13" />
                    </button>
                    <button class="btn btn--icon btn--sm" title="下载" @click="downloadFile(sub, file)">
                      <Icon name="download" :size="13" />
                    </button>
                    <button class="btn btn--icon btn--sm" title="删除该文件" @click="removeFile(sub, file)">
                      <Icon name="trash" :size="13" />
                    </button>
                  </div>
                  <div
                    v-if="fileReview(sub, file)"
                    class="small"
                    style="margin: 4px 0 0 24px; padding: 6px 10px; background: var(--surface-2); border-radius: 8px"
                  >
                    <div class="row" style="gap: 8px">
                      <strong>AI 分文件评审</strong>
                      <span v-if="fileReview(sub, file).score != null" class="num">得分 {{ fileReview(sub, file).score }}</span>
                      <span v-if="fileReview(sub, file).degraded" class="pill pill--warn small">该份未完整评审</span>
                    </div>
                    <div class="dim" style="margin-top: 3px">{{ fileReview(sub, file).summary || '（无摘要）' }}</div>
                  </div>
                </div>
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

      <div v-if="detail" class="selection-actions">
        <button
          v-if="['SELECTED', 'SUBMITTED'].includes(detail.status)"
          class="btn btn--danger btn--sm"
          @click="cancelSelection"
        >
          退选
        </button>
        <RouterLink v-if="defense" :to="`/defense/${defense.id}`" class="btn btn--primary btn--sm">
          <Icon name="shield" :size="14" />进入答辩
        </RouterLink>
        <span class="spacer" />
        <button class="btn btn--ghost btn--sm" @click="refreshDetail()">
          <Icon name="refresh" :size="14" />刷新状态
        </button>
      </div>
      </div>
    </div>
    <FilePreviewModal
      v-if="previewOpen && previewFile"
      :submission-id="previewSubmissionId"
      :file="previewFile"
      @close="previewOpen = false"
    />

  </div>
</template>

<style scoped>
/* 详情底部操作条：与正文用分隔线隔开，主操作靠左、次操作靠右 */
.selection-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.selection-actions .spacer {
  flex: 1;
}
</style>
