<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '../components/Icon.vue'
import PageHeader from '../components/layout/PageHeader.vue'
import StepFlow from '../components/layout/StepFlow.vue'
import { STUDENT_FLOW } from '../config/flow'
import EmptyState from '../components/EmptyState.vue'
import Drawer from '../components/Drawer.vue'
import { api, reasonText } from '../api/endpoints'
import { confirm } from '../stores/confirm'
import { toast, toastError } from '../stores/toast'

const route = useRoute()
const offeringId = computed(() => String(route.params.offeringId))

const loading = ref(true)
const topics = ref([])
const offering = ref(null)
const keyword = ref('')
const difficulty = ref('')
const page = ref(1)
const size = 12
const total = ref(0)

const selected = ref([])
const detail = ref(null)
const detailOpen = ref(false)
const submitting = ref(false)

const difficulties = ref([
  { code: 'EASY', label: '入门' },
  { code: 'MEDIUM', label: '进阶' },
  { code: 'HARD', label: '挑战' },
])

// topicSelectLimit 为 null 表示「不限」，用 Infinity 参与计算，避免被当成 0 而禁止选择
const limit = computed(() => offering.value?.topicSelectLimit ?? Infinity)
const myCount = computed(() => offering.value?.mySelectionCount ?? 0)
const remainingSlots = computed(() => Math.max(0, limit.value - myCount.value))
const canSelect = computed(() => offering.value?.canSelect !== false)

async function loadOffering() {
  try {
    const data = await api.studentOfferings({ page: 1, size: 50 })
    const list = data?.list || []
    offering.value = list.find((o) => o.offeringId === offeringId.value) || null
  } catch {
    offering.value = null
  }
}

async function loadDifficulties() {
  try {
    const list = await api.difficulties()
    if (Array.isArray(list) && list.length) difficulties.value = list
  } catch {
    /* 用默认三级兜底，不影响选题 */
  }
}

async function load() {
  loading.value = true
  try {
    const data = await api.studentTopics(offeringId.value, {
      page: page.value,
      size,
      keyword: keyword.value || undefined,
      difficulty: difficulty.value || undefined,
    })
    topics.value = data?.list || []
    total.value = data?.total || 0
  } catch (err) {
    toastError(err, '加载题目失败')
  } finally {
    loading.value = false
  }
}

function toggle(topic) {
  if (topic.selectedByMe) return
  const idx = selected.value.indexOf(topic.id)
  if (idx >= 0) {
    selected.value.splice(idx, 1)
    return
  }
  if (selected.value.length >= remainingSlots.value) {
    toast.info('不能再多选', `你还能选 ${remainingSlots.value} 道题（每人上限 ${limit.value} 道）`)
    return
  }
  selected.value.push(topic.id)
}

async function openDetail(topic) {
  try {
    detail.value = await api.studentTopicDetail(offeringId.value, topic.id)
    detailOpen.value = true
  } catch (err) {
    toastError(err, '读取题目详情失败')
  }
}

async function submit() {
  if (!selected.value.length) return
  if (!(await confirm({
    title: '确认选题',
    message: `将提交 ${selected.value.length} 道题目的选题申请，提交后可在「我的选题」查看。`,
    confirmText: '确认提交',
  }))) return

  submitting.value = true
  try {
    const created = await api.selectTopics(offeringId.value, selected.value)
    toast.ok('选题成功', `已生成 ${created?.length ?? selected.value.length} 条选题记录`)
    selected.value = []
    await Promise.all([load(), loadOffering()])
  } catch (err) {
    toastError(err, '选题失败')
    await load()
  } finally {
    submitting.value = false
  }
}

