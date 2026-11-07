import { computed, reactive } from 'vue'
import { api } from '../api/endpoints'
import { toast } from './toast'

/**
 * 后端没有「任务列表」接口，只有 GET /tasks/{id}。
 * 所以前端自己记录本机发起过的异步任务（重算成绩 / 导出 CSV / 重新评审），
 * 定时轮询状态，形成一个跨页面的「任务中心」。数据只存在本机。
 */
const KEY = 'sss.tasks'
const ACTIVE = new Set(['PENDING', 'RUNNING'])

function load() {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || '[]')
    return Array.isArray(raw) ? raw : []
  } catch {
    return []
  }
}

export const tasks = reactive({ items: load() })

export const activeTasks = computed(() => tasks.items.filter((t) => ACTIVE.has(t.status)))
export const activeCount = computed(() => activeTasks.value.length)

function persist() {
  localStorage.setItem(KEY, JSON.stringify(tasks.items.slice(0, 24)))
}

let timer = null

function ensurePolling() {
  if (timer) return
  timer = setInterval(() => {
    if (!activeTasks.value.length) {
      clearInterval(timer)
      timer = null
      return
    }
    activeTasks.value.forEach((t) => refresh(t.taskId))
  }, 2500)
}

export async function refresh(taskId) {
  const item = tasks.items.find((t) => t.taskId === taskId)
  if (!item) return null
  try {
    const data = await api.task(taskId)
    item.status = data.status || item.status
    item.progress = data.progress ?? item.progress ?? 0
    item.type = data.type || item.type
    item.error = data.error || ''
    item.result = data.result ?? item.result
    item.finishedAt = data.finishedAt || item.finishedAt
    persist()
    if (data.status === 'SUCCEEDED' && !item.notified) {
      item.notified = true
      toast.ok('任务完成', item.label)
    }
    if (data.status === 'FAILED' && !item.notified) {
      item.notified = true
      toast.error('任务失败', data.error || item.label)
    }
    return item
  } catch {
    return null
  }
}

/** 记录一个刚受理的任务并开始轮询。 */
export function track(taskId, { label = '后台任务', kind = 'TASK', offeringId = '' } = {}) {
  if (!taskId) return
  if (!tasks.items.some((t) => t.taskId === taskId)) {
    tasks.items.unshift({
      taskId,
      label,
      kind,
      offeringId,
      status: 'PENDING',
      progress: 0,
      createdAt: new Date().toISOString(),
      error: '',
      notified: false,
    })
    persist()
  }
  refresh(taskId)
  ensurePolling()
}

export async function cancelTask(taskId) {
  try {
    await api.cancelTask(taskId)
    const item = tasks.items.find((t) => t.taskId === taskId)
    if (item) item.status = 'CANCELED'
    persist()
  } catch {
    // 取消失败时保持原状，下一次轮询会刷新到真实状态
  }
}

export function clearFinished() {
  tasks.items = tasks.items.filter((t) => ACTIVE.has(t.status))
  persist()
}

/** 恢复上次未完成的任务轮询（刷新页面后继续跟进）。 */
export function resumeTracking() {
  if (activeTasks.value.length) {
    activeTasks.value.forEach((t) => refresh(t.taskId))
    ensurePolling()
  }
}
