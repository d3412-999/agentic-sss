import { session, saveTokens, clearSession } from '../stores/session'

// 所有请求走 Vite 代理（/api -> VITE_API_TARGET），浏览器端不写死后端地址。
export const API_BASE = '/api/v1'

export class ApiError extends Error {
  constructor(message, code = -1, status = 0, traceId = '') {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.traceId = traceId
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

/**
 * 统一请求封装。
 * - 自动带 Authorization: Bearer <accessToken>（JwtAuthenticationFilter 要求的前缀）
 * - 统一拆 Result {code,message,data,traceId}，code !== 0 抛 ApiError
 * - 401 先尝试一次 refresh，仍失败则清空会话并广播 sss:unauthorized
 * - idempotencyKey 会变成 Idempotency-Key 头（后端 @Idempotent 接口需要）
 */
export async function request(path, options = {}) {
  const {
    method = 'GET',
    body,
    auth = true,
    idempotencyKey,
    headers: extraHeaders = {},
    _retried = false,
  } = options

  const headers = { Accept: 'application/json', ...extraHeaders }
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json'
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
  if (auth && session.accessToken) headers.Authorization = `Bearer ${session.accessToken}`

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
    clearSession()
    window.dispatchEvent(new CustomEvent('sss:unauthorized'))
  }

  if (!res.ok) {
    throw new ApiError(
      payload?.message || `HTTP ${res.status}`,
      payload?.code ?? res.status,
      res.status,
      traceId,
    )
  }

  // 下载类接口返回裸文件流，不走 Result 包装；本封装目前只服务于 JSON 接口。
  if (payload && typeof payload.code === 'number' && payload.code !== 0) {
    throw new ApiError(payload.message || '业务错误', payload.code, res.status, traceId)
  }

  return payload ? payload.data : null
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
