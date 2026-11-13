<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import Icon from '../Icon.vue'
import EmptyState from '../EmptyState.vue'
import TabHead from '../layout/TabHead.vue'
import Modal from '../Modal.vue'
import Pager from '../Pager.vue'
import AttachmentUploader from '../topic/AttachmentUploader.vue'
import { api } from '../../api/endpoints'
import { confirm } from '../../stores/confirm'
import { toast, toastError } from '../../stores/toast'
import { useDraft } from '../../composables/useDraft'

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
const difficultyFilter = ref('')
const keyword = ref('')
const selected = ref([])
const busy = ref(false)

const difficulties = ref([])
const policy = ref({
  allowedExtensions: ['pdf', 'doc', 'docx'],
  maxFileBytes: 20 * 1024 * 1024,
  maxFilesPerRequest: 5,
  maxTotalBytes: 50 * 1024 * 1024,
  maxPerTopic: 20,
})

/* ---------- 新建 / 编辑 ---------- */
const editOpen = ref(false)
const editing = ref(null)
const formError = ref(null)
const attachmentFileIds = ref([])
const uploaderKey = ref(0)
const titleInput = ref(null)

const form = reactive({
  title: '',
  description: '',
  difficulty: 'MEDIUM',
})

const allChecked = computed(() => rows.value.length > 0 && selected.value.length === rows.value.length)
const uploader = ref(null)

/**
 * 题目草稿：新建与编辑各存一份（context 里带 topicId），互不覆盖。
 * 只记文字字段——附件是服务端 fileId，恢复时若文件已被清理反而会造出脏提交，
 * 所以附件不参与草稿，恢复后需要重新上传。
 */
const draftContext = ref('')
const draftArmed = ref(false)
const topicDraft = useDraft({
  module: 'draft',
  context: () => draftContext.value,
  source: () =>
    draftArmed.value
      ? { title: form.title, description: form.description, difficulty: form.difficulty }
      : null,
  apply: (data) => {
    if (typeof data.title === 'string') form.title = data.title
    if (typeof data.description === 'string') form.description = data.description
    if (typeof data.difficulty === 'string') form.difficulty = data.difficulty
  },
  isEmpty: (data) => !String(data?.title || '').trim() && !String(data?.description || '').trim(),
})

/** 打开弹窗后问一次：有草稿就问要不要恢复，没有就安静继续。 */
async function restoreTopicDraft(label) {
  topicDraft.resume()
  const data = await topicDraft.peek()
  if (!data) return
  const ok = await confirm({
    title: '恢复未保存的草稿？',
    message: `检测到上次未完成的${label}（${data.title || '未填写标题'}），是否继续编辑？`,
    confirmText: '恢复',
    cancelText: '不用了',
  })
  if (ok) {
    await topicDraft.restore()
    toast.info('已恢复草稿', '保存成功后会自动清除')
  } else {
    await topicDraft.clear()
    topicDraft.resume()
  }
}

/** 有未保存改动时关闭要二次确认，避免误点遮罩丢掉整页输入。 */
const dirty = computed(() => {
  if (!editOpen.value) return false
  if (!editing.value) {
    return !!(form.title.trim() || form.description.trim() || attachmentFileIds.value.length)
  }
  return (
    form.title !== (editing.value.title || '') ||
    form.description !== (editing.value.description || '') ||
    form.difficulty !== (editing.value.difficulty || 'MEDIUM')
  )
})

