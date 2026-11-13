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

/**
 * 401 刷新是「单飞」（single-flight）：并发的多个 401 请求共用同一个刷新 Promise，
 * 只发一次 POST /auth/refresh，避免 N 个请求同时拿旧 refreshToken 去续期。
 * - 成功：等待者各自用新令牌重试一次自己的请求（request 的 _retried 守卫）。
 * - 失败：清空会话并广播一次 sss:unauthorized，等待者仍按原 401 抛 ApiError。
 */
let refreshInFlight = null

function notifyUnauthorized() {
  // 只有确实带着令牌时才登出，避免登录前后台轮询的竞态把用户踢下线。
  // clearSession 会把 accessToken 置空，所以同一轮会话里最多广播一次。
  if (!session.accessToken) return
  clearSession()
  window.dispatchEvent(new CustomEvent('sss:unauthorized'))
}

function tryRefresh() {
  if (refreshInFlight) return refreshInFlight
  if (!session.refreshToken) return Promise.resolve(false)

  refreshInFlight = (async () => {
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
      // 网络中断同样按刷新失败处理
    }
    // 清会话 + 广播由共享 Promise 完成一次，所有等待者只拿到 false。
    notifyUnauthorized()
    return false
  })().finally(() => {
    refreshInFlight = null
  })

  return refreshInFlight
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
  const { method = 'GET', body, auth = true, idempotencyKey, _retried = false, signal } = options
  const headers = buildHeaders(options)
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData

  let res
  try {
    res = await fetch(API_BASE + path, {
      method,
      headers,
      credentials: 'same-origin',
      signal,
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
    })
  } catch (err) {
    // 主动中断不是「后端没启动」，必须区分开，否则组件卸载会弹一条假错误
    if (err?.name === 'AbortError') {
      throw new ApiError('请求已取消', -2, 0)
    }
    throw new ApiError('无法连接后端，请确认后端已启动，且 vue/.env 的 VITE_API_TARGET 指向正确端口', -1, 0)
  }

  const traceId = res.headers.get('X-Trace-Id') || ''
  const payload = await parseBody(res)

  if (res.status === 401 && auth && !_retried && (await tryRefresh())) {
    return request(path, { ...options, _retried: true })
  }

  if (res.status === 401 && auth) {
    // 刷新失败或重试后仍 401：统一走 notifyUnauthorized（幂等，只广播一次）。
    notifyUnauthorized()
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
 *
 * - 支持单个 `file` 或多个 `files`：多文件时同一个字段名重复 append，后端用 `List<MultipartFile>` 接收；
 * - `signal` 用于取消未完成的上传（组件卸载、用户点「取消」）；
 * - 取消时抛 code = -2 的错误，调用方据此区分「用户主动取消」与「真的失败」。
 */
export function upload(path, { file, files, fieldName = 'file', query = {}, onProgress, method = 'POST', signal } = {}) {
  return new Promise((resolve, reject) => {
    const list = Array.isArray(files) ? files.filter(Boolean) : file ? [file] : []
    if (!list.length) {
      reject(new ApiError('没有待上传的文件', -1, 0))
      return
    }
    if (signal?.aborted) {
      reject(new ApiError('已取消上传', -2, 0))
      return
    }

    const qs = toQuery(query)
    const xhr = new XMLHttpRequest()
    xhr.open(method, `${API_BASE}${path}${qs}`)
    if (session.accessToken) xhr.setRequestHeader('Authorization', `Bearer ${session.accessToken}`)
    xhr.setRequestHeader('Accept', 'application/json')

    const onAbort = () => xhr.abort()
    signal?.addEventListener('abort', onAbort)

    const finish = (fn, value) => {
      signal?.removeEventListener('abort', onAbort)
      fn(value)
    }

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onerror = () => finish(reject, new ApiError('上传失败：网络中断', -1, 0))
    xhr.onabort = () => finish(reject, new ApiError('已取消上传', -2, 0))
    xhr.onload = () => {
      let payload = null
      try {
        payload = xhr.responseText ? JSON.parse(xhr.responseText) : null
      } catch {
        payload = null
      }
      const traceId = xhr.getResponseHeader('X-Trace-Id') || ''
      if (xhr.status >= 200 && xhr.status < 300 && (!payload || payload.code === 0)) {
        finish(resolve, payload ? payload.data : null)
      } else if (xhr.status === 401) {
        clearSession()
        window.dispatchEvent(new CustomEvent('sss:unauthorized'))
        finish(reject, new ApiError('登录状态已失效，请重新登录', 40101, 401, traceId))
      } else {
        finish(reject, new ApiError(payload?.message || `上传失败 HTTP ${xhr.status}`, payload?.code ?? xhr.status, xhr.status, traceId, payload?.data))
      }
    }

    const form = new FormData()
    list.forEach((item) => form.append(fieldName, item))
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
    // 与 request() 的 ApiError 对齐：把响应头里的 X-Trace-Id 一并带出去，便于排查。
    const traceId = res.headers.get('X-Trace-Id') || ''
    throw new ApiError(
      traceId ? `下载失败 HTTP ${res.status}（traceId: ${traceId}）` : `下载失败 HTTP ${res.status}`,
      res.status,
      res.status,
      traceId,
    )
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

/**
 * 带 Authorization 头拉取二进制并返回 blob URL。
 * `<video src>`/`<img src>` 这类标签无法自定义请求头，所以鉴权资源必须先 fetch 成 blob 再播放。
 */
export async function fetchBlobUrl(path) {
  const res = await fetch(API_BASE + path, {
    headers: buildHeaders({ auth: true }),
    credentials: 'same-origin',
  })
  if (!res.ok) {
    const traceId = res.headers.get('X-Trace-Id') || ''
    throw new ApiError(
      traceId ? `读取文件失败 HTTP ${res.status}（traceId: ${traceId}）` : `读取文件失败 HTTP ${res.status}`,
      res.status,
      res.status,
      traceId,
    )
  }
  return URL.createObjectURL(await res.blob())
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
