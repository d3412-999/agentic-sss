<template>
  <div>
    <div class="crumb">
      <RouterLink to="/student/courses">选择课程</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <span>{{ offering.courseName }}</span>
    </div>

    <div class="page-head">
      <div>
        <div class="page-title">选择题目</div>
        <div class="page-sub">
          选题窗口 {{ offering.selectStart }} ~ {{ offering.selectEnd }} · 最多可选
          {{ offering.topicLimit }} 道 · 当前已选 {{ mine.length }} 道
        </div>
      </div>
      <div class="page-actions">
        <RouterLink class="btn btn-outline" to="/student/selections">
          <AppIcon name="list" :size="18" />
          我的选题
        </RouterLink>
      </div>
    </div>

    <div class="toolbar card">
      <label class="search" style="width: 300px">
        <AppIcon name="search" :size="17" />
        <input v-model="keyword" placeholder="搜索题目、技术栈" />
      </label>
      <div class="row gap-6 wrap">
        <button
          v-for="d in difficulties"
          :key="d.value"
          class="btn btn-sm"
          :class="difficulty === d.value ? 'btn-tonal' : 'btn-text'"
          @click="difficulty = d.value"
        >
          {{ d.label }}
        </button>
      </div>
    </div>

    <div class="grid g2 mt-16">
      <article
        v-for="t in visible"
        :key="t.id"
        class="card topic"
        :class="{
          selected: picked.includes(t.id),
          disabled: isMine(t.id) || t.selected >= t.max,
          full: t.selected >= t.max
        }"
        @click="toggle(t)"
      >
        <div class="between">
          <div class="row gap-8">
            <span class="check-box" :class="{ on: picked.includes(t.id) || isMine(t.id) }">
              <AppIcon v-if="picked.includes(t.id) || isMine(t.id)" name="check" :size="13" />
            </span>
            <span class="strong">{{ t.title }}</span>
          </div>
          <div class="row gap-6">
            <StatusChip :status="t.difficulty" />
            <StatusChip v-if="isMine(t.id)" label="已选" status="SELECTED" />
          </div>
        </div>

        <p class="topic-desc">{{ t.summary }}</p>

        <div class="row gap-6 wrap">
          <span v-for="tag in t.tags" :key="tag" class="chip outline">{{ tag }}</span>
        </div>

        <div class="topic-foot">
          <div class="grow">
            <div class="between tiny">
              <span class="dim">已选 {{ t.selected }} / {{ t.max }}</span>
              <span :class="t.selected >= t.max ? 'full-text' : 'dim'">
                {{ t.selected >= t.max ? '已满' : `剩余 ${t.max - t.selected} 个名额` }}
              </span>
            </div>
            <div class="bar thin mt-8">
              <span
                :style="{ width: Math.min(100, (t.selected / t.max) * 100) + '%' }"
                :class="{ warn: t.selected >= t.max }"
              />
            </div>
          </div>
        </div>
      </article>
    </div>

    <div class="select-bar-wrap">
      <div class="select-bar card">
        <div class="row gap-12">
          <span class="sel-count">{{ picked.length + mine.length }}</span>
          <div>
            <div class="small strong">
              已选 {{ picked.length + mine.length }} / {{ offering.topicLimit }} 道题目
              <span v-if="picked.length" class="dim">（本次新增 {{ picked.length }} 道）</span>
            </div>
            <div class="tiny dim mt-4">{{ pickedTitles || '点击题目卡片进行选择' }}</div>
          </div>
        </div>
        <div class="row gap-8">
          <button class="btn btn-text" :disabled="!picked.length" @click="picked = []">清空</button>
          <button class="btn btn-primary" :disabled="!picked.length" @click="confirmOpen = true">
            确认选题
            <AppIcon name="arrowRight" :size="17" />
          </button>
        </div>
      </div>
    </div>

    <ModalDialog v-model="confirmOpen" title="确认选题" subtitle="确认后可在选题截止前退选">
      <div class="col gap-12">
        <div v-for="id in picked" :key="id" class="confirm-row">
          <AppIcon name="checkCircle" :size="18" />
          <span>{{ topicOf(id)?.title }}</span>
        </div>
        <div class="hint mt-8">
          确认后系统会为你创建选题记录，每道题需分别提交源码与课程设计报告。
        </div>
      </div>
      <template #footer>
        <button class="btn btn-outline" @click="confirmOpen = false">取消</button>
        <button class="btn btn-primary" @click="submit">确认选择</button>
      </template>
    </ModalDialog>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import StatusChip from '@/components/StatusChip.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import { offering, selections, topics } from '@/data/mock'