async function load() {
  loading.value = true
  try {
    const data = await api.teacherTopics(props.offeringId, {
      page: page.value,
      size,
      difficulty: difficultyFilter.value || undefined,
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

async function loadMeta() {
  try {
    const list = await api.difficulties()
    difficulties.value = Array.isArray(list) && list.length
      ? list
      : [
          { code: 'EASY', label: '入门', sort: 10 },
          { code: 'MEDIUM', label: '进阶', sort: 20 },
          { code: 'HARD', label: '挑战', sort: 30 },
        ]
  } catch {
    difficulties.value = [
      { code: 'EASY', label: '入门', sort: 10 },
      { code: 'MEDIUM', label: '进阶', sort: 20 },
      { code: 'HARD', label: '挑战', sort: 30 },
    ]
  }
  try {
    const data = await api.uploadPolicy()
    if (data) policy.value = data
  } catch {
    /* 取不到就用与后端一致的默认值，不阻塞页面 */
  }
}

function toggleAll() {
  selected.value = allChecked.value ? [] : rows.value.map((r) => r.id)
}

function resetForm() {
  form.title = ''
  form.description = ''
  form.difficulty = form.difficulty || 'MEDIUM'
  attachmentFileIds.value = []
  uploaderKey.value += 1
  formError.value = null
}

async function openCreate() {
  editing.value = null
  resetForm()
  draftContext.value = `topic-${props.offeringId}-new`
  draftArmed.value = false
  editOpen.value = true
  await nextTick()
  titleInput.value?.focus()
  await restoreTopicDraft('新题目草稿')
  draftArmed.value = true
}

async function openEdit(row) {
  formError.value = null
  try {
    const detail = await api.topic(row.id)
    editing.value = detail
    form.title = detail.title || ''
    form.description = detail.description || ''
    form.difficulty = detail.difficulty || 'MEDIUM'
    uploaderKey.value += 1
    draftContext.value = `topic-${props.offeringId}-${row.id}`
    draftArmed.value = false
    editOpen.value = true
    await restoreTopicDraft('题目修改')
    draftArmed.value = true
  } catch (err) {
    toastError(err, '读取题目详情失败')
  }
}

function describeError(err) {
  const fields = err?.data?.errors
  const detail = Array.isArray(fields) && fields.length
    ? fields.map((f) => f.message).join('；')
    : err?.traceId
      ? `traceId: ${err.traceId}`
      : ''
  return { text: err?.message || '保存失败', detail }
}

function validate() {
  const title = form.title.trim()
  if (title.length < 2) return '标题至少 2 个字'
  if (title.length > 100) return '标题不能超过 100 字'
  if (!form.description.trim()) return '请填写题目描述'
  if (form.description.length > 5000) return '题目描述不能超过 5000 字'
  return ''
}

/**
 * @param keepOpen true = 「保存并继续新建」：保存成功后保留弹窗、清空内容，方便连续录题
 */
async function submit(keepOpen = false) {
  formError.value = null
  const invalid = validate()
  if (invalid) {
    formError.value = { text: invalid, detail: '' }
    return
  }
  const uploading = uploader.value?.uploading
  if (uploading) {
    formError.value = { text: '还有附件正在上传，请等待完成或取消后再保存', detail: '' }
    return
  }
  if (busy.value) return

  busy.value = true
  try {
    if (editing.value) {
      const updated = await api.updateTopic(editing.value.id, {
        version: editing.value.version,
        title: form.title.trim(),
        description: form.description.trim(),
        difficulty: form.difficulty,
      })
      editing.value = { ...editing.value, ...updated }
      toast.ok('题目已更新', updated.title)
      editOpen.value = false
      await topicDraft.clear()
      await load()
      emit('changed')
    } else {
      const created = await api.createTopic(props.offeringId, {
        title: form.title.trim(),
        description: form.description.trim(),
        difficulty: form.difficulty,
        attachments: attachmentFileIds.value,
      })
      toast.ok('题目已创建', keepOpen ? '可以继续录入下一题' : created.title)
      await topicDraft.clear()
      await load()
      emit('changed')
      if (keepOpen) {
        const kept = form.difficulty
        resetForm()
        form.difficulty = kept
        await nextTick()
        titleInput.value?.focus()
      } else {
        editOpen.value = false
      }
    }
  } catch (err) {
    formError.value = describeError(err)
  } finally {
    busy.value = false
  }
}

async function requestClose() {
  if (busy.value) return
  if (dirty.value && !(await confirm({
    title: '关闭编辑？',
    message: '有未保存的修改，已自动存为草稿，下次打开这里可以恢复。',
    confirmText: '关闭',
  }))) return
  editOpen.value = false
}

async function removeOne(row) {
  if (!(await confirm({
    title: '删除题目',
    message: `删除「${row.title}」后不可恢复；已有学生选择的题目无法删除。`,
    danger: true,
    confirmText: '删除',
  }))) return
  try {
    await api.deleteTopic(row.id)
    toast.ok('已删除', row.title)
    await load()
    emit('changed')
  } catch (err) {
    toastError(err, '删除失败')
  }
}

/** 批量删除：逐条执行，失败的保留在列表里并汇总原因。 */
async function removeSelected() {
  if (!selected.value.length) return
  if (!(await confirm({
    title: `删除 ${selected.value.length} 道题目`,
    message: '删除后不可恢复；已有学生选择的题目会被跳过。',
    danger: true,
    confirmText: '批量删除',
  }))) return
  busy.value = true
  const failed = []
  for (const id of [...selected.value]) {
    try {
      await api.deleteTopic(id)
    } catch (err) {
      failed.push(err?.message || '删除失败')
    }
  }
  busy.value = false
  if (failed.length) {
    toast.info('部分题目未删除', failed.slice(0, 2).join('；'))
  } else {
    toast.ok('已删除', `${selected.value.length} 道题目`)
  }
  await load()
  emit('changed')
}

function attachmentsChanged(list) {
  if (editing.value) editing.value = { ...editing.value, attachments: list || [] }
}

function difficultyClass(code) {
  return { EASY: 'tag--easy', MEDIUM: 'tag--medium', HARD: 'tag--hard' }[code] || ''
}

onMounted(async () => {
  await Promise.all([load(), loadMeta()])
})
</script>

<template>
  <div>
    <TabHead
      title="题目"
      description="题目发布后学生才能选题；难度与数量会参与答辩出题规则。"
      :count="total"
      count-unit=" 道"
    >
      <template #actions>
        <button class="btn btn--primary btn--sm" @click="openCreate"><Icon name="plus" :size="14" />新建题目</button>
      </template>
    </TabHead>

    <div class="row row--wrap" style="margin-bottom: 12px">
      <div class="tabs">
        <button
          class="tabs__item"
          :class="{ 'is-active': difficultyFilter === '' }"
          @click="difficultyFilter = ''; page = 1; load()"
        >
          全部
        </button>
        <button
          v-for="d in difficulties"
          :key="d.code"
          class="tabs__item"
          :class="{ 'is-active': difficultyFilter === d.code }"
          @click="difficultyFilter = d.code; page = 1; load()"
        >
          {{ d.label }}
        </button>
      </div>
      <div class="spacer" />
      <input
        v-model="keyword"
        class="input"
        style="width: 190px"
        placeholder="搜索标题"
        aria-label="搜索题目标题"
        @keyup.enter="page = 1; load()"
      />
      <button class="btn btn--ghost btn--sm" aria-label="搜索" @click="page = 1; load()">
        <Icon name="search" :size="14" />
      </button>
      <button
        v-if="selected.length"
        class="btn btn--soft btn--sm"
        :disabled="busy"
        @click="removeSelected"
      >
        <Icon name="trash" :size="14" />删除所选 {{ selected.length }}
      </button>
    </div>

    <div class="card">
      <div v-if="loading" class="card__body"><div class="skeleton" style="height: 200px" /></div>
      <EmptyState v-else-if="!rows.length" icon="file" title="还没有题目" hint="新建题目后学生即可在选题中心看到">
        <button class="btn btn--primary btn--sm" style="margin-top: 10px" @click="openCreate">新建题目</button>
      </EmptyState>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 34px">
                <input type="checkbox" :checked="allChecked" aria-label="全选" @change="toggleAll" />
              </th>
              <th>题目</th>
              <th>难度</th>
              <th class="ta-c">已选</th>
              <th class="ta-c">附件</th>
              <th>更新于</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id">
              <td><input v-model="selected" type="checkbox" :value="r.id" :aria-label="`选择 ${r.title}`" /></td>
              <td>
                <button class="table__primary truncate link-title" style="max-width: 320px" @click="openEdit(r)">
                  {{ r.title }}
                </button>
                <div class="table__sub">{{ r.description || '暂无描述' }}</div>
              </td>
              <td><span class="tag" :class="difficultyClass(r.difficulty)">{{ r.difficultyLabel }}</span></td>
              <td class="ta-c num">{{ r.selectedCount }}</td>
              <td class="ta-c num">
                <span v-if="r.attachmentCount" class="pill">{{ r.attachmentCount }} 个</span>
                <span v-else class="dim small">—</span>
              </td>
              <td class="small dim">{{ r.updatedAt ? new Date(r.updatedAt).toLocaleDateString('zh-CN') : '—' }}</td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 4px">
                  <button class="btn btn--icon btn--sm" title="编辑" @click="openEdit(r)"><Icon name="edit" :size="14" /></button>
                  <button class="btn btn--icon btn--sm" title="删除" @click="removeOne(r)"><Icon name="trash" :size="14" /></button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager v-if="total > size" v-model:page="page" :size="size" :total="total" @update:page="load" />
    </div>

    <Modal v-if="editOpen" :title="editing ? '编辑题目' : '新建题目'" size="lg" @close="requestClose">
      <div style="display: grid; gap: 14px">
        <div class="field">
          <label for="topic-title">标题</label>
          <input
            id="topic-title"
            ref="titleInput"
            v-model="form.title"
            class="input"
            maxlength="100"
            placeholder="例如：基于 Spring Boot 的图书管理系统"
          />
        </div>
        <div class="field">
          <label for="topic-desc">题目描述</label>
          <textarea
            id="topic-desc"
            v-model="form.description"
            class="textarea"
            style="min-height: 120px"
            maxlength="5000"
            placeholder="业务背景、功能要求、评分关注点"
          />
          <div class="small dim" style="text-align: right">{{ form.description.length }} / 5000</div>
        </div>
        <div class="field" style="max-width: 220px">
          <label for="topic-difficulty">难度</label>
          <select id="topic-difficulty" v-model="form.difficulty" class="select">
            <option v-for="d in difficulties" :key="d.code" :value="d.code">{{ d.label }}</option>
          </select>
        </div>
        <div class="field">
          <label>题目附件</label>
          <AttachmentUploader
            :key="uploaderKey"
            ref="uploader"
            :topic-id="editing ? editing.id : ''"
            :existing="editing ? editing.attachments : []"
            :policy="policy"
            :disabled="busy"
            @update:file-ids="attachmentFileIds = $event"
            @changed="attachmentsChanged"
          />
        </div>
        <div v-if="formError" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
          <div>
            <div>{{ formError.text }}</div>
            <div v-if="formError.detail" class="small" style="opacity: 0.8; margin-top: 2px">{{ formError.detail }}</div>
          </div>
        </div>
      </div>
      <template #foot>
        <button class="btn btn--ghost" :disabled="busy" @click="requestClose">取消</button>
        <button v-if="!editing" class="btn btn--ghost" :disabled="busy" @click="submit(true)">
          <span v-if="busy" class="spinner" />保存并继续新建
        </button>
        <button class="btn btn--primary" :disabled="busy" @click="submit(false)">
          <span v-if="busy" class="spinner" />保存
        </button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.link-title {
  background: none;
  border: 0;
  padding: 0;
  text-align: left;
  font: inherit;
  cursor: pointer;
  color: inherit;
}
.link-title:hover {
  color: var(--brand);
}
.tag--easy {
  background: rgba(34, 197, 94, 0.12);
  color: #15803d;
}
.tag--medium {
  background: rgba(59, 130, 246, 0.12);
  color: #1d4ed8;
}
.tag--hard {
  background: rgba(249, 115, 22, 0.14);
  color: #c2410c;
}
</style>
