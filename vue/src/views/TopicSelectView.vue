<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import EmptyState from '../components/EmptyState.vue'
import Drawer from '../components/Drawer.vue'
import { api, reasonText, DIFFICULTY_LABEL } from '../api/endpoints'
import { confirm } from '../stores/confirm'
import { toast, toastError } from '../stores/toast'

const route = useRoute()
const router = useRouter()
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

const DIFFICULTIES = [
  { key: '', label: '全部难度' },
  ...['EASY', 'MEDIUM', 'HARD'].map((key) => ({ key, label: DIFFICULTY_LABEL[key] })),
]

const limit = computed(() => offering.value?.topicSelectLimit ?? 3)
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
  if (idx >= 0) selected.value.splice(idx, 1)
  else {
    if (selected.value.length >= remainingSlots.value) {
      toast.info('不能再多选', `你还能选 ${remainingSlots.value} 道题（每人上限 ${limit.value} 道）`)
      return
    }
    selected.value.push(topic.id)
  }
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
    message: `将提交 ${selected.value.length} 道题目的选题申请。名额由数据库原子预占，抢满即失败，请确认。`,
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

function fill(ratio) {
  return Math.min(100, Math.round((ratio || 0) * 100))
}

function tone(topic) {
  if (!topic.remaining) return 'danger'
  if (topic.remaining <= 1) return 'warn'
  return 'ok'
}

onMounted(async () => {
  await Promise.all([loadOffering(), load()])
})
</script>

