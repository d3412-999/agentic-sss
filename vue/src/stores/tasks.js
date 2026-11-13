import { computed, reactive } from 'vue'
import { api } from '../api/endpoints'
import { toast } from './toast'
import { namespace } from '../utils/storage'
import { currentUserId } from '../persist/identity'

/**
 * 后端没有「任务列表」接口，只有 GET /tasks/{id}。
 * 所以前端自己记录本机发起过的异步任务（重算成绩 / 导出 CSV / 重新评审），
 * 定时轮询状态，形成一个跨页面的「任务中心」。
 *
 * 持久化走 utils/storage 的统一封装（原先直接裸写 localStorage，缺版本/隔离/TTL）：
 * - key 带 userId，换账号不会看到上一个人的任务；
 * - 带 schema 版本与 7 天 TTL，旧格式自动丢弃、过期自动清理；
 * - 读写在 storage 层里已经包了 try/catch，隐私模式退化为内存态。
 */
const ns = () => namespace('async', currentUserId())

const ACTIVE = new Set(['PENDING', 'RUNNING'])
const TTL_MS = 7 * 24 * 60 * 60 * 1000

export const tasks = reactive({ items: [] })

let hydrated = false

/**
 * 从本地恢复任务列表（异步）。
 * 由 persist/resume.js 在路由进入时统一调用一次，页面组件不必各自触发。
 */
export async function hydrateTasks() {
  if (hydrated) return
  hydrated = true
  const saved = await ns().get('center')
  if (saved && Array.isArray(saved.items)) {
    // 合并而不是覆盖：极少数情况下首屏还没导航就已经 track 了新任务
    const known = new Set(tasks.items.map((t) => t.taskId))
    const restored = saved.items.filter((t) => t && t.taskId && !known.has(t.taskId))
    tasks.items = [...tasks.items, ...restored]
  }
  resumeTracking()
}

export const activeTasks = computed(() => tasks.items.filter((t) => ACTIVE.has(t.status)))
export const activeCount = computed(() => activeTasks.value.length)

async function persist() {
  if (!tasks.items.length) {
    await ns().remove('center')
    return
  }
  await ns().set('center', { items: tasks.items.slice(0, 24) }, { ttlMs: TTL_MS })
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
    void persist()
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
    void persist()
  }
  refresh(taskId)
  ensurePolling()
}

export async function cancelTask(taskId) {
  try {
    await api.cancelTask(taskId)
    const item = tasks.items.find((t) => t.taskId === taskId)
    if (item) item.status = 'CANCELED'
    void persist()
  } catch {
    // 取消失败时保持原状，下一次轮询会刷新到真实状态
  }
}

export function clearFinished() {
  tasks.items = tasks.items.filter((t) => ACTIVE.has(t.status))
  void persist()
}

/** 恢复上次未完成的任务轮询（刷新页面后继续跟进）。 */
export function resumeTracking() {
  if (activeTasks.value.length) {
    activeTasks.value.forEach((t) => refresh(t.taskId))
    ensurePolling()
  }
}
