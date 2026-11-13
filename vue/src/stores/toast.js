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

/**
 * 把后端 ApiError 统一变成一条错误提示，返回原始错误便于调用方继续处理。
 * traceId 用完整值（日志里是按 traceId 全文检索的，截断后就搜不到了）。
 */
export function toastError(err, fallback = '操作失败') {
  toast.error(fallback, errorText(err, fallback))
  return err
}

/**
 * 行内（非 toast）错误文案：字段级错误 > 后端 message > 兜底文案，末尾附 traceId。
 * 排障时用户只要把这串贴回来，就能直接用 traceId 在日志里定位到那一次请求。
 */
export function errorText(err, fallback = '操作失败') {
  const fields = err?.data?.errors
  const detail = Array.isArray(fields) && fields.length
    ? fields.map((f) => f.message || f.field).filter(Boolean).join('；')
    : ''
  const message = detail || err?.message || fallback
  const trace = err?.traceId ? `（traceId: ${err.traceId}）` : ''
  return message + trace
}