const keyword = ref('')
const difficulty = ref('all')
const picked = ref([])
const confirmOpen = ref(false)

const difficulties = [
  { value: 'all', label: '全部难度' },
  { value: 'EASY', label: '简单' },
  { value: 'MEDIUM', label: '中等' },
  { value: 'HARD', label: '较难' }
]

const mine = computed(() => selections.filter((s) => s.status !== 'CANCELED'))
const remainingSlots = computed(() => offering.topicLimit - mine.value.length)

const visible = computed(() =>
  topics.filter((t) => {
    const kw = keyword.value.trim()
    const matchKw = !kw || t.title.includes(kw) || t.tags.some((x) => x.includes(kw))
    const matchDiff = difficulty.value === 'all' || t.difficulty === difficulty.value
    return t.status === 'PUBLISHED' && matchKw && matchDiff
  })
)

const pickedTitles = computed(() => picked.value.map((id) => topicOf(id)?.title).join('、'))

function topicOf(id) {
  return topics.find((t) => t.id === id)
}

function isMine(id) {
  return mine.value.some((s) => s.topicId === id)
}

function toggle(t) {
  if (isMine(t.id) || t.selected >= t.max) return
  const i = picked.value.indexOf(t.id)
  if (i >= 0) {
    picked.value.splice(i, 1)
    return
  }
  if (picked.value.length >= remainingSlots.value) return
  picked.value.push(t.id)
}

function submit() {
  confirmOpen.value = false
  picked.value = []
}
</script>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 16px; flex-wrap: wrap; }

.topic {
  position: relative;
  padding: 18px 20px;
  background: var(--c-surface);
  border-color: var(--c-line-2);
  cursor: pointer;
  transition: border-color .16s var(--ease), box-shadow .16s var(--ease), transform .16s var(--ease), background .16s var(--ease);
}
.topic:hover { border-color: var(--c-line); box-shadow: var(--shadow-1); }
.topic.selected { border-color: var(--c-blue); background: linear-gradient(180deg, rgba(26, 115, 232, .05), var(--c-surface) 45%); box-shadow: 0 0 0 1px var(--c-blue); }
.topic.disabled { cursor: not-allowed; opacity: .72; }
.topic.disabled:hover { transform: none; box-shadow: none; border-color: var(--c-line-2); }
.topic.full { background: var(--c-surface); }

.check-box {
  width: 18px; height: 18px;
  border: 1.5px solid var(--c-line);
  border-radius: 5px;
  display: grid; place-items: center;
  color: #fff;
  flex: none;
  transition: background .15s var(--ease), border-color .15s var(--ease);
}
.check-box.on { background: var(--c-blue); border-color: var(--c-blue); }

.topic-desc { margin: 12px 0; font-size: 14px; line-height: 1.7; color: var(--c-text-2); }

.topic-foot { margin-top: 16px; }
.bar span.warn { background: #f29900; }
.full-text { color: var(--c-amber-700); }

.select-bar-wrap {
  position: sticky;
  bottom: 16px;
  margin-top: 20px;
  z-index: 20;
}
.select-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 12px 16px;
  background: var(--c-surface);
  border-color: var(--c-line-2);
  box-shadow: var(--shadow-2);
  flex-wrap: wrap;
}
.sel-count {
  width: 40px; height: 40px;
  border-radius: 12px;
  display: grid; place-items: center;
  background: var(--c-blue-50);
  color: var(--c-blue-700);
  font-size: 19px; font-weight: 600;
}

.confirm-row { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: 10px; background: var(--c-grey-50); font-size: 14.5px; }
.confirm-row svg { color: var(--c-green); flex: none; }
</style>
