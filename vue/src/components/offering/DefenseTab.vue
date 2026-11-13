<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../Icon.vue'
import StatusPill from '../StatusPill.vue'
import EmptyState from '../EmptyState.vue'
import Modal from '../Modal.vue'
import { api } from '../../api/endpoints'
import { toast, toastError } from '../../stores/toast'
import TabHead from '../layout/TabHead.vue'

const props = defineProps({
  offeringId: { type: String, required: true },
})

const router = useRouter()

const loading = ref(true)
const busy = ref('')
const keyword = ref('')
const candidates = ref([])
const sessions = ref([])

/* ---------- 题数规则（后端唯一来源，前端只读来预览） ---------- */
const rule = ref(null)

/* ---------- 违规告警（后端只记录，教师才是裁决者） ---------- */
const violations = ref([])
const violationOpen = ref(false)
const deciding = ref('')
const VIOLATION_LABEL = {
  TAB_SWITCH: '切换标签页 / 窗口',
  DEVTOOLS: '打开开发者工具',
  PASTE_PATTERN: '粘贴外部内容',
  COPY_BLOCK: '尝试复制源码',
  SHORTCUT_BLOCK: '拦截快捷键',
  RESUME: '教师恢复答辩',
  UPHOLD: '教师确认终止',
}

const violatedSessions = computed(() => violations.value.filter((row) => row.sessionStatus === 'VIOLATED'))
const violationBadge = computed(() => violations.value.reduce((sum, row) => sum + row.majorCount, 0))
const violationBySession = computed(() => {
  const map = {}
  for (const row of violations.value) map[row.sessionId] = row
  return map
})
const needsDecision = computed(() => violatedSessions.value.length)

function lastEvent(row) {
  return row.latestAt ? fmt(row.latestAt) : '—'
}

function typeText(type) {
  return VIOLATION_LABEL[type] || type
}

async function loadViolations() {
  try {
    const rows = await api.teacherDefenseViolations(props.offeringId)
    violations.value = Array.isArray(rows) ? rows : []
  } catch {
    violations.value = []
  }
}

async function decide(row, action) {
  if (deciding.value) return
  deciding.value = `${row.sessionId}:${action}`
  try {
    if (action === 'resume') {
      await api.resumeDefense(row.sessionId)
      toast.ok('已恢复答辩', `${row.studentName || '该学生'} 可以继续作答，违规次数重新计算`)
    } else {
      await api.upholdDefense(row.sessionId)
      toast.ok('已确认终止', '会话已转为「已完成」，成绩留空，可按需 AI 评分或改分')
    }
    await Promise.all([load(), loadViolations()])
  } catch (err) {
    toastError(err, action === 'resume' ? '恢复答辩失败' : '确认终止失败')
  } finally {
    deciding.value = ''
  }
}

/* ---------- 列表 ---------- */
const filteredCandidates = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return candidates.value
  return candidates.value.filter((row) =>
    `${row.studentName || ''} ${row.studentNo || ''} ${row.topicTitle || ''}`.toLowerCase().includes(q),
  )
})

const startedCount = computed(() => candidates.value.filter((row) => row.sessionId).length)
const liveCount = computed(() => sessions.value.filter((row) => ['READY', 'IN_PROGRESS'].includes(row.status)).length)
const evaluatedCount = computed(() => sessions.value.filter((row) => row.status === 'EVALUATED').length)
const blockedCount = computed(() => candidates.value.filter((row) => !row.eligible).length)

async function load() {
  loading.value = true
  try {
    const [candidateList, sessionList] = await Promise.all([
      api.teacherDefenseCandidates(props.offeringId),
      api.teacherDefenseSessions(props.offeringId),
    ])
    candidates.value = Array.isArray(candidateList) ? candidateList : candidateList?.list || []
    sessions.value = Array.isArray(sessionList) ? sessionList : sessionList?.list || []
  } catch (err) {
    toastError(err, '加载答辩数据失败')
  } finally {
    loading.value = false
  }
  await loadViolations()
}

async function loadRule() {
  try {
    rule.value = await api.defenseQuestionRule()
  } catch {
    // 取不到规则不影响发起（后端仍会按配置算题数），只是少了预览
    rule.value = null
  }
}

/* ---------- 多选 ---------- */
const selected = ref([])