async function previewAttachment(attachment) {
  if (attachment.contentType !== 'application/pdf') {
    api.downloadFile(attachment.fileId, attachment.filename).catch((err) => toastError(err, '下载失败'))
    return
  }
  try {
    const url = await api.topicAttachmentBlobUrl(attachment.fileId)
    window.open(url, '_blank', 'noopener')
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (err) {
    toastError(err, '预览失败')
  }
}

function humanSize(bytes) {
  const n = Number(bytes) || 0
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB'
  if (n >= 1024) return Math.round(n / 1024) + ' KB'
  return n + ' B'
}

onMounted(async () => {
  await Promise.all([loadOffering(), loadDifficulties(), load()])
})
</script>

<template>
  <div style="padding-bottom: 68px">
    <PageHeader :title="offering?.courseName || '选题中心'">
      <template #breadcrumb>
        <RouterLink to="/courses">我的课程</RouterLink>
        <Icon name="chevron-right" :size="12" />
        <span>{{ offering?.courseName || '选题中心' }}</span>
      </template>
      <template #description>
        {{ offering ? `${offering.teacherName || '任课教师'} · ${offering.semester} · ${offering.className}` : '' }}
        <span v-if="offering"> · {{ limit === Infinity ? '选题数量不限' : `每人最多 ${limit} 道题` }}</span>
      </template>
      <template #steps>
        <StepFlow :steps="STUDENT_FLOW" current="topic" dense />
      </template>
      <template #actions>
        <RouterLink to="/selections" class="btn btn--ghost"><Icon name="target" :size="15" />我的选题（{{ myCount }}）</RouterLink>
      </template>
    </PageHeader>

    <div v-if="offering && !canSelect" class="alert alert--warn" style="margin-bottom: 14px">
      <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
      {{ reasonText(offering.cannotSelectReason) || '当前不在选题时间内，仅可浏览题目。' }}
    </div>

    <div class="page-split">
      <!-- 左：难度目录（替代原顶部平铺 tab） -->
      <nav class="subnav" aria-label="题目难度">
        <div class="subnav__group">按难度</div>
        <button
          type="button"
          class="subnav__item"
          :class="{ 'is-active': difficulty === '' }"
          @click="difficulty = ''; page = 1; load()"
        >
          <span>全部难度</span>
        </button>
        <button
          v-for="d in difficulties"
          :key="d.code"
          type="button"
          class="subnav__item"
          :class="{ 'is-active': difficulty === d.code }"
          @click="difficulty = d.code; page = 1; load()"
        >
          <span>{{ d.label }}</span>
        </button>
      </nav>

      <!-- 右：搜索 + 题目列表 -->
      <div class="page-split__main">
        <div class="row row--wrap" style="margin-bottom: 16px">
          <input v-model="keyword" class="input" style="width: 220px" placeholder="搜索题目关键词" @keyup.enter="page = 1; load()" />
          <button class="btn btn--ghost btn--sm" @click="page = 1; load()"><Icon name="search" :size="14" />搜索</button>
          <span class="spacer" />
          <span class="small dim">共 {{ total }} 道题</span>
        </div>

        <div v-if="loading" class="grid grid--3">
      <div v-for="i in 6" :key="i" class="skeleton" style="height: 190px" />
    </div>

    <EmptyState v-else-if="!topics.length" icon="file" title="没有可选题目" hint="换个关键词或难度试试" />

    <div v-else class="grid grid--3">
      <article
        v-for="t in topics"
        :key="t.id"
        class="card"
        style="cursor: pointer; transition: transform 0.16s ease, box-shadow 0.16s ease"
        :style="selected.includes(t.id) ? { borderColor: 'var(--brand)', boxShadow: '0 0 0 3px var(--brand-ring)' } : {}"
        @click="openDetail(t)"
      >
        <div class="card__body" style="display: flex; flex-direction: column; height: 100%">
          <div class="row" style="align-items: flex-start">
            <span class="tag">{{ t.difficultyLabel || '未分级' }}</span>
            <span v-if="t.selectedByMe" class="pill pill--ok" style="margin-left: auto">已选</span>
            <span v-else class="pill" style="margin-left: auto">已选 {{ t.selectedCount }} 人</span>
          </div>

          <h3 style="font-size: 14px; font-weight: 650; margin-top: 10px; line-height: 1.45">
            {{ t.title }}
          </h3>
          <p class="small muted" style="margin-top: 6px; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden">
            {{ t.summary || '暂无简介' }}
          </p>

          <div class="row small dim" style="margin-top: auto; padding-top: 14px">
            <Icon name="file" :size="13" />
            <span>{{ t.attachmentCount ? `${t.attachmentCount} 个附件` : '无附件' }}</span>
          </div>

          <button
            v-if="!t.selectedByMe"
            class="btn btn--sm"
            :class="selected.includes(t.id) ? 'btn--primary' : 'btn--ghost'"
            style="margin-top: 12px"
            :disabled="!canSelect"
            @click.stop="toggle(t)"
          >
            <Icon :name="selected.includes(t.id) ? 'check' : 'plus'" :size="14" />
            {{ selected.includes(t.id) ? '已选中，点击取消' : '加入选题' }}
          </button>
        </div>
      </article>
    </div>

    <div v-if="total > size" class="row" style="margin-top: 16px; justify-content: center">
      <button class="btn btn--ghost btn--sm" :disabled="page <= 1" @click="page--; load()">上一页</button>
      <span class="small dim num" style="padding: 0 12px">{{ page }} / {{ Math.ceil(total / size) }}</span>
      <button class="btn btn--ghost btn--sm" :disabled="page >= Math.ceil(total / size)" @click="page++; load()">下一页</button>
    </div>
      </div>
    </div>

    <!-- 底部选择条 -->
    <div
      v-if="selected.length"
      style="
        position: fixed;
        left: var(--sidebar-w);
        right: 0;
        bottom: 0;
        z-index: 35;
        padding: 12px 26px;
        background: var(--surface);
        border-top: 1px solid var(--line);
        box-shadow: 0 -8px 24px rgba(16, 24, 40, 0.08);
      "
    >
      <div class="row" style="max-width: 1320px; margin: 0 auto">
        <Icon name="target" :size="16" />
        <span>已选 <strong class="num">{{ selected.length }}</strong> 道题</span>
        <span class="small dim">{{ limit === Infinity ? '（选题数量不限）' : `（本次可再选 ${remainingSlots} 道）` }}</span>
        <span class="spacer" />
        <button class="btn btn--ghost btn--sm" @click="selected = []">清空</button>
        <button class="btn btn--primary" :disabled="submitting" @click="submit">
          <span v-if="submitting" class="spinner" />提交选题
        </button>
      </div>
    </div>

    <!-- 题目详情 -->
    <Drawer v-if="detailOpen" :title="detail?.title || '题目详情'" wide @close="detailOpen = false">
      <div v-if="detail" style="display: grid; gap: 16px">
        <div class="row row--wrap">
          <span class="tag">{{ detail.difficultyLabel || '未分级' }}</span>
          <span class="pill">已选 {{ detail.selectedCount }} 人</span>
          <span v-if="detail.selectedByMe" class="pill pill--brand">你已经选了这道题</span>
        </div>

        <div>
          <div class="section-title">题目描述</div>
          <p class="muted" style="white-space: pre-line">{{ detail.description || '暂无描述' }}</p>
        </div>

        <div v-if="detail.attachments?.length">
          <div class="section-title">题目附件</div>
          <div style="display: grid; gap: 6px">
            <div v-for="a in detail.attachments" :key="a.fileId" class="row small">
              <Icon name="file" :size="14" />
              <span class="truncate">{{ a.filename }}</span>
              <span class="dim">{{ humanSize(a.size) }}</span>
              <span class="spacer" />
              <button
                v-if="a.contentType === 'application/pdf'"
                class="btn btn--ghost btn--sm"
                @click="previewAttachment(a)"
              >
                <Icon name="eye" :size="13" />预览
              </button>
              <button class="btn btn--ghost btn--sm" @click="api.downloadFile(a.fileId, a.filename).catch((e) => toastError(e, '下载失败'))">
                <Icon name="download" :size="13" />下载
              </button>
            </div>
          </div>
        </div>
      </div>

      <template #foot>
        <button class="btn btn--ghost" @click="detailOpen = false">关闭</button>
        <button
          v-if="detail && !detail.selectedByMe"
          class="btn btn--primary"
          :disabled="!canSelect"
          @click="toggle(detail); detailOpen = false"
        >
          {{ selected.includes(detail.id) ? '取消选择' : '加入选题' }}
        </button>
      </template>
    </Drawer>
  </div>
</template>
