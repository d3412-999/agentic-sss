<script setup>
import { computed, onMounted, ref } from 'vue'
import Icon from '../Icon.vue'
import EmptyState from '../EmptyState.vue'
import Modal from '../Modal.vue'
import { api } from '../../api/endpoints'
import { confirm } from '../../stores/confirm'
import { toast, toastError } from '../../stores/toast'

const props = defineProps({
  offeringId: { type: String, required: true },
})

const loading = ref(true)
const busy = ref('')
const candidates = ref([])
const works = ref([])

const addOpen = ref(false)
const target = ref(null)
const form = ref({ note: '', tags: '' })
const addError = ref('')

const addedCount = computed(() => works.value.length)
const indexedCount = computed(() => works.value.filter((w) => w.indexStatus === 'INDEXED').length)

async function load() {
  loading.value = true
  try {
    const [candidateList, workList] = await Promise.all([
      api.excellentWorkCandidates(props.offeringId),
      api.excellentWorks(props.offeringId),
    ])
    candidates.value = candidateList || []
    works.value = workList || []
  } catch (err) {
    toastError(err, '加载优秀作品失败')
  } finally {
    loading.value = false
  }
}

function openAdd(row) {
  addError.value = ''
  target.value = row
  form.value = { note: '', tags: '' }
  addOpen.value = true
}

async function submitAdd() {
  if (!target.value) return
  busy.value = 'add'
  addError.value = ''
  try {
    await api.addExcellentWork(props.offeringId, {
      selectionId: target.value.selectionId,
      note: form.value.note.trim() || null,
      tags: form.value.tags.trim() || null,
    })
    toast.ok('已加入模板库', '报告正文已索引，供后续报告评审参考')
    addOpen.value = false
    await load()
  } catch (err) {
    addError.value = err.message
  } finally {
    busy.value = ''
  }
}

async function remove(work) {
  if (!(await confirm({
    title: '移出模板库',
    message: `把「${work.studentName} · ${work.topicTitle}」从优秀作品模板库移除？`,
    danger: true,
    confirmText: '确认移除',
  }))) return
  busy.value = `del-${work.workId}`
  try {
    await api.deleteExcellentWork(props.offeringId, work.workId)
    toast.ok('已移出模板库', work.title)
    await load()
  } catch (err) {
    toastError(err, '移除失败')
  } finally {
    busy.value = ''
  }
}

async function retry(work) {
  busy.value = `retry-${work.workId}`
  try {
    await api.reindexExcellentWork(props.offeringId, work.workId)
    toast.ok('已重新索引', work.title)
    await load()
  } catch (err) {
    toastError(err, '重新索引失败')
  } finally {
    busy.value = ''
  }
}

function downloadReport(row) {
  if (!row.reportSubmissionId) return
  api.downloadSubmission(row.reportSubmissionId, `报告-${row.studentNo || row.studentName}.pdf`)
}

function statusClass(status) {
  if (status === 'INDEXED') return 'pill--ok'
  if (status === 'FAILED') return 'pill--danger'
  return 'pill--warn'
}

