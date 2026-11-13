<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import StatusPill from '../components/StatusPill.vue'
import EmptyState from '../components/EmptyState.vue'
import PageHeader from '../components/layout/PageHeader.vue'
import ToolbarBar from '../components/layout/ToolbarBar.vue'
import TeacherSummary from '../components/summary/TeacherSummary.vue'
import Pager from '../components/Pager.vue'
import Drawer from '../components/Drawer.vue'
import Modal from '../components/Modal.vue'
import { api } from '../api/endpoints'
import { confirm } from '../stores/confirm'
import { toast, toastError } from '../stores/toast'
import { useDraft } from '../composables/useDraft'

const router = useRouter()

const loading = ref(true)
const page = ref(1)
const size = 10
const total = ref(0)
const rows = ref([])

const filters = reactive({ keyword: '', status: '', semester: '' })
const STATUS_TABS = [
  { key: '', label: '全部' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'PUBLISHED', label: '已发布' },
  { key: 'CLOSED', label: '已关闭' },
  { key: 'ARCHIVED', label: '已归档' },
]

const offerings = ref([])

/* ---------- 新建开课 ---------- */
const createOpen = ref(false)
const creating = ref(false)
// 已移除：课程模板、每人最多选几题、答辩模式、答辩题目数。
// 这些不再出现在表单里，也不随请求提交，服务端统一按「不限 / 固定 AI 异步答辩」处理。
const form = reactive({
  semester: '',
  className: '',
  description: '',
  defense: 40,
  code: 35,
  report: 25,
  selectStart: '',
  selectEnd: '',
  submitDeadline: '',
  defenseStart: '',
})
const weightSum = computed(() => Number(form.defense) + Number(form.code) + Number(form.report))
const weightOk = computed(() => weightSum.value === 100)
const formError = ref('')

/**
 * 新建教学班草稿。
 * 这个表单 9 个字段，权重和日期都还要手工调，填到一半被 Esc 关掉最招人烦。
 * `draftArmed` 控制「从什么时候开始记」：恢复询问还没结束时不要写盘，
 * 否则会把刚读出来的默认值当成用户的草稿存下来。
 */
const draftArmed = ref(false)
const createDraft = useDraft({
  module: 'draft',
  context: () => 'offering-new',
  source: () => (draftArmed.value ? { ...form } : null),
  apply: (data) => {
    Object.keys(form).forEach((key) => {
      if (data[key] !== undefined && data[key] !== null) form[key] = data[key]
    })
  },
  // 连班名都没填就当空表单，不占地方
  isEmpty: (data) => !String(data?.className || '').trim(),
})

async function restoreDraft() {
  createDraft.resume()
  const data = await createDraft.peek()
  if (!data) return
  const ok = await confirm({
    title: '恢复未完成的教学班？',
    message: `检测到上次填到一半的「${data.className || '未命名教学班'}」，是否继续填写？`,
    confirmText: '恢复',
    cancelText: '重新填写',
  })
  if (ok) {
    await createDraft.restore()
    toast.info('已恢复草稿', '创建成功后会自动清除')
  } else {
    await createDraft.clear()
    createDraft.resume()
  }
}

/* ---------- 发布阻塞项 ---------- */
const blockers = ref(null)

