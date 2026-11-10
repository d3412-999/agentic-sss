<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import StatusPill from '../components/StatusPill.vue'
import EmptyState from '../components/EmptyState.vue'
import Pager from '../components/Pager.vue'
import Drawer from '../components/Drawer.vue'
import Modal from '../components/Modal.vue'
import { api } from '../api/endpoints'
import { toast, toastError } from '../stores/toast'

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

const courses = ref([])
const offerings = ref([])

/* ---------- 新建开课 ---------- */
const createOpen = ref(false)
const creating = ref(false)
const form = reactive({
  courseId: '',
  semester: '',
  className: '',
  description: '',
  topicSelectLimit: 3,
  defense: 40,
  code: 35,
  report: 25,
  selectStart: '',
  selectEnd: '',
  submitDeadline: '',
  defenseStart: '',
  defenseEnd: '',
  defenseMode: 'AI_ASYNC',
  questionCount: 5,
  maxAnswerSeconds: 180,
  allowRetry: true,
})
const weightSum = computed(() => Number(form.defense) + Number(form.code) + Number(form.report))
const weightOk = computed(() => weightSum.value === 100)
const formError = ref('')

/* ---------- 新建课程模板 ---------- */
const courseOpen = ref(false)
const courseBusy = ref(false)
const courseForm = reactive({ code: '', name: '', credit: 2, description: '' })

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

async function loadCourses() {
  try {
    const data = await api.courses({ page: 1, size: 100 })
    courses.value = data?.list || []
  } catch {
    courses.value = []
  }
}

