<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import Icon from '../Icon.vue'
import StatusPill from '../StatusPill.vue'
import EmptyState from '../EmptyState.vue'
import Modal from '../Modal.vue'
import Pager from '../Pager.vue'
import { api } from '../../api/endpoints'
import { confirm } from '../../stores/confirm'
import { toast, toastError } from '../../stores/toast'

const props = defineProps({
  offeringId: { type: String, required: true },
  offering: { type: Object, default: null },
})
const emit = defineEmits(['changed'])

const loading = ref(true)
const rows = ref([])
const page = ref(1)
const size = 10
const total = ref(0)
const statusFilter = ref('')
const keyword = ref('')
const selected = ref([])
const busy = ref(false)

const STATUS_TABS = [
  { key: '', label: '全部' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'PUBLISHED', label: '已发布' },
  { key: 'ARCHIVED', label: '已归档' },
]

/* ---------- 新建 / 编辑 ---------- */
const editOpen = ref(false)
const editing = ref(null)
const formError = ref('')
const form = reactive({
  title: '',
  description: '',
  requirements: '',
  difficulty: 'MEDIUM',
  maxSelectCount: 1,
  tagsText: '',
  techText: '',
  status: 'DRAFT',
})

const allChecked = computed(() => rows.value.length > 0 && selected.value.length === rows.value.length)

async function load() {
  loading.value = true
  try {
    const data = await api.teacherTopics(props.offeringId, {
      page: page.value,
      size,
      status: statusFilter.value || undefined,
      keyword: keyword.value || undefined,
    })
    rows.value = data?.list || []
    total.value = data?.total || 0
    selected.value = []
  } catch (err) {
    toastError(err, '加载题目失败')
  } finally {
    loading.value = false
  }
}

function toggleAll() {
  selected.value = allChecked.value ? [] : rows.value.map((r) => r.id)
}

function openCreate() {
  editing.value = null
  formError.value = ''
  Object.assign(form, {
    title: '',
    description: '',
    requirements: '',
    difficulty: 'MEDIUM',
    maxSelectCount: 1,
    tagsText: '',
    techText: '',
    status: 'DRAFT',
  })
  editOpen.value = true
}

async function openEdit(row) {
  formError.value = ''
  try {
    const detail = await api.topic(row.id)
    editing.value = detail
    Object.assign(form, {
      title: detail.title || '',
      description: detail.description || '',
      requirements: detail.requirements || '',
      difficulty: detail.difficulty || 'MEDIUM',
      maxSelectCount: detail.maxSelectCount ?? 1,
      tagsText: (detail.tags || []).join(', '),
      techText: (detail.expectedTech || []).join(', '),
      status: detail.status || 'DRAFT',
    })
    editOpen.value = true
  } catch (err) {
    toastError(err, '读取题目详情失败')
  }
}