async function load() {
  loading.value = true
  try {
    const data = await api.teacherOfferings({
      page: page.value,
      size,
      keyword: filters.keyword || undefined,
      status: filters.status || undefined,
      semester: filters.semester || undefined,
    })
    rows.value = data?.list || []
    total.value = data?.total || 0
  } catch (err) {
    toastError(err, '加载教学班失败')
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  formError.value = ''
  form.semester = form.semester || defaultSemester()
  createOpen.value = true
  draftArmed.value = false
  await restoreDraft()
  draftArmed.value = true
}

function defaultSemester() {
  const now = new Date()
  const y = now.getFullYear()
  const month = now.getMonth() + 1
  const startYear = month >= 8 ? y : y - 1
  return `${startYear}-${startYear + 1}-${month >= 2 && month <= 7 ? 2 : 1}`
}

function todayPlus(days) {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

/** 后端把具体原因放在 message 与 data.errors，traceId 在响应头，一并展示便于定位。 */
function errorText(err) {
  const fields = err?.data?.errors
  const detail = Array.isArray(fields) && fields.length
    ? fields.map((f) => f.message).filter(Boolean).join('；')
    : ''
  const message = detail || err?.message || '请求失败'
  return err?.traceId ? `${message}（traceId: ${err.traceId}）` : message
}

async function submitCreate() {
  formError.value = ''
  if (!form.semester.trim()) return (formError.value = '请填写学期')
  if (!form.className.trim()) return (formError.value = '请填写教学班名称')
  if (!weightOk.value) return (formError.value = `三项权重之和必须为 100，当前为 ${weightSum.value}`)
  if (!form.selectStart || !form.selectEnd) return (formError.value = '请填写选题开始与结束日期')
  if (form.selectEnd < form.selectStart) return (formError.value = '选题结束日期不能早于开始日期')

  creating.value = true
  try {
    const created = await api.createOffering({
      semester: form.semester.trim(),
      className: form.className.trim(),
      description: form.description || undefined,
      weights: { defense: Number(form.defense), code: Number(form.code), report: Number(form.report) },
      timeline: {
        selectStart: form.selectStart,
        selectEnd: form.selectEnd,
        submitDeadline: form.submitDeadline || undefined,
        defenseStart: form.defenseStart || undefined,
      },
    })
    toast.ok('教学班已创建', '状态为草稿，添加学生与题目后即可发布')
    createOpen.value = false
    // 提交成功即清理，不留孤儿草稿
    await createDraft.clear()
    await load()
    if (created?.id) router.push({ name: 'offering-workbench', params: { offeringId: created.id } })
  } catch (err) {
    formError.value = errorText(err)
  } finally {
    creating.value = false
  }
}

function openWorkbench(id) {
  router.push({ name: 'offering-workbench', params: { offeringId: id } })
}

async function act(row, action) {
  const map = {
    publish: { fn: () => api.publishOffering(row.id), ok: '已发布', label: '发布' },
    close: { fn: () => api.closeOffering(row.id), ok: '已关闭', label: '关闭' },
    archive: { fn: () => api.archiveOffering(row.id), ok: '已归档', label: '归档' },
  }
  const item = map[action]
  if (!item) return
  try {
    await item.fn()
    toast.ok(`${item.label}成功`, row.courseName)
    blockers.value = null
    await load()
  } catch (err) {
    if (Array.isArray(err?.data?.blockers)) {
      blockers.value = { name: row.courseName, list: err.data.blockers }
    } else {
      toastError(err, `${item.label}失败`)
    }
  }
}

onMounted(async () => {
  await load()
  form.selectStart = todayPlus(0)
  form.selectEnd = todayPlus(14)
  form.submitDeadline = todayPlus(60)
})
</script>

<template>
  <div>
    <PageHeader
      title="我的教学班"
      description="按「创建 → 补充名单与题目 → 发布」的顺序推进。发布前系统会自动检查权重、时限与学生名单。"
    >
      <template #meta>
        <span class="pill pill--brand">共 {{ total }} 个教学班</span>
      </template>
      <template #actions>
        <button class="btn btn--primary" @click="openCreate"><Icon name="plus" :size="15" />新建教学班</button>
      </template>
    </PageHeader>

    <TeacherSummary />

    <section class="card">
      <ToolbarBar>
        <template #left>
          <div class="tabs">
            <button
              v-for="t in STATUS_TABS"
              :key="t.key"
              class="tabs__item"
              :class="{ 'is-active': filters.status === t.key }"
              @click="filters.status = t.key; page = 1; load()"
            >
              {{ t.label }}
            </button>
          </div>
        </template>
        <template #right>
          <input v-model="filters.keyword" class="input" style="width: 220px" placeholder="搜索课程 / 班级" @keyup.enter="page = 1; load()" />
          <button class="btn btn--ghost btn--sm" @click="page = 1; load()"><Icon name="search" :size="14" />查询</button>
        </template>
      </ToolbarBar>

      <div v-if="loading" class="card__body">
        <div class="skeleton" style="height: 220px" />
      </div>
      <EmptyState v-else-if="!rows.length" icon="book" title="还没有教学班" hint="填写学期与教学班名称即可创建，题目与名单稍后补充">
        <button class="btn btn--primary btn--sm" style="margin-top: 10px" @click="openCreate">新建教学班</button>
      </EmptyState>
      <div v-else class="card__body card__body--flush">
        <div
          v-for="r in rows"
          :key="r.id"
          class="list-row offering-row"
          @click="openWorkbench(r.id)"
        >
          <span class="offering-row__icon"><Icon name="book" :size="16" /></span>
          <div style="min-width: 0; flex: 1">
            <div class="row" style="gap: 8px">
              <span class="list-row__title truncate">{{ r.courseName || '未关联课程' }}</span>
              <StatusPill :status="r.status" />
            </div>
            <div class="list-row__meta truncate">
              {{ r.className }} · {{ r.semester }} · {{ r.studentCount }} 名学生 ·
              {{ r.topicCount }} 道题 · {{ r.selectionCount }} 人已选
            </div>
          </div>
          <div class="row offering-row__ops" style="gap: 6px" @click.stop>
            <button class="btn btn--ghost btn--sm" @click="openWorkbench(r.id)">进入课程</button>
            <button v-if="r.status === 'DRAFT'" class="btn btn--soft btn--sm" @click="act(r, 'publish')">发布</button>
            <button v-if="r.status === 'PUBLISHED'" class="btn btn--ghost btn--sm" @click="act(r, 'close')">关闭</button>
            <button
              v-if="r.status === 'PUBLISHED' || r.status === 'CLOSED'"
              class="btn btn--ghost btn--sm"
              @click="act(r, 'archive')"
            >
              归档
            </button>
          </div>
        </div>
      </div>
      <Pager v-if="total > size" v-model:page="page" :size="size" :total="total" @update:page="load" />
    </section>

    <!-- 新建教学班 -->
    <Drawer v-if="createOpen" title="新建教学班" wide @close="createOpen = false">
      <div style="display: grid; gap: 16px">
        <div class="form-grid">
          <div class="field">
            <label>学期</label>
            <input v-model="form.semester" class="input" placeholder="2026-2027-1" />
          </div>
          <div class="field">
            <label>教学班</label>
            <input v-model="form.className" class="input" placeholder="例如 计科2301" />
          </div>
          <div class="field span-2">
            <label>课程说明</label>
            <textarea v-model="form.description" class="textarea" placeholder="对学生的整体要求、提交规范等" />
          </div>
        </div>

        <div>
          <div class="section-title"><Icon name="chart" :size="14" />成绩权重</div>
          <div class="form-grid form-grid--3">
            <div class="field">
              <label>答辩 %</label>
              <input v-model.number="form.defense" type="number" min="0" max="100" class="input" />
            </div>
            <div class="field">
              <label>源码 %</label>
              <input v-model.number="form.code" type="number" min="0" max="100" class="input" />
            </div>
            <div class="field">
              <label>报告 %</label>
              <input v-model.number="form.report" type="number" min="0" max="100" class="input" />
            </div>
          </div>
          <div class="row small" style="margin-top: 8px">
            <span :class="weightOk ? 'pill pill--ok' : 'pill pill--danger'">合计 {{ weightSum }}%</span>
            <span class="dim">三项之和必须等于 100，否则发布会被阻塞</span>
          </div>
        </div>

        <div>
          <div class="section-title"><Icon name="clock" :size="14" />时间线</div>
          <div class="form-grid">
            <div class="field">
              <label>选题开始</label>
              <input v-model="form.selectStart" type="date" class="input" />
            </div>
            <div class="field">
              <label>选题结束</label>
              <input v-model="form.selectEnd" type="date" class="input" />
            </div>
            <div class="field">
              <label>提交截止</label>
              <input v-model="form.submitDeadline" type="date" class="input" />
            </div>
            <div class="field">
              <label>答辩开始</label>
              <input v-model="form.defenseStart" type="date" class="input" />
            </div>
          </div>
        </div>

        <div v-if="formError" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ formError }}
        </div>
      </div>

      <template #foot>
        <button class="btn btn--ghost" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="creating" @click="submitCreate">
          <span v-if="creating" class="spinner" />创建教学班
        </button>
      </template>
    </Drawer>

    <!-- 发布阻塞项 -->
    <Modal v-if="blockers" title="暂时无法发布" @close="blockers = null">
      <p class="muted small" style="margin-bottom: 12px">
        「{{ blockers.name }}」还有以下前置条件未满足：
      </p>
      <div style="display: grid; gap: 8px">
        <div v-for="b in blockers.list" :key="b.code" class="alert alert--warn">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
          <span><strong class="mono">{{ b.code }}</strong> · {{ b.message }}</span>
        </div>
      </div>
      <template #foot>
        <button class="btn btn--primary" @click="blockers = null">知道了</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
/* 行列表：整行可点进课程详情，右侧操作独立成组不触发跳转 */
.offering-row {
  cursor: pointer;
  gap: 12px;
}

.offering-row__icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--surface-3);
  color: var(--text-2);
}

.offering-row__ops {
  flex: none;
}

@media (max-width: 900px) {
  .offering-row {
    flex-wrap: wrap;
  }

  .offering-row__ops {
    width: 100%;
    padding-left: 44px;
  }
}
</style>