const selectableRows = computed(() => filteredCandidates.value.filter((row) => row.eligible))
const selectedRows = computed(() =>
  candidates.value.filter((row) => selected.value.includes(row.selectionId)),
)
const selectedCount = computed(() => selectedRows.value.length)
const projectedQuestions = computed(() =>
  selectedRows.value.reduce((sum, row) => sum + (row.plannedQuestionCount || 0), 0),
)
const allSelected = computed(
  () => selectableRows.value.length > 0 && selectableRows.value.every((row) => selected.value.includes(row.selectionId)),
)

/** 单批上限来自后端配置，前端只做提前拦截，真正的校验仍在 submitBatch 里。 */
const batchLimit = computed(() => rule.value?.batchMaxStudents || 50)
const overLimit = computed(() => selectedCount.value > batchLimit.value)

function isSelected(row) {
  return selected.value.includes(row.selectionId)
}

function toggleSelect(row) {
  if (!row.eligible) return
  const index = selected.value.indexOf(row.selectionId)
  if (index >= 0) {
    selected.value.splice(index, 1)
    return
  }
  if (selected.value.length >= batchLimit.value) {
    toast.info('已达单批上限', `一次最多发起 ${batchLimit.value} 人，请分批操作`)
    return
  }
  selected.value.push(row.selectionId)
}

function toggleAll() {
  if (allSelected.value) {
    const visible = new Set(selectableRows.value.map((row) => row.selectionId))
    selected.value = selected.value.filter((id) => !visible.has(id))
    return
  }
  const merged = new Set(selected.value)
  for (const row of selectableRows.value) merged.add(row.selectionId)
  const list = [...merged]
  if (list.length > batchLimit.value) {
    toast.info('已达单批上限', `一次最多发起 ${batchLimit.value} 人，已按表格顺序取前 ${batchLimit.value} 人`)
    selected.value = list.slice(0, batchLimit.value)
    return
  }
  selected.value = list
}

function clearSelection() {
  selected.value = []
}

/** 按档位分组，确认弹窗里给教师一个「题量从哪来」的直观解释。 */
const selectionSummary = computed(() => {
  const map = new Map()
  for (const row of selectedRows.value) {
    const key = `${row.codeScore ?? '—'}|${row.plannedQuestionCount}`
    const item = map.get(key) || {
      codeScore: row.codeScore,
      count: row.plannedQuestionCount,
      students: 0,
    }
    item.students += 1
    map.set(key, item)
  }
  return [...map.values()].sort((a, b) => (b.codeScore ?? -1) - (a.codeScore ?? -1))
})

function tierOf(score) {
  if (!rule.value?.tiers) return '—'
  const value = Number(score)
  const matched = rule.value.tiers.find((tier) => value >= tier.min && value <= tier.max)
  return matched ? matched.code : '—'
}

/* ---------- 发起答辩（单个） ---------- */
const createOpen = ref(false)
const target = ref(null)
const createError = ref('')
const manual = ref({ enabled: false, count: null, reason: '' })

function openCreate(row) {
  target.value = row
  manual.value = { enabled: false, count: row.plannedQuestionCount || rule.value?.baseCount || 6, reason: '' }
  createError.value = ''
  createOpen.value = true
}

async function submitCreate() {
  if (!target.value) return
  if (manual.value.enabled && (!manual.value.count || !manual.value.reason.trim())) {
    createError.value = '手动指定题数必须填写理由'
    return
  }
  busy.value = 'create'
  createError.value = ''
  try {
    const payload = { selectionId: target.value.selectionId }
    if (manual.value.enabled) {
      payload.questionCount = Number(manual.value.count)
      payload.overrideReason = manual.value.reason.trim()
    }
    const created = await api.createDefenseSession(payload)
    toast.ok('答辩会话已创建', `已生成 ${created?.questionCount ?? target.value.plannedQuestionCount} 道题 + 1 道加分题`)
    createOpen.value = false
    if (created?.id) router.push(`/defense/${created.id}`)
    else await load()
  } catch (err) {
    createError.value = err?.message || '创建答辩会话失败'
  } finally {
    busy.value = ''
  }
}

/* ---------- 批量发起 ---------- */
const batch = ref({ open: false, running: false, data: null, error: '', timer: null })
const batchConfirm = ref(false)

function openBatch() {
  if (!selectedCount.value) return
  batchConfirm.value = true
}