<template>
  <div style="padding-bottom: 68px">
    <div class="page-head">
      <div>
        <div class="row small dim" style="margin-bottom: 6px">
          <RouterLink to="/courses">我的课程</RouterLink>
          <Icon name="chevron-right" :size="12" />
          <span>{{ offering?.courseName || '选题中心' }}</span>
        </div>
        <h1>{{ offering?.courseName || '选题中心' }}</h1>
        <p>
          {{ offering ? `${offering.teacherName || '任课教师'} · ${offering.semester} · ${offering.className}` : '' }}
          <span v-if="offering"> · 每人最多 {{ limit }} 道题</span>
        </p>
      </div>
      <div class="page-head__actions">
        <RouterLink to="/selections" class="btn btn--ghost"><Icon name="target" :size="15" />我的选题（{{ myCount }}）</RouterLink>
      </div>
    </div>

    <div v-if="offering && !canSelect" class="alert alert--warn" style="margin-bottom: 14px">
      <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
      {{ reasonText(offering.cannotSelectReason) || '当前不在选题时间内，仅可浏览题目。' }}
    </div>

    <div class="row row--wrap" style="margin-bottom: 16px">
      <div class="tabs">
        <button
          v-for="d in DIFFICULTIES"
          :key="d.key"
          class="tabs__item"
          :class="{ 'is-active': difficulty === d.key }"
          @click="difficulty = d.key; page = 1; load()"
        >
          {{ d.label }}
        </button>
      </div>
      <div class="spacer" />
      <input v-model="keyword" class="input" style="width: 200px" placeholder="搜索题目关键词" @keyup.enter="page = 1; load()" />
      <button class="btn btn--ghost btn--sm" @click="page = 1; load()"><Icon name="search" :size="14" />搜索</button>
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
            <span class="tag">{{ DIFFICULTY_LABEL[t.difficulty] || t.difficulty || '不限' }}</span>
            <span v-if="t.selectedByMe" class="pill pill--ok" style="margin-left: auto">已选</span>
            <span
              v-else
              class="pill"
              :class="`pill--${tone(t)}`"
              style="margin-left: auto"
            >
              剩余 {{ t.remaining ?? 0 }}
            </span>
          </div>

          <h3 style="font-size: 14px; font-weight: 650; margin-top: 10px; line-height: 1.45">
            {{ t.title }}
          </h3>
          <p class="small muted" style="margin-top: 6px; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden">
            {{ t.summary || '暂无简介' }}
          </p>

          <div class="row row--wrap" style="margin-top: 10px; gap: 4px">
            <span v-for="tag in (t.tags || []).slice(0, 3)" :key="tag" class="tag">{{ tag }}</span>
          </div>

          <div style="margin-top: auto; padding-top: 14px">
            <div class="row small dim">
              <span>容量 {{ t.selectedCount }}/{{ t.maxSelectCount }}</span>
              <span class="spacer" />
              <span class="num">{{ fill(t.selectedCount / Math.max(1, t.maxSelectCount)) }}%</span>
            </div>
            <div class="bar" style="margin-top: 6px">
              <div
                class="bar__fill"
                :style="{
                  width: fill(t.selectedCount / Math.max(1, t.maxSelectCount)) + '%',
                  background: t.selectedByMe
                    ? 'linear-gradient(90deg, #22c55e, #14b8a6)'
                    : t.remaining
                      ? ''
                      : 'var(--danger)',
                }"
              />
            </div>
          </div>

          <button
            v-if="!t.selectedByMe"
            class="btn btn--sm"
            :class="selected.includes(t.id) ? 'btn--primary' : 'btn--ghost'"
            style="margin-top: 12px"
            :disabled="!t.remaining"
            @click.stop="toggle(t)"
          >
            <Icon :name="selected.includes(t.id) ? 'check' : 'plus'" :size="14" />
            {{ selected.includes(t.id) ? '已选中，点击取消' : t.remaining ? '加入选题' : '名额已满' }}
          </button>
        </div>
      </article>
    </div>

    <div v-if="total > size" class="row" style="margin-top: 16px; justify-content: center">
      <button class="btn btn--ghost btn--sm" :disabled="page <= 1" @click="page--; load()">上一页</button>
      <span class="small dim num" style="padding: 0 12px">{{ page }} / {{ Math.ceil(total / size) }}</span>
      <button class="btn btn--ghost btn--sm" :disabled="page >= Math.ceil(total / size)" @click="page++; load()">下一页</button>
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
        <span class="small dim">（本次可再选 {{ remainingSlots }} 道）</span>
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
          <span class="tag">{{ DIFFICULTY_LABEL[detail.difficulty] || detail.difficulty }}</span>
          <span class="pill" :class="detail.remaining ? 'pill--ok' : 'pill--danger'">剩余 {{ detail.remaining }} / {{ detail.maxSelectCount }}</span>
          <span v-if="detail.selectedByMe" class="pill pill--brand">你已经选了这道题</span>
        </div>

        <div>
          <div class="section-title">题目描述</div>
          <p class="muted" style="white-space: pre-line">{{ detail.description || '暂无描述' }}</p>
        </div>

        <div v-if="detail.requirements">
          <div class="section-title">交付要求</div>
          <p class="muted" style="white-space: pre-line">{{ detail.requirements }}</p>
        </div>

        <div v-if="detail.tags?.length">
          <div class="section-title">标签</div>
          <div class="row row--wrap"><span v-for="t in detail.tags" :key="t" class="tag">{{ t }}</span></div>
        </div>

        <div v-if="detail.attachments?.length">
          <div class="section-title">附件</div>
          <div style="display: grid; gap: 6px">
            <div v-for="a in detail.attachments" :key="a.fileId" class="row small">
              <Icon name="file" :size="14" />
              <span class="truncate">{{ a.filename }}</span>
              <button class="btn btn--ghost btn--sm spacer" @click="api.downloadFile(a.fileId, a.filename).catch((e) => toastError(e, '下载失败'))">
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
          :disabled="!detail.remaining || !canSelect"
          @click="toggle(detail); detailOpen = false"
        >
          {{ selected.includes(detail.id) ? '取消选择' : '加入选题' }}
        </button>
      </template>
    </Drawer>
  </div>
</template>
