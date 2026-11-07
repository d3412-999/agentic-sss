<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/endpoints'
import { currentRole } from '../stores/session'

const offerings = ref([])
const topics = ref([])
const selectedOfferingId = ref('')
const loading = ref(false)
const error = ref('')
const notice = ref('')
const selecting = ref('')

const isStudent = computed(() => currentRole.value === 'STUDENT')
const isTeacher = computed(() => currentRole.value === 'TEACHER')

function offeringId(row) {
  return row.offeringId || row.id || ''
}

function offeringLabel(row) {
  return `${row.courseName || '开课'} · ${row.semester || ''} ${row.className ? '· ' + row.className : ''}`
}

async function loadTopics() {
  if (!selectedOfferingId.value) return
  loading.value = true
  error.value = ''
  try {
    const data = isStudent.value
      ? await api.studentTopics(selectedOfferingId.value, { page: 1, size: 50 })
      : await api.teacherTopics(selectedOfferingId.value, { page: 1, size: 50 })
    topics.value = Array.isArray(data?.list) ? data.list : Array.isArray(data) ? data : []
  } catch (e) {
    error.value = e?.message || '题目列表加载失败'
    topics.value = []
  } finally {
    loading.value = false
  }
}

async function switchOffering() {
  notice.value = ''
  await loadTopics()
}

async function selectTopic(topic) {
  if (!isStudent.value) return
  selecting.value = topic.id
  error.value = ''
  notice.value = ''
  try {
    const created = await api.selectTopics(selectedOfferingId.value, [topic.id])
    const count = Array.isArray(created) ? created.length : 1
    notice.value = `选题成功，共 ${count} 条记录（已带 Idempotency-Key）`
    await loadTopics()
  } catch (e) {
    error.value = e?.message || '选题失败'
  } finally {
    selecting.value = ''
  }
}

onMounted(async () => {
  if (!isStudent.value && !isTeacher.value) {
    error.value = 'ADMIN 账号没有学生/教师业务列表，请改用教师或学生演示账号。'
    return
  }
  loading.value = true
  try {
    const data = isStudent.value
      ? await api.studentOfferings({ page: 1, size: 20 })
      : await api.teacherOfferings({ page: 1, size: 20 })
    offerings.value = Array.isArray(data?.list) ? data.list : []
    if (offerings.value.length) {
      selectedOfferingId.value = offeringId(offerings.value[0])
      await loadTopics()
    }
  } catch (e) {
    error.value = e?.message || '开课列表加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <h1>开课与题目</h1>
    <p class="muted" style="margin-top: 0">按当前角色调用真实后端接口，非 mock 数据</p>

    <div v-if="notice" class="alert alert--success">{{ notice }}</div>
    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div class="card">
      <div class="card__head">
        <h2>选择开课</h2>
        <span class="badge badge--gray">{{ currentRole }}</span>
      </div>
      <div v-if="offerings.length" class="field" style="margin-bottom: 0">
        <select v-model="selectedOfferingId" @change="switchOffering">
          <option v-for="row in offerings" :key="offeringId(row)" :value="offeringId(row)">
            {{ offeringLabel(row) }}
          </option>
        </select>
      </div>
      <div v-else-if="!loading" class="empty">暂无开课数据（确认 Flyway V1 / V2 已执行）</div>
    </div>

    <div class="card">
      <div class="card__head">
        <h2>题目列表</h2>
        <button class="btn btn--ghost btn--sm" type="button" :disabled="loading" @click="loadTopics">刷新</button>
      </div>

      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!topics.length" class="empty">该开课下暂无题目</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>题目</th><th>难度</th><th>名额</th><th>标签</th><th>状态</th><th v-if="isStudent">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in topics" :key="t.id">
              <td>
                <strong>{{ t.title }}</strong>
                <div class="muted" v-if="t.summary || t.description">{{ t.summary || t.description }}</div>
              </td>
              <td>{{ t.difficulty || '—' }}</td>
              <td>
                <template v-if="t.remaining !== undefined">{{ t.remaining }}</template>
                <template v-else>{{ t.selectedCount ?? 0 }} / {{ t.maxSelectCount ?? '—' }}</template>
              </td>
              <td>
                <span v-for="tag in t.tags || []" :key="tag" class="tag">{{ tag }}</span>
                <span v-if="!t.tags?.length" class="muted">—</span>
              </td>
              <td>
                <span v-if="t.status" class="badge" :class="t.status === 'PUBLISHED' ? 'badge--ok' : 'badge--gray'">
                  {{ t.status }}
                </span>
                <span v-else-if="t.selectedByMe" class="badge badge--ok">已选</span>
                <span v-else class="muted">—</span>
              </td>
              <td v-if="isStudent">
                <button
                  class="btn btn--sm"
                  type="button"
                  :disabled="t.selectedByMe || t.remaining === 0 || selecting === t.id"
                  @click="selectTopic(t)"
                >
                  {{ t.selectedByMe ? '已选' : selecting === t.id ? '提交中' : '选题' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
