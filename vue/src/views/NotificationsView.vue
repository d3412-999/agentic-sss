<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api/endpoints'

const list = ref([])
const total = ref(0)
const unreadCount = ref(0)
const unreadOnly = ref(false)
const loading = ref(false)
const error = ref('')
const notice = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.notifications({ unreadOnly: unreadOnly.value, page: 1, size: 20 })
    list.value = Array.isArray(data?.list) ? data.list : []
    total.value = data?.total ?? list.value.length
    unreadCount.value = data?.unreadCount ?? 0
  } catch (e) {
    error.value = e?.message || '通知加载失败'
  } finally {
    loading.value = false
  }
}

function formatTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleString('zh-CN', { hour12: false })
}

async function markRead(row) {
  try {
    await api.markNotificationRead(row.id)
    notice.value = '已标记为已读'
    await load()
  } catch (e) {
    error.value = e?.message || '操作失败'
  }
}

async function readAll() {
  try {
    const data = await api.readAllNotifications()
    notice.value = `已全部标记为已读（${data?.updated ?? 0} 条）`
    await load()
  } catch (e) {
    error.value = e?.message || '操作失败'
  }
}

onMounted(load)
</script>

<template>
  <div>
    <h1>站内通知</h1>
    <p class="muted" style="margin-top: 0">共 {{ total }} 条 · 未读 {{ unreadCount }} 条</p>

    <div v-if="notice" class="alert alert--success">{{ notice }}</div>
    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div class="card">
      <div class="card__head">
        <h2>通知列表</h2>
        <div style="display: flex; gap: 8px; align-items: center">
          <label style="margin: 0; display: flex; align-items: center; gap: 6px; font-size: 13px">
            <input
              type="checkbox"
              :checked="unreadOnly"
              style="width: auto"
              @change="unreadOnly = $event.target.checked; load()"
            />
            仅未读
          </label>
          <button class="btn btn--ghost btn--sm" type="button" :disabled="loading" @click="load">刷新</button>
          <button class="btn btn--sm" type="button" :disabled="loading" @click="readAll">全部已读</button>
        </div>
      </div>

      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!list.length" class="empty">暂无通知</div>
      <div v-else class="table-wrap">
        <table>
          <thead><tr><th style="width: 70px">状态</th><th>标题</th><th>内容</th><th>时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in list" :key="row.id">
              <td>
                <span class="badge" :class="row.read ? 'badge--gray' : 'badge--warn'">
                  {{ row.read ? '已读' : '未读' }}
                </span>
              </td>
              <td><strong>{{ row.title }}</strong><div class="muted">{{ row.type }}</div></td>
              <td class="muted">{{ row.content || '—' }}</td>
              <td class="muted">{{ formatTime(row.createdAt) }}</td>
              <td>
                <button v-if="!row.read" class="btn btn--ghost btn--sm" type="button" @click="markRead(row)">
                  标为已读
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
