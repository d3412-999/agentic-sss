<template>
  <div>
    <div class="crumb">
      <RouterLink to="/teacher/offerings">我的开课</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <RouterLink :to="`/teacher/offerings/${offering.id}`">面向对象程序设计课程设计(Java)</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <span>题目管理</span>
    </div>

    <div class="page-head">
      <div>
        <div class="page-title">题目管理</div>
        <div class="page-sub">共 {{ list.length }} 道题 · 已发布 {{ publishedCount }} 道 · 草稿 {{ list.length - publishedCount }} 道</div>
      </div>
      <div class="page-actions">
        <button class="btn btn-outline" :disabled="!draftIds.length" @click="publishAll">
          <AppIcon name="send" :size="18" />
          批量发布（{{ draftIds.length }}）
        </button>
        <button class="btn btn-primary" @click="openCreate">
          <AppIcon name="plus" :size="18" />
          新建题目
        </button>
      </div>
    </div>


    <div class="toolbar card">
      <label class="search" style="width: 280px">
        <AppIcon name="search" :size="17" />
        <input v-model="keyword" placeholder="搜索题目或标签" />
      </label>
      <div class="row gap-6">
        <button
          v-for="f in filters"
          :key="f.value"
          class="btn btn-sm"
          :class="status === f.value ? 'btn-tonal' : 'btn-text'"
          @click="status = f.value"
        >
          {{ f.label }}
        </button>
      </div>
    </div>

    <div class="grid g2 mt-16">
      <article v-for="t in visible" :key="t.id" class="card topic-card">
        <div class="between">
          <div class="row gap-8">
            <span class="tiny dim mono">#{{ t.id.toUpperCase() }}</span>
            <span class="chip" :class="t.status === 'PUBLISHED' ? 'green' : ''">
              {{ t.status === 'PUBLISHED' ? '已发布' : '草稿' }}
            </span>
          </div>
          <StatusChip :status="t.difficulty" />
        </div>

        <h3 class="topic-title">{{ t.title }}</h3>
        <p class="topic-summary">{{ t.summary }}</p>

        <div class="row gap-6 wrap mb-12">
          <span v-for="tag in t.tags" :key="tag" class="chip outline">{{ tag }}</span>
        </div>

        <div class="between">
          <div class="cap">
            <span class="tiny dim">已选 {{ t.selected }} / {{ t.max }}</span>
            <div class="bar thin" style="width: 90px">
              <span :style="{ width: Math.min(100, (t.selected / t.max) * 100) + '%' }" :class="{ warn: t.selected >= t.max }" />
            </div>
          </div>
          <div class="row gap-4">
            <button class="btn btn-sm btn-text" @click="edit(t)">编辑</button>
            <button v-if="t.status === 'DRAFT'" class="btn btn-sm btn-tonal" @click="publish(t)">发布</button>
            <button v-else class="btn btn-sm btn-text" @click="archive(t)">下架</button>
          </div>
        </div>
      </article>

      <button class="card add-card" @click="openCreate">
        <span class="add-icon"><AppIcon name="plus" :size="22" /></span>
        <span class="strong">新建题目</span>
        <span class="tiny dim">可手动填写或从 Excel 导入</span>
      </button>
    </div>

    <ModalDialog v-model="createOpen" :title="editing ? '编辑题目' : '新建题目'" subtitle="题目发布后学生即可选择">
      <div class="col gap-14">
        <div class="field">
          <label class="label">题目名称<span class="req">*</span></label>
          <input v-model="form.title" class="input" placeholder="如 基于 JavaFX 的图书管理系统" />
        </div>
        <div class="field">
          <label class="label">题目简介<span class="req">*</span></label>
          <textarea v-model="form.summary" class="textarea" placeholder="一句话说明题目要求与考察点" />
        </div>
        <div class="grid g2">
          <div class="field">
            <label class="label">难度</label>
            <select v-model="form.difficulty" class="select">
              <option value="EASY">简单</option>
              <option value="MEDIUM">中等</option>
              <option value="HARD">较难</option>
            </select>
          </div>
          <div class="field">
            <label class="label">人数上限</label>
            <input v-model.number="form.max" class="input" type="number" min="1" max="50" />
          </div>
        </div>
        <div class="field">
          <label class="label">标签</label>
          <input v-model="form.tags" class="input" placeholder="用逗号分隔，如 JavaFX, JDBC, 设计模式" />
        </div>
      </div>
      <template #footer>
        <button class="btn btn-outline" @click="createOpen = false">取消</button>
        <button class="btn btn-primary" :disabled="!form.title || !form.summary" @click="save">保存</button>
      </template>
    </ModalDialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import StatusChip from '@/components/StatusChip.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import { offering, topics } from '@/data/mock'

