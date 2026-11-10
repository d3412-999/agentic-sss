<script setup>
import { onMounted, ref } from 'vue'
import Icon from '../components/Icon.vue'
import EmptyState from '../components/EmptyState.vue'
import Pager from '../components/Pager.vue'
import { api } from '../api/endpoints'
import { toast, toastError } from '../stores/toast'

const loading = ref(true)
const data = ref({ list: [], total: 0, unreadCount: 0 })
const unreadOnly = ref(false)
const page = ref(1)
const size = 20
const busy = ref(false)

const TYPE_META = {
  SELECTION: { icon: 'target', tone: 'pill--brand' },
  OFFERING: { icon: 'book', tone: 'pill--info' },
  SUBMISSION: { icon: 'upload', tone: 'pill--info' },
  ANALYSIS: { icon: 'wand', tone: 'pill--brand' },
  GRADE: { icon: 'chart', tone: 'pill--ok' },
  DEFENSE: { icon: 'flag', tone: 'pill--warn' },
  SYSTEM: { icon: 'shield', tone: '' },
}

function meta(type) {
  return TYPE_META[type] || { icon: 'bell', tone: '' }
}

function timeText(value) {
  if (!value) return ''
  const diff = Date.now() - new Date(value).getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d} 天前`
  return new Date(value).toLocaleDateString('zh-CN')
}

async function load() {
  loading.value = true
  try {
    data.value = await api.notifications({ unreadOnly: unreadOnly.value, page: page.value, size })
  } catch (err) {
    toastError(err, '加载通知失败')
  } finally {
    loading.value = false
  }
}

async function markRead(item) {
  if (item.read) return
  try {
    await api.markNotificationRead(item.id)
    item.read = true
    data.value.unreadCount = Math.max(0, (data.value.unreadCount || 0) - 1)
  } catch (err) {
    toastError(err, '操作失败')
  }
}

async function readAll() {
  busy.value = true
  try {
    const result = await api.readAllNotifications()
    toast.ok('已全部标记为已读', `${result?.updated ?? 0} 条`)
    await load()
  } catch (err) {
    toastError(err, '操作失败')
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>站内通知</h1>
        <p>开课发布、选题结果、评审完成、成绩发布都会在这里提醒。未读 {{ data.unreadCount || 0 }} 条。</p>
      </div>
      <div class="page-head__actions">
        <button class="btn btn--ghost btn--sm" :disabled="busy || !data.unreadCount" @click="readAll">
          <Icon name="check" :size="14" />全部已读
        </button>
        <button class="btn btn--soft btn--sm" @click="unreadOnly = !unreadOnly; page = 1; load()">
          <Icon name="filter" :size="14" />{{ unreadOnly ? '只看未读' : '全部通知' }}
        </button>
      </div>
    </div>

    <div class="card">
      <div v-if="loading" class="card__body"><div class="skeleton" style="height: 220px" /></div>
      <EmptyState
        v-else-if="!data.list?.length"
        icon="bell"
        :title="unreadOnly ? '没有未读通知' : '还没有通知'"
        hint="教师发布开课或成绩后会在这里收到提醒"
      />
      <div v-else class="card__body card__body--flush">
        <div
          v-for="n in data.list"
          :key="n.id"
          class="list-row"
          :class="{ 'is-unread': !n.read }"
          style="cursor: pointer; align-items: flex-start"
          @click="markRead(n)"
        >
          <div class="empty__icon" style="width: 34px; height: 34px; border-radius: 10px">
            <Icon :name="meta(n.type).icon" :size="16" />
          </div>
          <div style="min-width: 0; flex: 1">
            <div class="row" style="gap: 8px">
              <span class="list-row__title">{{ n.title }}</span>
              <span class="pill" :class="meta(n.type).tone">{{ n.type }}</span>
              <span class="spacer small dim">{{ timeText(n.createdAt) }}</span>
            </div>
            <div class="small muted" style="margin-top: 3px; white-space: pre-line">{{ n.content }}</div>
            <div v-if="n.link" class="small" style="margin-top: 4px; color: var(--brand)">{{ n.link }}</div>
          </div>
        </div>
      </div>
      <Pager v-if="data.total > size" v-model:page="page" :size="size" :total="data.total" @update:page="load" />
    </div>
  </div>
</template>