function openCreate() {
  formError.value = ''
  if (!courses.value.length) {
    toast.info('先建课程模板', '教学班需要挂在一门课程模板下，请先新建课程')
    courseOpen.value = true
    return
  }
  form.courseId = form.courseId || courses.value[0].id
  form.semester = form.semester || defaultSemester()
  createOpen.value = true
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

async function submitCreate() {
  formError.value = ''
  if (!form.courseId) return (formError.value = '请选择课程模板')
  if (!form.semester.trim()) return (formError.value = '请填写学期')
  if (!form.className.trim()) return (formError.value = '请填写教学班名称')
  if (!weightOk.value) return (formError.value = `三项权重之和必须为 100，当前为 ${weightSum.value}`)
  if (!form.selectStart || !form.selectEnd) return (formError.value = '请填写选题开始与结束日期')
  if (form.selectEnd < form.selectStart) return (formError.value = '选题结束日期不能早于开始日期')

  creating.value = true
  try {
    const created = await api.createOffering({
      courseId: form.courseId,
      semester: form.semester.trim(),
      className: form.className.trim(),
      description: form.description || undefined,
      topicSelectLimit: Number(form.topicSelectLimit),
      weights: { defense: Number(form.defense), code: Number(form.code), report: Number(form.report) },
      defenseConfig: {
        defenseMode: form.defenseMode,
        questionCount: Number(form.questionCount),
        maxAnswerSeconds: Number(form.maxAnswerSeconds),
        allowRetry: form.allowRetry,
        language: 'zh-CN',
      },
      timeline: {
        selectStart: form.selectStart,
        selectEnd: form.selectEnd,
        submitDeadline: form.submitDeadline || undefined,
        defenseStart: form.defenseStart || undefined,
        defenseEnd: form.defenseEnd || undefined,
      },
    })
    toast.ok('教学班已创建', '状态为草稿，添加学生与题目后即可发布')
    createOpen.value = false
    await load()
    if (created?.id) router.push({ name: 'offering-workbench', params: { offeringId: created.id } })
  } catch (err) {
    const fields = err?.data?.errors
    formError.value = Array.isArray(fields) && fields.length
      ? fields.map((f) => f.message).join('；')
      : err.message
  } finally {
    creating.value = false
  }
}

async function submitCourse() {
  if (!courseForm.code.trim() || !courseForm.name.trim()) return
  courseBusy.value = true
  try {
    const created = await api.createCourse({
      code: courseForm.code.trim(),
      name: courseForm.name.trim(),
      credit: Number(courseForm.credit) || undefined,
      description: courseForm.description || undefined,
    })
    toast.ok('课程模板已创建', created?.name)
    courseOpen.value = false
    courseForm.code = ''
    courseForm.name = ''
    courseForm.description = ''
    await loadCourses()
    form.courseId = created?.id || form.courseId
    form.semester = form.semester || defaultSemester()
    createOpen.value = true
  } catch (err) {
    toastError(err, '创建课程失败')
  } finally {
    courseBusy.value = false
  }
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
  await Promise.all([load(), loadCourses()])
  form.selectStart = todayPlus(0)
  form.selectEnd = todayPlus(14)
  form.submitDeadline = todayPlus(60)
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>教学班</h1>
        <p>课程模板 → 开课（教学班）→ 出题 → 发布。发布前系统会自动检查权重、时限与学生名单。</p>
      </div>
      <div class="page-head__actions">
        <button class="btn btn--ghost" @click="courseOpen = true"><Icon name="folder" :size="15" />课程模板</button>
        <button class="btn btn--primary" @click="openCreate"><Icon name="plus" :size="15" />新建教学班</button>
      </div>
    </div>

    <div class="card">
      <div class="card__head" style="flex-wrap: wrap; gap: 8px">
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
        <div class="spacer" />
        <input v-model="filters.keyword" class="input" style="width: 200px" placeholder="搜索课程 / 班级" @keyup.enter="page = 1; load()" />
        <button class="btn btn--ghost btn--sm" @click="page = 1; load()"><Icon name="search" :size="14" />查询</button>
      </div>

      <div v-if="loading" class="card__body">
        <div class="skeleton" style="height: 220px" />
      </div>
      <EmptyState v-else-if="!rows.length" icon="book" title="还没有教学班" hint="新建教学班需要先有一门课程模板">
        <button class="btn btn--primary btn--sm" style="margin-top: 10px" @click="openCreate">新建教学班</button>
      </EmptyState>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>课程 / 班级</th>
              <th>状态</th>
              <th class="ta-r">学生</th>
              <th class="ta-r">题目</th>
              <th class="ta-r">已选</th>
              <th>学期</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id">
              <td>
                <div class="table__primary">{{ r.courseName }}</div>
                <div class="table__sub">{{ r.className }}</div>
              </td>
              <td><StatusPill :status="r.status" /></td>
              <td class="ta-r num">{{ r.studentCount }}</td>
              <td class="ta-r num">{{ r.topicCount }}</td>
              <td class="ta-r num">{{ r.selectionCount }}</td>
              <td class="small dim mono">{{ r.semester }}</td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 6px">
                  <button
                    class="btn btn--ghost btn--sm"
                    @click="router.push({ name: 'offering-workbench', params: { offeringId: r.id } })"
                  >
                    工作台
                  </button>
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
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager v-if="total > size" v-model:page="page" :size="size" :total="total" @update:page="load" />
    </div>

    <!-- 新建教学班 -->
    <Drawer v-if="createOpen" title="新建教学班" wide @close="createOpen = false">
      <div style="display: grid; gap: 16px">
        <div class="form-grid">
          <div class="field span-2">
            <label>课程模板</label>
            <select v-model="form.courseId" class="select">
              <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.code }} · {{ c.name }}</option>
            </select>
          </div>
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

        <div>
          <div class="section-title"><Icon name="flag" :size="14" />选题与答辩设置</div>
          <div class="form-grid form-grid--3">
            <div class="field">
              <label>每人最多选几题</label>
              <input v-model.number="form.topicSelectLimit" type="number" min="1" max="10" class="input" />
            </div>
            <div class="field">
              <label>答辩模式</label>
              <select v-model="form.defenseMode" class="select">
                <option value="AI_ASYNC">AI 异步答辩</option>
                <option value="LIVE">在线实时答辩</option>
              </select>
            </div>
            <div class="field">
              <label>答辩题目数</label>
              <input v-model.number="form.questionCount" type="number" min="1" max="20" class="input" />
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

    <!-- 新建课程模板 -->
    <Modal v-if="courseOpen" title="新建课程模板" size="sm" @close="courseOpen = false">
      <div style="display: grid; gap: 14px">
        <div class="field">
          <label>课程代码</label>
          <input v-model="courseForm.code" class="input" placeholder="例如 CS2301" maxlength="32" />
        </div>
        <div class="field">
          <label>课程名称</label>
          <input v-model="courseForm.name" class="input" placeholder="面向对象程序设计课程设计" maxlength="128" />
        </div>
        <div class="field">
          <label>学分</label>
          <input v-model.number="courseForm.credit" type="number" min="0" step="0.5" class="input" />
        </div>
        <div class="field">
          <label>简介</label>
          <textarea v-model="courseForm.description" class="textarea" style="min-height: 70px" />
        </div>
      </div>
      <template #foot>
        <button class="btn btn--ghost" @click="courseOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="courseBusy" @click="submitCourse">
          <span v-if="courseBusy" class="spinner" />创建
        </button>
      </template>
    </Modal>

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