const list = ref(topics.map((t) => ({ ...t })))
const keyword = ref('')
const status = ref('all')
const createOpen = ref(false)
const editing = ref(null)

const filters = [
  { value: 'all', label: '全部' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'DRAFT', label: '草稿' }
]

const form = reactive({ title: '', summary: '', difficulty: 'MEDIUM', max: 5, tags: '' })

const publishedCount = computed(() => list.value.filter((t) => t.status === 'PUBLISHED').length)
const draftIds = computed(() => list.value.filter((t) => t.status === 'DRAFT').map((t) => t.id))

const visible = computed(() =>
  list.value.filter((t) => {
    const kw = keyword.value.trim()
    const matchKw = !kw || t.title.includes(kw) || t.tags.some((x) => x.includes(kw))
    return matchKw && (status.value === 'all' || t.status === status.value)
  })
)

function openCreate() {
  editing.value = null
  Object.assign(form, { title: '', summary: '', difficulty: 'MEDIUM', max: 5, tags: '' })
  createOpen.value = true
}

function edit(t) {
  editing.value = t
  Object.assign(form, { title: t.title, summary: t.summary, difficulty: t.difficulty, max: t.max, tags: t.tags.join(', ') })
  createOpen.value = true
}

function save() {
  const tags = form.tags.split(/[,，]/).map((x) => x.trim()).filter(Boolean)
  if (editing.value) {
    Object.assign(editing.value, { title: form.title, summary: form.summary, difficulty: form.difficulty, max: form.max, tags })
  } else {
    list.value.unshift({
      id: 't' + (list.value.length + 1),
      title: form.title,
      summary: form.summary,
      difficulty: form.difficulty,
      tags,
      max: form.max,
      selected: 0,
      status: 'DRAFT'
    })
  }
  createOpen.value = false
}

function publish(t) {
  t.status = 'PUBLISHED'
}

function archive(t) {
  t.status = 'DRAFT'
}

function publishAll() {
  list.value.forEach((t) => {
    if (t.status === 'DRAFT') t.status = 'PUBLISHED'
  })
}
</script>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 16px; flex-wrap: wrap; }
.bar span.warn { background: #f29900; }

.topic-card { padding: 18px 20px; display: flex; flex-direction: column; gap: 10px; }
.topic-title { font-size: 16.5px; font-weight: 600; letter-spacing: -.2px; }
.topic-summary { font-size: 14px; line-height: 1.7; color: var(--c-text-2); min-height: 44px; }
.cap { display: flex; align-items: center; gap: 10px; }

.add-card {
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px;
  min-height: 190px;
  border: 1.5px dashed var(--c-line);
  background: var(--c-surface);
  color: var(--c-text-2);
  cursor: pointer;
  transition: border-color .16s var(--ease), background .16s var(--ease);
}
.add-card:hover { border-color: var(--c-blue); background: rgba(26, 115, 232, .02); }
.add-icon { width: 44px; height: 44px; border-radius: 50%; background: var(--c-blue-50); color: var(--c-blue-700); display: grid; place-items: center; }
</style>