function statusText(status) {
  return { INDEXED: '已入库', FAILED: '索引失败', PENDING: '索引中' }[status] || status || '未入库'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="grid grid--4" style="margin-bottom: 14px">
      <div class="kpi" style="padding: 13px 16px">
        <div class="kpi__label">候选作品</div>
        <div class="kpi__value num" style="font-size: 21px">{{ candidates.length }}</div>
      </div>
      <div class="kpi" style="padding: 13px 16px">
        <div class="kpi__label">已加入模板库</div>
        <div class="kpi__value num" style="font-size: 21px">{{ addedCount }}</div>
      </div>
      <div class="kpi" style="padding: 13px 16px">
        <div class="kpi__label">索引成功</div>
        <div class="kpi__value num" style="font-size: 21px">{{ indexedCount }}</div>
      </div>
      <div class="kpi" style="padding: 13px 16px">
        <div class="kpi__label">用途</div>
        <div class="small dim" style="margin-top: 6px">报告评审时作为 RAG 参考，不直接改分</div>
      </div>
    </div>

    <div class="card" style="margin-bottom: 16px">
      <div class="card__head">
        <div>
          <div class="card__title">候选作品</div>
          <div class="small dim">按报告得分排序，教师自行判断哪些作品值得作为模板</div>
        </div>
        <button class="btn btn--ghost btn--sm" :disabled="loading" @click="load">
          <Icon name="refresh" :size="14" />刷新
        </button>
      </div>
      <div v-if="loading" class="card__body"><div class="skeleton" style="height: 180px" /></div>
      <EmptyState
        v-else-if="!candidates.length"
        icon="file"
        title="还没有候选作品"
        hint="学生上传报告并完成评审后，这里会出现可选作品"
      />
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>学生</th>
              <th>题目</th>
              <th class="ta-c">报告得分</th>
              <th class="ta-c">页数</th>
              <th class="ta-c">模板库</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in candidates" :key="row.selectionId">
              <td>
                <div class="table__primary">{{ row.studentName || '—' }}</div>
                <div class="table__sub mono">{{ row.studentNo || '—' }}</div>
              </td>
              <td class="truncate" style="max-width: 260px">{{ row.topicTitle || '—' }}</td>
              <td class="ta-c num mono" style="font-weight: 650">{{ row.reportScore ?? '—' }}</td>
              <td class="ta-c num mono">{{ row.pageCount ?? '—' }}</td>
              <td class="ta-c">
                <span v-if="row.workId" class="pill" :class="statusClass(row.workIndexStatus)">
                  {{ statusText(row.workIndexStatus) }}
                </span>
                <span v-else class="small dim">—</span>
              </td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 4px">
                  <button
                    v-if="row.reportSubmissionId"
                    class="btn btn--ghost btn--sm"
                    title="下载报告"
                    @click="downloadReport(row)"
                  >
                    <Icon name="download" :size="14" />
                  </button>
                  <button
                    v-if="!row.workId && row.reportSubmissionId"
                    class="btn btn--primary btn--sm"
                    @click="openAdd(row)"
                  >
                    <Icon name="plus" :size="14" />加入模板库
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <div class="card__head">
        <div>
          <div class="card__title">已入库模板</div>
          <div class="small dim">索引成功后会写入本地模板库；启用 LightRAG 时同时写入 LightRAG</div>
        </div>
      </div>
      <EmptyState
        v-if="!works.length"
        icon="check"
        title="模板库还是空的"
        hint="从上面的候选作品里挑选你认为优秀的报告"
      />
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>作品</th>
              <th>推荐理由</th>
              <th class="ta-c">状态</th>
              <th class="ta-c">方式</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="work in works" :key="work.workId">
              <td>
                <div class="table__primary">{{ work.title }}</div>
                <div class="table__sub">{{ work.studentNo }} · {{ work.topicTitle }}</div>
              </td>
              <td class="truncate" style="max-width: 260px">{{ work.note || '—' }}</td>
              <td class="ta-c">
                <span class="pill" :class="statusClass(work.indexStatus)">
                  {{ statusText(work.indexStatus) }}
                </span>
                <div v-if="work.errorMessage" class="small dim truncate" style="max-width: 180px; margin-top: 4px">
                  {{ work.errorMessage }}
                </div>
              </td>
              <td class="ta-c small dim">{{ work.indexMethod || '—' }}</td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 4px">
                  <button
                    v-if="work.indexStatus === 'FAILED'"
                    class="btn btn--ghost btn--sm"
                    :disabled="busy === `retry-${work.workId}`"
                    @click="retry(work)"
                  >
                    重试
                  </button>
                  <button
                    class="btn btn--icon btn--sm"
                    title="移出模板库"
                    :disabled="busy === `del-${work.workId}`"
                    @click="remove(work)"
                  >
                    <Icon name="trash" :size="14" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <Modal v-if="addOpen" title="加入优秀作品模板库" @close="addOpen = false">
      <div style="display: grid; gap: 12px">
        <div class="alert alert--info">
          <Icon name="file" :size="15" style="flex: none; margin-top: 2px" />
          <span>
            将把「{{ target?.studentName }} · {{ target?.topicTitle }}」的报告正文加入模板库，
            后续报告评审会把它作为 RAG 参考片段。
          </span>
        </div>
        <div class="field">
          <label>推荐理由（可选）</label>
          <textarea
            v-model="form.note"
            class="textarea"
            style="min-height: 80px"
            maxlength="1000"
            placeholder="例如：分层清晰、异常处理完整、测试覆盖充分"
          />
        </div>
        <div class="field">
          <label>标签（可选，逗号分隔）</label>
          <input v-model="form.tags" class="input" maxlength="500" placeholder="分层设计,事务,单元测试" />
        </div>
        <div v-if="addError" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ addError }}
        </div>
      </div>
      <template #foot>
        <button class="btn btn--ghost" @click="addOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy === 'add'" @click="submitAdd">
          <span v-if="busy === 'add'" class="spinner" />加入模板库
        </button>
      </template>
    </Modal>
  </div>
</template>