async function submitBatch() {
  if (overLimit.value) {
    toast.info('已超过单批上限', `请分批发起，单批最多 ${batchLimit.value} 人`)
    return
  }
  batchConfirm.value = false
  batch.value = { open: true, running: true, data: null, error: '', timer: null }
  try {
    const data = await api.batchDefenseSessions({
      offeringId: props.offeringId,
      selectionIds: selected.value,
    })
    batch.value.data = data
    await pollBatch(data.batchId, 0)
  } catch (err) {
    batch.value.running = false
    batch.value.error = err?.message || '批量发起失败'
  }
}

/**
 * 轮询批量进度。
 *
 * <p>2s 起步、失败退避到 10s，只到批次进入终态才停；页面隐藏时跳过一轮，
 * 避免教师切走后还在后台高频打接口。</p>
 */
async function pollBatch(batchId, attempt) {
  if (typeof document !== 'undefined' && document.hidden) {
    batch.value.timer = setTimeout(() => pollBatch(batchId, attempt), 5000)
    return
  }
  try {
    const data = await api.batchDefenseDetail(batchId)
    batch.value.data = data
    if (data.status === 'RUNNING') {
      const delay = Math.min(10000, 2000 * 2 ** Math.min(attempt, 3))
      batch.value.timer = setTimeout(() => pollBatch(batchId, attempt + 1), delay)
      return
    }
    batch.value.running = false
    await load()
    clearSelection()
    if (data.failed > 0) {
      toast.info('批量发起完成', `${data.succeeded} 成功 / ${data.skipped} 跳过 / ${data.failed} 失败`)
    } else {
      toast.ok('批量发起完成', `${data.succeeded} 人已创建答辩，${data.skipped} 人跳过`)
    }
  } catch (err) {
    batch.value.running = false
    batch.value.error = err?.message || '读取批量进度失败'
  }
}

const failedRows = computed(() => (batch.value.data?.items || []).filter((row) => row.status === 'FAILED'))

/** 失败重试：只把失败的那几条重新提交，已成功的不重复创建。 */
async function retryFailed() {
  const ids = failedRows.value.map((row) => row.selectionId)
  if (!ids.length) return
  selected.value = ids
  await submitBatch()
}

function closeBatch() {
  if (batch.value.timer) clearTimeout(batch.value.timer)
  batch.value = { open: false, running: false, data: null, error: '', timer: null }
}

function enter(row) {
  const sessionId = row.sessionId || row.id
  if (sessionId) router.push(`/defense/${sessionId}`)
}

const videoBySessionId = computed(() => {
  const map = {}
  for (const session of sessions.value) {
    if (session?.id) map[session.id] = Boolean(session.videoAvailable)
  }
  return map
})

function openVideo(sessionId) {
  if (sessionId) router.push(`/defense/${sessionId}?focus=video`)
}

function fmt(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

let pollTimer = null

onMounted(() => {
  void load()
  void loadRule()
  pollTimer = setInterval(() => {
    if (typeof document !== 'undefined' && document.hidden) return
    void loadViolations()
  }, 30_000)
})

onBeforeUnmount(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  if (batch.value.timer) clearTimeout(batch.value.timer)
})
</script>

