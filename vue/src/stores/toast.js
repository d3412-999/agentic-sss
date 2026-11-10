import { reactive } from 'vue'

let seq = 0
export const toasts = reactive([])

function push(type, title, message, ttl = 4200) {
  const id = ++seq
  toasts.push({ id, type, title, message })
  if (ttl > 0) setTimeout(() => dismiss(id), ttl)
  return id
}

export function dismiss(id) {
  const i = toasts.findIndex((t) => t.id === id)
  if (i >= 0) toasts.splice(i, 1)
}

export const toast = {
  ok: (title, message) => push('ok', title, message),
  error: (title, message) => push('error', title, message, 6000),
  info: (title, message) => push('info', title, message),
}

/** 把后端 ApiError 统一变成一条错误提示，返回原始错误便于调用方继续处理。 */
export function toastError(err, fallback = '操作失败') {
  const msg = err?.message || fallback
  const trace = err?.traceId ? `（traceId: ${String(err.traceId).slice(0, 12)}）` : ''
  toast.error(fallback, msg + trace)
  return err
}
