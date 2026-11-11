<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../Icon.vue'
import StatusPill from '../StatusPill.vue'
import EmptyState from '../EmptyState.vue'
import Modal from '../Modal.vue'
import { api } from '../../api/endpoints'
import { toast, toastError } from '../../stores/toast'

const props = defineProps({
  offeringId: { type: String, required: true },
})

const router = useRouter()

const loading = ref(true)
const busy = ref('')
const keyword = ref('')
const candidates = ref([])
const sessions = ref([])

/* ---------- 发起答辩 ---------- */
const createOpen = ref(false)
const target = ref(null)
const questionCount = ref(3)
const createError = ref('')
const QUESTION_COUNTS = [2, 3, 4, 5]

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
}

function openCreate(row) {
  target.value = row
  questionCount.value = 3
  createError.value = ''
  createOpen.value = true
}

async function submitCreate() {
  if (!target.value) return
  busy.value = 'create'
  createError.value = ''
  try {
    const created = await api.createDefenseSession({
      selectionId: target.value.selectionId,
      questionCount: Number(questionCount.value) || 3,
    })
    toast.ok('答辩会话已创建', 'AI 题目已生成，可进入答辩页开始作答')
    createOpen.value = false
    if (created?.id) {
      router.push(`/defense/${created.id}`)
    } else {
      await load()
    }
  } catch (err) {
    createError.value = err?.message || '创建答辩会话失败'
  } finally {
    busy.value = ''
  }
}

function enter(row) {
  const sessionId = row.sessionId || row.id
  if (sessionId) router.push(`/defense/${sessionId}`)
}

/** 会话列表里有录像的 sessionId 集合（候选学生表按 sessionId 关联）。 */
const videoBySessionId = computed(() => {
  const map = {}
  for (const session of sessions.value) {
    if (session?.id) map[session.id] = Boolean(session.videoAvailable)
  }
  return map
})

/** 教师回放入口：直接跳到答辩页并滚动到「答辩录像」卡片。 */
function openVideo(sessionId) {
  if (sessionId) router.push(`/defense/${sessionId}?focus=video`)
}

function fmt(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

onMounted(load)
</script>

<template>
  <div>
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
          <div class="small dim">选择一名学生发起答辩：AI 按选题与材料出题，学生端录音作答并自动转写</div>
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
              <th>学生</th>
              <th>选题</th>
              <th class="ta-c">选题状态</th>
              <th class="ta-c">答辩状态</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in filteredCandidates" :key="row.selectionId">
              <td>
                <div class="table__primary">{{ row.studentName || '—' }}</div>
                <div class="table__sub mono">{{ row.studentNo || '—' }}</div>
              </td>
              <td class="truncate" style="max-width: 280px">
                {{ row.topicTitle || '—' }}
                <div v-if="row.className" class="table__sub">{{ row.className }}</div>
              </td>
              <td class="ta-c"><StatusPill :status="row.selectionStatus" /></td>
              <td class="ta-c">
                <StatusPill v-if="row.sessionStatus" :status="row.sessionStatus" />
                <span v-else class="small dim">未发起</span>
              </td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 6px">
                  <button
                    v-if="row.sessionId && videoBySessionId[row.sessionId]"
                    class="btn btn--ghost btn--sm"
                    @click="openVideo(row.sessionId)"
                  >
                    <Icon name="play" :size="14" />查看录像
                  </button>
                  <button
                    v-if="row.sessionId"
                    class="btn btn--ghost btn--sm"
                    @click="enter(row)"
                  >
                    <Icon name="arrow-right" :size="14" />进入
                  </button>
                  <button
                    v-else
                    class="btn btn--primary btn--sm"
                    @click="openCreate(row)"
                  >
                    <Icon name="play" :size="14" />发起答辩
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="!loading && sessions.length" class="card">
      <div class="card__head">
        <div>
          <h3>答辩会话</h3>
          <div class="small dim">按创建时间倒序；已评分会话可进入查看评分与转写</div>
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
              <td class="ta-c small dim">{{ fmt(row.startedAt) }}</td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 6px">
                  <button
                    v-if="row.videoAvailable"
                    class="btn btn--ghost btn--sm"
                    @click="openVideo(row.id)"
                  >
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
            为「{{ target?.studentName }} · {{ target?.topicTitle }}」创建答辩会话：AI 会根据选题与学生已提交材料出题，
            学生录音作答后自动转写，教师再统一评分。
          </span>
        </div>
        <div class="field">
          <label>出题数量</label>
          <select v-model="questionCount" class="select">
            <option v-for="n in QUESTION_COUNTS" :key="n" :value="n">{{ n }} 道题</option>
          </select>
          <div class="field__hint">建议 3 道：覆盖设计、代码与报告三方面</div>
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
  </div>
</template>