<template>
  <div>
    <TabHead
      title="答辩"
      description="勾选学生后批量发起；已有进行中的会话会自动跳过，不会重复创建。题目数量由源码分档得出。"
    />

    <!-- 违规告警：只提示不自动处理，裁决权在教师 -->
    <div v-if="violationBadge > 0 || violatedSessions.length" class="alert alert--danger" style="margin-bottom: 14px">
      <Icon name="alert" :size="16" style="flex: none; margin-top: 2px" />
      <div style="min-width: 0; flex: 1">
        <strong>检测到答辩违规行为</strong>
        <div class="small" style="margin-top: 2px">
          {{ violations.length }} 个会话 · 累计 {{ violationBadge }} 次有效违规<template v-if="needsDecision">
            · <strong>{{ needsDecision }} 个会话已暂停，等待你确认</strong></template
          >
        </div>
      </div>
      <button class="btn btn--danger btn--sm" style="flex: none" @click="violationOpen = true">
        查看详情
      </button>
    </div>

    <div class="grid grid--4" style="margin-bottom: 14px">
      <div class="kpi" style="padding: 13px 16px">
        <div class="kpi__label">可答辩选题</div>
        <div class="kpi__value num" style="font-size: 21px">{{ candidates.length }}</div>
      </div>
      <div class="kpi" style="padding: 13px 16px">
        <div class="kpi__label">已发起会话</div>
        <div class="kpi__value num" style="font-size: 21px">{{ startedCount }}</div>
      </div>
      <div class="kpi" style="padding: 13px 16px">
        <div class="kpi__label">进行中</div>
        <div class="kpi__value num" style="font-size: 21px">{{ liveCount }}</div>
      </div>
      <div class="kpi" style="padding: 13px 16px">
        <div class="kpi__label">已评分</div>
        <div class="kpi__value num" style="font-size: 21px">{{ evaluatedCount }}</div>
      </div>
    </div>

    <div class="card" style="margin-bottom: 16px">
      <div class="card__head">
        <div>
          <h3>可答辩学生</h3>
          <div class="small dim">
            勾选后批量发起：题数按源码评分分档，同档同量；不可发起的行会标注原因
            <template v-if="blockedCount"> · 当前 {{ blockedCount }} 人不可发起</template>
          </div>
        </div>
        <div class="row" style="margin-left: auto">
          <input v-model="keyword" class="input" style="width: 190px" placeholder="搜索学生 / 题目" />
          <button class="btn btn--ghost btn--sm" :disabled="loading" @click="load">
            <Icon name="refresh" :size="14" />刷新
          </button>
        </div>
      </div>
      <div v-if="loading" class="card__body"><div class="skeleton" style="height: 180px" /></div>
      <EmptyState
        v-else-if="!candidates.length"
        icon="shield"
        title="还没有可答辩的选题"
        hint="学生完成选题后，这里会出现可发起答辩的学生"
      />
      <EmptyState
        v-else-if="!filteredCandidates.length"
        icon="search"
        title="没有匹配的学生"
        hint="换个关键词试试"
      />
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 34px">
                <input type="checkbox" :checked="allSelected" :disabled="!selectableRows.length" @change="toggleAll" />
              </th>
              <th>学生</th>
              <th>选题</th>
              <th class="ta-c">选题状态</th>
              <th class="ta-c">源码 / 题数</th>
              <th class="ta-c">答辩状态</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in filteredCandidates" :key="row.selectionId" :class="{ 'row--muted': !row.eligible }">
              <td>
                <input
                  type="checkbox"
                  :checked="isSelected(row)"
                  :disabled="!row.eligible"
                  :title="row.ineligibleReason || ''"
                  @change="toggleSelect(row)"
                />
              </td>
              <td>
                <div class="table__primary">{{ row.studentName || '—' }}</div>
                <div class="table__sub mono">{{ row.studentNo || '—' }}</div>
              </td>
              <td class="truncate" style="max-width: 250px">
                {{ row.topicTitle || '—' }}
                <div v-if="row.className" class="table__sub">{{ row.className }}</div>
              </td>
              <td class="ta-c"><StatusPill :status="row.selectionStatus" /></td>
              <td class="ta-c">
                <div class="small mono">
                  源码 {{ row.codeScore ?? '—' }} · {{ tierOf(row.codeScore) }}
                </div>
                <div class="small dim">
                  {{ row.plannedQuestionCount }} 题<template v-if="row.bonusCount"> + {{ row.bonusCount }} 加分</template>
                </div>
              </td>
              <td class="ta-c">
                <StatusPill v-if="row.sessionStatus" :status="row.sessionStatus" />
                <span v-else class="small dim">未发起</span>
                <div v-if="!row.eligible" class="small" style="margin-top: 4px; color: var(--warn)">
                  {{ row.ineligibleReason }}
                </div>
                <div v-if="row.sessionId && violationBySession[row.sessionId]?.majorCount" class="small" style="margin-top: 4px">
                  <span class="pill pill--danger">
                    <Icon name="alert" :size="11" />违规 {{ violationBySession[row.sessionId].majorCount }}
                  </span>
                </div>
              </td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 6px">
                  <button
                    v-if="row.sessionId && videoBySessionId[row.sessionId]"
                    class="btn btn--ghost btn--sm"
                    @click="openVideo(row.sessionId)"
                  >
                    <Icon name="play" :size="14" />录像
                  </button>
                  <button v-if="row.sessionId" class="btn btn--ghost btn--sm" @click="enter(row)">
                    <Icon name="arrow-right" :size="14" />进入
                  </button>
                  <button v-else class="btn btn--primary btn--sm" :disabled="!row.eligible" @click="openCreate(row)">
                    <Icon name="play" :size="14" />发起答辩
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 批量操作条：固定底部，勾选后出现 -->
    <div v-if="selectedCount" class="batch-bar">
      <div class="row" style="gap: 10px; min-width: 0">
        <strong>已选 {{ selectedCount }} 人</strong>
        <span class="small dim">预计 {{ projectedQuestions }} 道计分题（另含加分题，不计入总分）</span>
        <span v-if="overLimit" class="pill pill--danger">超过单批上限 {{ batchLimit }} 人</span>
      </div>
      <span class="spacer" />
      <button class="btn btn--ghost btn--sm" @click="clearSelection">清空</button>
      <button class="btn btn--primary btn--sm" :disabled="batch.running || overLimit" @click="openBatch">
        <Icon name="play" :size="14" />批量发起答辩
      </button>
    </div>

    <div v-if="!loading && sessions.length" class="card">
      <div class="card__head">
        <div>
          <h3>答辩会话</h3>
          <div class="small dim">按创建时间倒序；已评分会话可进入查看三原则判定与加分题结果</div>
        </div>
      </div>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>学生</th>
              <th class="ta-c">状态</th>
              <th class="ta-c">作答进度</th>
              <th class="ta-c">得分</th>
              <th class="ta-c">三原则判定</th>
              <th class="ta-c">开始时间</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in sessions" :key="row.id">
              <td>
                <div class="table__primary">{{ row.studentName || '—' }}</div>
                <div class="table__sub mono">{{ row.studentNo || '—' }}</div>
              </td>
              <td class="ta-c">
                <StatusPill :status="row.status" />
                <div v-if="row.videoAvailable" class="small dim" style="margin-top: 4px">
                  <Icon name="eye" :size="12" /> 已录像
                </div>
              </td>
              <td class="ta-c num mono">{{ row.answeredCount ?? 0 }} / {{ row.questionCount ?? 0 }}</td>
              <td class="ta-c num mono" style="font-weight: 650">{{ row.score ?? '—' }}</td>
              <td class="ta-c">
                <span v-if="row.aiDependent === true" class="pill pill--danger">完全依赖 AI</span>
                <span v-else-if="row.aiDependent === false" class="pill pill--ok">未判定为依赖</span>
                <span v-else class="small dim">未判定</span>
                <div v-if="row.aiDependentOverridden" class="small dim" style="margin-top: 4px">教师已覆盖</div>
              </td>
              <td class="ta-c small dim">{{ fmt(row.startedAt) }}</td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 6px">
                  <button v-if="row.videoAvailable" class="btn btn--ghost btn--sm" @click="openVideo(row.id)">
                    <Icon name="play" :size="14" />查看录像
                  </button>
                  <button class="btn btn--ghost btn--sm" @click="enter(row)">
                    <Icon name="arrow-right" :size="14" />进入
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <Modal v-if="createOpen" title="发起答辩" @close="createOpen = false">
      <div style="display: grid; gap: 12px">
        <div class="alert alert--info">
          <Icon name="shield" :size="15" style="flex: none; margin-top: 2px" />
          <span>
            为「{{ target?.studentName }} · {{ target?.topicTitle }}」创建答辩会话：
            AI 按三原则（复述设计 / 解释修改理由 / 现场改动）出题，另加 1 道不计分的加分题。
          </span>
        </div>

        <div class="rule-preview">
          <div class="row" style="gap: 8px">
            <strong>题数预览</strong>
            <span class="pill pill--brand">{{ tierOf(target?.codeScore) }}</span>
            <span class="spacer" />
            <span class="num mono">{{ target?.plannedQuestionCount }} 题</span>
          </div>
          <div class="small dim" style="margin-top: 4px">
            源码评分 {{ target?.codeScore ?? '未评（按 0 分档）' }} ·
            基础 {{ rule?.baseCount ?? 6 }} 题 + 档位加题，上限 {{ rule?.maxCount ?? 10 }} 题
            <template v-if="rule"> · 加分题 {{ rule.bonusCount }} 道（{{ rule.bonusMaxAnswerSeconds }}s，可不计入总分）</template>
          </div>
        </div>

        <label class="row" style="gap: 8px">
          <input v-model="manual.enabled" type="checkbox" />
          <span>手动指定题数（会记录理由，不追溯历史会话）</span>
        </label>
        <div v-if="manual.enabled" class="grid grid--2">
          <div class="field">
            <label>题目数量</label>
            <input v-model.number="manual.count" class="input" type="number" min="1" max="12" />
          </div>
          <div class="field">
            <label>覆盖理由（必填）</label>
            <input v-model="manual.reason" class="input" placeholder="如：该生需复赛加试" />
          </div>
        </div>

        <div v-if="createError" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ createError }}
        </div>
      </div>
      <template #foot>
        <button class="btn btn--ghost" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy === 'create'" @click="submitCreate">
          <span v-if="busy === 'create'" class="spinner" />生成题目并创建
        </button>
      </template>
    </Modal>

    <Modal v-if="batchConfirm" title="批量发起答辩" @close="batchConfirm = false">
      <div style="display: grid; gap: 12px">
        <div class="alert alert--info">
          <Icon name="shield" :size="15" style="flex: none; margin-top: 2px" />
          <span>
            将为学生逐个调用 AI 出题：已有进行中答辩会被跳过、评审未完成的不发起。
            单个学生失败不影响其余人，可在结果里单独重试。
          </span>
        </div>
        <div class="row row--wrap" style="gap: 10px">
          <span class="pill pill--brand">{{ selectedCount }} 人</span>
          <span class="pill">{{ projectedQuestions }} 道计分题</span>
          <span class="pill">另含 {{ selectedCount }} 道加分题（不计分）</span>
        </div>
        <div class="table-wrap" style="max-height: 240px; overflow: auto">
          <table class="table">
            <thead>
              <tr>
                <th>源码评分</th>
                <th class="ta-c">档位</th>
                <th class="ta-c">题数</th>
                <th class="ta-c">人数</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in selectionSummary" :key="`${item.codeScore}-${item.count}`">
                <td class="num mono">{{ item.codeScore ?? '未评' }}</td>
                <td class="ta-c"><span class="tag">{{ tierOf(item.codeScore) }}</span></td>
                <td class="ta-c num mono">{{ item.count }}</td>
                <td class="ta-c num mono">{{ item.students }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="small dim">
          <template v-if="rule">
            并发 {{ rule.batchConcurrency || 2 }} 路执行（单批最多 {{ rule.batchMaxStudents || 50 }} 人），
          </template>
          题数规则由后端统一计算，前端预览与落地结果一致。
        </div>
      </div>
      <template #foot>
        <button class="btn btn--ghost" @click="batchConfirm = false">取消</button>
        <button class="btn btn--primary" :disabled="overLimit" @click="submitBatch">确认发起</button>
      </template>
    </Modal>

    <Modal v-if="batch.open" title="批量发起进度" size="lg" @close="closeBatch">
      <div style="display: grid; gap: 12px">
        <div class="row row--wrap" style="gap: 10px">
          <span class="pill" :class="batch.running ? 'pill--info' : 'pill--ok'">
            {{ batch.running ? '执行中' : '已完成' }}
          </span>
          <span class="pill">成功 {{ batch.data?.succeeded ?? 0 }}</span>
          <span class="pill">跳过 {{ batch.data?.skipped ?? 0 }}</span>
          <span class="pill" :class="(batch.data?.failed ?? 0) ? 'pill--danger' : ''">
            失败 {{ batch.data?.failed ?? 0 }}
          </span>
          <span class="spacer" />
          <span class="small dim">共 {{ batch.data?.total ?? selectedCount }} 条</span>
        </div>

        <div v-if="batch.error" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ batch.error }}
        </div>

        <div class="table-wrap" style="max-height: 340px; overflow: auto">
          <table class="table">
            <thead>
              <tr>
                <th>学生</th>
                <th class="ta-c">结果</th>
                <th class="ta-c">题数</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in batch.data?.items || []" :key="row.selectionId">
                <td>
                  <div class="table__primary">{{ row.studentName || '—' }}</div>
                  <div class="table__sub mono">{{ row.studentNo }}</div>
                </td>
                <td class="ta-c">
                  <span
                    class="pill"
                    :class="row.status === 'SUCCEEDED' ? 'pill--ok' : row.status === 'SKIPPED' ? 'pill--warn' : 'pill--danger'"
                  >
                    {{ row.status === 'SUCCEEDED' ? '成功' : row.status === 'SKIPPED' ? '跳过' : '失败' }}
                  </span>
                </td>
                <td class="ta-c num mono">{{ row.questionCount ?? '—' }}</td>
                <td class="small">{{ row.reason || '—' }}</td>
              </tr>
              <tr v-if="batch.running && !(batch.data?.items || []).length">
                <td colspan="4" class="small dim">正在逐个调用 AI 出题，请稍候…</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <template #foot>
        <button v-if="failedRows.length && !batch.running" class="btn btn--ghost" @click="retryFailed">
          <Icon name="refresh" :size="14" />重试失败 {{ failedRows.length }} 条
        </button>
        <button class="btn btn--primary" :disabled="batch.running" @click="closeBatch">关闭</button>
      </template>
    </Modal>

    <Modal v-if="violationOpen" title="答辩违规详情" size="lg" @close="violationOpen = false">
      <EmptyState
        v-if="!violations.length"
        icon="shield"
        title="暂无违规记录"
        hint="学生答辩期间切屏、打开开发者工具等行为会实时记录在这里"
      />
      <div v-else style="display: grid; gap: 16px">
        <div class="alert alert--info" style="align-items: flex-start">
          <Icon name="shield" :size="15" style="flex: none; margin-top: 2px" />
          <span>
            这些是前端上报的<strong>辅助证据</strong>，不是最终结论：系统弹窗、投屏、多显示器都可能造成误判。
            已暂停的答辩由你决定——误报就恢复，确认作弊就终止（终止后成绩留空，可正常评分或改分）。
          </span>
        </div>

        <div v-for="row in violations" :key="row.sessionId" class="violation-row">
          <div class="row row--wrap" style="gap: 8px">
            <strong>{{ row.studentName || '—' }}</strong>
            <span class="small dim mono">{{ row.studentNo }}</span>
            <StatusPill :status="row.sessionStatus" />
            <span class="spacer" />
            <span class="pill" :class="row.count >= row.threshold ? 'pill--danger' : 'pill--warn'">
              当前累计 {{ row.count }} / {{ row.threshold }}
            </span>
          </div>
          <div class="small dim" style="margin-top: 4px">
            会话 <span class="mono">{{ row.sessionId }}</span> · 最近一次 {{ lastEvent(row) }} ·
            共 {{ row.majorCount }} 次有效 + {{ row.minorCount }} 次仅留证
          </div>

          <ul class="violation-list">
            <li v-for="(item, index) in row.items" :key="`${row.sessionId}-${index}`">
              <span class="mono small dim">{{ fmt(item.occurredAt) }}</span>
              <span>{{ typeText(item.type) }}</span>
              <span v-if="item.severity === 'MINOR'" class="pill">仅留证</span>
              <span v-if="item.ip" class="small dim mono">{{ item.ip }}</span>
            </li>
          </ul>

          <div v-if="row.sessionStatus === 'VIOLATED'" class="row row--wrap" style="margin-top: 10px">
            <span class="small" style="color: var(--warn)">
              建议：先看录像与转写核对，再决定恢复或终止
            </span>
            <span class="spacer" />
            <button class="btn btn--ghost btn--sm" :disabled="Boolean(deciding)" @click="decide(row, 'resume')">
              <span v-if="deciding === `${row.sessionId}:resume`" class="spinner" />
              判定误报，恢复答辩
            </button>
            <button class="btn btn--danger btn--sm" :disabled="Boolean(deciding)" @click="decide(row, 'uphold')">
              <span v-if="deciding === `${row.sessionId}:uphold`" class="spinner" />
              确认违规，终止答辩
            </button>
          </div>
          <div v-else-if="row.sessionStatus === 'COMPLETED'" class="small dim" style="margin-top: 8px">
            已确认终止，可进入会话完成评分。
          </div>
        </div>
      </div>
      <template #foot>
        <button class="btn btn--ghost" @click="violationOpen = false">关闭</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.violation-row {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: var(--surface-2);
}

.violation-list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 4px;
  max-height: 190px;
  overflow: auto;
}

.violation-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 6px;
  border-radius: 6px;
  background: var(--surface);
  font-size: 12.5px;
}

.row--muted {
  opacity: 0.72;
}

.batch-bar {
  position: sticky;
  bottom: 12px;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: var(--surface);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}

.rule-preview {
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: var(--surface-2);
}
</style>