function toList(text) {
  return String(text || '')
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

async function submit() {
  formError.value = ''
  if (!form.title.trim() || form.title.trim().length < 2) return (formError.value = '标题至少 2 个字')
  if (!form.description.trim()) return (formError.value = '请填写题目描述')

  busy.value = true
  const payload = {
    title: form.title.trim(),
    description: form.description.trim(),
    requirements: form.requirements || undefined,
    difficulty: form.difficulty,
    maxSelectCount: Number(form.maxSelectCount) || 1,
    tags: toList(form.tagsText),
    expectedTech: toList(form.techText),
  }
  try {
    if (editing.value) {
      await api.updateTopic(editing.value.id, { ...payload, version: editing.value.version })
      toast.ok('题目已更新', payload.title)
    } else {
      await api.createTopic(props.offeringId, { ...payload, status: 'DRAFT' })
      toast.ok('题目已创建', '默认为草稿，可批量发布')
    }
    editOpen.value = false
    await load()
    emit('changed')
  } catch (err) {
    formError.value = err.message || '保存失败'
  } finally {
    busy.value = false
  }
}

async function publishOne(row) {
  try {
    await api.publishTopic(row.id)
    toast.ok('题目已发布', row.title)
    await load()
    emit('changed')
  } catch (err) {
    toastError(err, '发布失败')
  }
}

async function archiveOne(row) {
  if (!(await confirm({ title: '归档题目', message: `归档后学生端不再展示「${row.title}」。`, danger: true }))) return
  try {
    await api.archiveTopic(row.id)
    toast.ok('已归档', row.title)
    await load()
    emit('changed')
  } catch (err) {
    toastError(err, '归档失败')
  }
}

async function removeOne(row) {
  if (!(await confirm({ title: '删除题目', message: `删除「${row.title}」后不可恢复；已有选题的题目无法删除。`, danger: true, confirmText: '删除' }))) return
  try {
    await api.deleteTopic(row.id)
    toast.ok('已删除', row.title)
    await load()
    emit('changed')
  } catch (err) {
    toastError(err, '删除失败')
  }
}

async function publishSelected() {
  if (!selected.value.length) return
  try {
    const result = await api.publishTopicsBatch(props.offeringId, selected.value)
    if (result.failed?.length) {
      toast.info('部分题目未发布', `${result.published} 个成功，${result.failed.length} 个失败（缺少描述或容量非法）`)
    } else {
      toast.ok('批量发布成功', `${result.published} 个题目已上线`)
    }
    await load()
    emit('changed')
  } catch (err) {
    toastError(err, '批量发布失败')
  }
}

function difficultyLabel(d) {
  return { EASY: '入门', MEDIUM: '进阶', HARD: '挑战' }[d] || d || '不限'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="row row--wrap" style="margin-bottom: 14px">
      <div class="tabs">
        <button
          v-for="t in STATUS_TABS"
          :key="t.key"
          class="tabs__item"
          :class="{ 'is-active': statusFilter === t.key }"
          @click="statusFilter = t.key; page = 1; load()"
        >
          {{ t.label }}
        </button>
      </div>
      <div class="spacer" />
      <input v-model="keyword" class="input" style="width: 190px" placeholder="搜索标题" @keyup.enter="page = 1; load()" />
      <button class="btn btn--ghost btn--sm" @click="page = 1; load()"><Icon name="search" :size="14" /></button>
      <button v-if="selected.length" class="btn btn--soft btn--sm" @click="publishSelected">
        <Icon name="check" :size="14" />发布所选 {{ selected.length }}
      </button>
      <button class="btn btn--primary btn--sm" @click="openCreate"><Icon name="plus" :size="14" />新建题目</button>
    </div>

    <div class="card">
      <div v-if="loading" class="card__body"><div class="skeleton" style="height: 200px" /></div>
      <EmptyState v-else-if="!rows.length" icon="file" title="还没有题目" hint="至少发布一道题才能发布教学班">
        <button class="btn btn--primary btn--sm" style="margin-top: 10px" @click="openCreate">新建题目</button>
      </EmptyState>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 34px">
                <input type="checkbox" :checked="allChecked" @change="toggleAll" />
              </th>
              <th>题目</th>
              <th>难度</th>
              <th>状态</th>
              <th class="ta-c">容量</th>
              <th>标签</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id">
              <td><input v-model="selected" type="checkbox" :value="r.id" /></td>
              <td>
                <div class="table__primary truncate" style="max-width: 320px">{{ r.title }}</div>
                <div class="table__sub">
                  <span v-if="r.attachmentCount">附件 {{ r.attachmentCount }} 个 · </span>
                  {{ r.publishedAt ? '发布于 ' + new Date(r.publishedAt).toLocaleDateString('zh-CN') : '未发布' }}
                </div>
              </td>
              <td><span class="tag">{{ difficultyLabel(r.difficulty) }}</span></td>
              <td><StatusPill :status="r.status" /></td>
              <td class="ta-c num">
                <span :class="r.selectedCount >= r.maxSelectCount ? 'pill pill--danger' : 'pill'">
                  {{ r.selectedCount }}/{{ r.maxSelectCount }}
                </span>
              </td>
              <td>
                <span v-for="t in (r.tags || []).slice(0, 3)" :key="t" class="tag">{{ t }}</span>
              </td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 4px">
                  <button class="btn btn--icon btn--sm" title="查看/编辑" @click="openEdit(r)"><Icon name="edit" :size="14" /></button>
                  <button v-if="r.status !== 'PUBLISHED'" class="btn btn--icon btn--sm" title="发布" @click="publishOne(r)"><Icon name="check" :size="14" /></button>
                  <button v-if="r.status === 'PUBLISHED'" class="btn btn--icon btn--sm" title="归档" @click="archiveOne(r)"><Icon name="folder" :size="14" /></button>
                  <button class="btn btn--icon btn--sm" title="删除" @click="removeOne(r)"><Icon name="trash" :size="14" /></button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager v-if="total > size" v-model:page="page" :size="size" :total="total" @update:page="load" />
    </div>

    <Modal v-if="editOpen" :title="editing ? '编辑题目' : '新建题目'" size="lg" @close="editOpen = false">
      <div style="display: grid; gap: 14px">
        <div class="field">
          <label>标题</label>
          <input v-model="form.title" class="input" maxlength="100" placeholder="例如：基于 Spring Boot 的图书管理系统" />
        </div>
        <div class="field">
          <label>题目描述</label>
          <textarea v-model="form.description" class="textarea" style="min-height: 110px" placeholder="业务背景、功能要求、评分关注点" />
        </div>
        <div class="field">
          <label>交付要求（选填）</label>
          <textarea v-model="form.requirements" class="textarea" style="min-height: 70px" placeholder="源码结构、必用技术、报告章节等" />
        </div>
        <div class="form-grid form-grid--3">
          <div class="field">
            <label>难度</label>
            <select v-model="form.difficulty" class="select">
              <option value="EASY">入门</option>
              <option value="MEDIUM">进阶</option>
              <option value="HARD">挑战</option>
            </select>
          </div>
          <div class="field">
            <label>容量（最多几人选）</label>
            <input v-model.number="form.maxSelectCount" type="number" min="1" max="50" class="input" />
          </div>
          <div class="field">
            <label>状态</label>
            <input :value="editing ? editing.status : 'DRAFT'" class="input" disabled />
          </div>
        </div>
        <div class="form-grid">
          <div class="field">
            <label>标签（逗号分隔）</label>
            <input v-model="form.tagsText" class="input" placeholder="Web, 数据库, 前后端分离" />
          </div>
          <div class="field">
            <label>期望技术栈（逗号分隔）</label>
            <input v-model="form.techText" class="input" placeholder="Spring Boot, MySQL, Vue" />
          </div>
        </div>
        <div v-if="formError" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ formError }}
        </div>
      </div>
      <template #foot>
        <button class="btn btn--ghost" @click="editOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy" @click="submit"><span v-if="busy" class="spinner" />保存</button>
      </template>
    </Modal>
  </div>
</template>
