import { session, saveTokens, clearSession } from '../stores/session'

// 所有请求走 Vite 代理（/api -> VITE_API_TARGET），浏览器端不写死后端地址。
export const API_BASE = '/api/v1'

export class ApiError extends Error {
  constructor(message, code = -1, status = 0, traceId = '', data = null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.traceId = traceId
    this.data = data
  }
}

async function parseBody(res) {
  // MiscController 的 cancelTask 返回 204 且无响应体
  if (res.status === 204) return null
  const text = await res.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

async function tryRefresh() {
  if (!session.refreshToken) return false
  try {
    const res = await fetch(`${API_BASE}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ refreshToken: session.refreshToken }),
    })
    const payload = await parseBody(res)
    if (res.ok && payload?.code === 0 && payload.data?.accessToken) {
      saveTokens(payload.data)
      return true
    }
  } catch {
    // 刷新失败按未登录处理
  }
  return false
}

function buildHeaders({ auth = true, idempotencyKey, body, headers = {} }) {
  const result = { Accept: 'application/json', ...headers }
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData
  if (body !== undefined && !isForm) result['Content-Type'] = 'application/json'
  if (idempotencyKey) result['Idempotency-Key'] = idempotencyKey
  if (auth && session.accessToken) result.Authorization = `Bearer ${session.accessToken}`
  return result
}

/**
 * 统一请求封装。
 * - 自动带 Authorization: Bearer <accessToken>
 * - 统一拆 Result {code,message,data,traceId}，code !== 0 抛 ApiError
 * - 401 先尝试一次 refresh，仍失败则清空会话并广播 sss:unauthorized
 * - idempotencyKey 会变成 Idempotency-Key 头（后端 @Idempotent 接口需要）
 */
export async function request(path, options = {}) {
  const { method = 'GET', body, auth = true, idempotencyKey, _retried = false } = options
  const headers = buildHeaders(options)
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData

  let res
  try {
    res = await fetch(API_BASE + path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
    })
  } catch {
    throw new ApiError('无法连接后端，请确认后端已启动，且 vue/.env 的 VITE_API_TARGET 指向正确端口', -1, 0)
  }

  const traceId = res.headers.get('X-Trace-Id') || ''
  const payload = await parseBody(res)

  if (res.status === 401 && auth && !_retried && (await tryRefresh())) {
    return request(path, { ...options, _retried: true })
  }

  if (res.status === 401 && auth) {
    // 只有确实带了令牌才认为会话失效；没有令牌的 401 交给调用方处理，
    // 避免后台轮询在登录前的竞态把用户踢下线。
    if (session.accessToken) {
      clearSession()
      window.dispatchEvent(new CustomEvent('sss:unauthorized'))
    }
  }

  if (!res.ok) {
    throw new ApiError(
      payload?.message || `HTTP ${res.status}`,
      payload?.code ?? res.status,
      res.status,
      traceId,
      payload?.data ?? null,
    )
  }

  if (payload && typeof payload.code === 'number' && payload.code !== 0) {
    throw new ApiError(payload.message || '业务错误', payload.code, res.status, traceId, payload.data ?? null)
  }

  return payload ? payload.data : null
}

/**
 * 带进度的上传（XMLHttpRequest，fetch 无法报告上传进度）。
 * 用于源码 zip / 报告 pdf 这类大文件。
 */
export function upload(path, { file, fieldName = 'file', query = {}, onProgress, method = 'POST' } = {}) {
  return new Promise((resolve, reject) => {
    const qs = toQuery(query)
    const xhr = new XMLHttpRequest()
    xhr.open(method, `${API_BASE}${path}${qs}`)
    if (session.accessToken) xhr.setRequestHeader('Authorization', `Bearer ${session.accessToken}`)
    xhr.setRequestHeader('Accept', 'application/json')

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onerror = () => reject(new ApiError('上传失败：网络中断', -1, 0))
    xhr.onload = () => {
      let payload = null
      try {
        payload = xhr.responseText ? JSON.parse(xhr.responseText) : null
      } catch {
        payload = null
      }
      const traceId = xhr.getResponseHeader('X-Trace-Id') || ''
      if (xhr.status >= 200 && xhr.status < 300 && (!payload || payload.code === 0)) {
        resolve(payload ? payload.data : null)
      } else if (xhr.status === 401) {
        clearSession()
        window.dispatchEvent(new CustomEvent('sss:unauthorized'))
        reject(new ApiError('登录状态已失效，请重新登录', 40101, 401, traceId))
      } else {
        reject(new ApiError(payload?.message || `上传失败 HTTP ${xhr.status}`, payload?.code ?? xhr.status, xhr.status, traceId, payload?.data))
      }
    }

    const form = new FormData()
    form.append(fieldName, file)
    xhr.send(form)
  })
}

/** 带鉴权头的文件下载：拉成 blob 再触发浏览器保存。 */
export async function download(path, filename = '') {
  const res = await fetch(API_BASE + path, {
    headers: buildHeaders({ auth: true }),
    credentials: 'same-origin',
  })
  if (!res.ok) {
    throw new ApiError(`下载失败 HTTP ${res.status}`, res.status, res.status)
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename || 'download'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 4000)
}

export function toQuery(params = {}) {
  const qs = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') qs.append(key, String(value))
  })
  const s = qs.toString()
  return s ? `?${s}` : ''
}

export function newIdempotencyKey() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return `idem-${Date.now()}-${Math.random().toString(16).slice(2)}`
}
