/**
 * 前端本地存储工具层（localStorage / sessionStorage）。
 *
 * <h3>为什么不是直接 localStorage.setItem</h3>
 * 项目里原本有三处裸写：`stores/session.js` 的登录态、`stores/tasks.js` 的任务列表、
 * `stores/theme.js` 的主题。它们各自 `JSON.parse` + `try/catch`，没有版本、没有过期、
 * 没有 key 隔离（换账号不清理就串味）。这里统一收口，调用方只关心 `get/set`。
 *
 * <h3>四件套保证</h3>
 * - **可校验**：每条记录是 `{v, t, e, h, d}` 信封，`v` 是 schema 版本、`h` 是内容校验哈希、
 *   `e` 是过期时间。任何一项对不上就当脏数据丢弃，不让坏数据污染页面。
 * - **可清理**：`list()` 给出每条记录的模块/用户/大小/更新时间，`cleanup()` 清过期与孤儿。
 * - **不串味**：key 强制为 `sss:{module}:{userId}:{context}`，多账号切换互不影响。
 * - **可降级**：隐私模式、QuotaExceeded、老浏览器读不到 storage 时自动转内存态，
 *   业务代码不必写 `if (storageAvailable)` 分支。
 *
 * <h3>为什么 API 是 async</h3>
 * localStorage 本身是同步的，但「写大对象」迟早要换 IndexedDB，而 IDB 只能异步。
 * 现在就把边界定成 async，将来换驱动只改 `useDriver()`，调用方一行不动。
 * 默认驱动在内存里直接 resolve，没有额外开销。
 *
 * <h3>为什么不 import vue</h3>
 * 这个文件要能在 Node 里用 `node --test` 跑（见 `vue/tests/storage.test.mjs`）。
 * 需要响应式的地方由调用方自己包一层 `ref` + `onStorageHealth()`。
 */

/** 所有 key 的统一前缀；`list()` 也只认这个前缀，不会误删同源其它应用的 key。 */
const PREFIX = 'sss'

/**
 * 信封 schema 版本。改动记录结构（不是业务数据）时 +1，
 * 旧版本记录会在读取时被判定为 `version` 不匹配并自动清掉。
 */
export const SCHEMA_VERSION = 1

/** 探测用的临时 key，写完立即删。 */
const PROBE_KEY = `${PREFIX}:__probe`

/**
 * 隐私模式下读不到 storage 时的内存兜底。
 * 只在当前页面生命周期内有效——刷新即丢，这正是隐私模式该有的行为。
 */
const memory = new Map()

/** 单条记录超过这个大小就不建议再往 localStorage 塞（localStorage 上限约 5MB/域）。 */
export const SOFT_LIMIT_BYTES = 512 * 1024

// ---------------------------------------------------------------------------
// 健康状态与订阅
// ---------------------------------------------------------------------------

const health = {
  /** 能否真正写进 storage（false 表示全程内存态）。 */
  available: true,
  /** 是否已降级为内存态。 */
  memoryOnly: false,
  /** 是否遇到过配额超限。 */
  quotaExceeded: false,
  /** 最近一次错误原因，给 UI 提示用。 */
  lastError: '',
}

const healthListeners = new Set()
const errorListeners = new Set()

/** 读取当前健康状态快照（拷贝，避免外部直接改内部对象）。 */
export function storageHealth() {
  return { ...health }
}

/** 订阅健康状态变化（UI 用它决定要不要挂「隐私模式无法保存进度」提示条）。 */
export function onStorageHealth(listener) {
  healthListeners.add(listener)
  return () => healthListeners.delete(listener)
}

/**
 * 订阅写入失败事件。
 * 与健康状态分开：配额错误每次都该提示一次，而状态只在变化时才通知。
 */
export function onStorageError(listener) {
  errorListeners.add(listener)
  return () => errorListeners.delete(listener)
}

function patchHealth(patch) {
  let changed = false
  for (const [key, value] of Object.entries(patch)) {
    if (health[key] !== value) {
      health[key] = value
      changed = true
    }
  }
  if (!changed) return
  const snapshot = { ...health }
  healthListeners.forEach((fn) => safely(() => fn(snapshot)))
}

function emitError(payload) {
  errorListeners.forEach((fn) => safely(() => fn(payload)))
}

/** 所有回调都不允许把异常抛回给写入路径——提示失败不能影响主流程。 */
function safely(fn) {
  try {
    fn()
  } catch {
    /* 忽略订阅者自身异常 */
  }
}

// ---------------------------------------------------------------------------
// 驱动层（默认 localStorage/sessionStorage，可替换为 IndexedDB）
// ---------------------------------------------------------------------------

/**
 * 默认驱动。`area` 取 `'local'` 或 `'session'`。
 *
 * 换成 IndexedDB 的驱动只要实现同样的四个方法即可：
 * ```js
 * useDriver({
 *   name: 'indexeddb',
 *   async get(area, key) { ... },
 *   async set(area, key, value) { ... },
 *   async remove(area, key) { ... },
 *   async keys(area) { ... },
 * })
 * ```
 */
export const localDriver = {
  name: 'localStorage',
  async get(area, key) {
    const store = areaOf(area)
    if (store) {
      try {
        const raw = store.getItem(key)
        if (raw !== null) return raw
      } catch (err) {
        degrade(err, key)
      }
    }
    return memory.has(cacheKey(area, key)) ? memory.get(cacheKey(area, key)) : null
  },
  async set(area, key, value) {
    const store = areaOf(area)
    if (store) {
      try {
        store.setItem(key, value)
        memory.delete(cacheKey(area, key))
        patchHealth({ available: true })
        return true
      } catch (err) {
        degrade(err, key)
      }
    }
    memory.set(cacheKey(area, key), value)
    return false
  },
  async remove(area, key) {
    const store = areaOf(area)
    if (store) {
      try {
        store.removeItem(key)
      } catch {
        /* 删除失败不算错误：内存态里也会删 */
      }
    }
    memory.delete(cacheKey(area, key))
    return true
  },
  async keys(area) {
    const out = new Set()
    const store = areaOf(area)
    if (store) {
      try {
        for (let i = 0; i < store.length; i += 1) {
          const key = store.key(i)
          if (key) out.add(key)
        }
      } catch (err) {
        degrade(err, '')
      }
    }
    for (const key of memory.keys()) {
      if (key.startsWith(`${area}:`)) out.add(key.slice(area.length + 1))
    }
    return [...out]
  },
}

let driver = localDriver

/** 替换底层驱动（IndexedDB 预留口）。 */
export function useDriver(next) {
  driver = next || localDriver
}

function cacheKey(area, key) {
  return `${area}:${key}`
}

/** 拿到真正的 Storage 对象；隐私模式/被禁用时返回 null。 */
function areaOf(area) {
  const w = win()
  if (!w) return null
  try {
    if (area === 'session') return w.sessionStorage || null
    return w.localStorage || null
  } catch {
    // Safari 隐私模式下访问 localStorage 会直接抛 SecurityError
    return null
  }
}

function win() {
  if (typeof window !== 'undefined' && window) return window
  if (typeof globalThis !== 'undefined' && globalThis.window) return globalThis.window
  return null
}

/** 降级：写不进去就转内存态，并通知 UI。 */
function degrade(err, key) {
  const name = err && err.name ? err.name : ''
  const quota = name === 'QuotaExceededError' || name === 'NS_ERROR_DOM_QUOTA_REACHED' || (err && err.code === 22)
  patchHealth({
    available: false,
    memoryOnly: true,
    quotaExceeded: quota || health.quotaExceeded,
    lastError: quota ? '本地存储空间已满' : `本地存储不可用（${name || '未知原因'}）`,
  })
  emitError({
    type: quota ? 'quota' : 'unavailable',
    key,
    memoryOnly: true,
    message: quota ? '本地存储空间已满，本次不保存进度' : '当前浏览器无法保存本地数据（隐私模式？）',
  })
}

// ---------------------------------------------------------------------------
// 信封编解码
// ---------------------------------------------------------------------------

/** 32 位 FNV-1a：够快、无依赖，用于发现「写入后被人手改/截断」的脏数据。 */
export function hashOf(text) {
  let hash = 0x811c9dc5
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(36)
}

function now() {
  return Date.now()
}

/**
 * 编码成信封字符串。
 *
 * `d` 存的是**已经序列化好的字符串**而不是对象：读取时先校验哈希再 `JSON.parse`，
 * 避免「对象进 JSON 出」导致哈希算不准；也让体积统计更接近真实占用。
 */
export function encode(value, { ttlMs = 0, expiresAt = 0 } = {}) {
  const payload = JSON.stringify(value)
  if (payload === undefined) {
    throw new TypeError('storage: 值无法序列化（undefined / 函数 / 循环引用）')
  }
  const timestamp = now()
  return JSON.stringify({
    v: SCHEMA_VERSION,
    t: timestamp,
    e: expiresAt || (ttlMs > 0 ? timestamp + ttlMs : 0),
    h: hashOf(payload),
    d: payload,
  })
}

/**
 * 解码并校验。
 * @returns {{ok: true, value: any, updatedAt: number} | {ok: false, reason: string}}
 *   reason: `corrupt`（不是信封）/ `version`（schema 不兼容）/ `checksum`（内容被改坏）/ `expired`
 */
export function decode(raw) {
  if (typeof raw !== 'string' || !raw) return { ok: false, reason: 'corrupt' }
  let env
  try {
    env = JSON.parse(raw)
  } catch {
    return { ok: false, reason: 'corrupt' }
  }
  if (!env || typeof env !== 'object' || typeof env.d !== 'string') {
    return { ok: false, reason: 'corrupt' }
  }
  if (env.v !== SCHEMA_VERSION) return { ok: false, reason: 'version' }
  if (env.h !== hashOf(env.d)) return { ok: false, reason: 'checksum' }
  if (env.e && env.e <= now()) return { ok: false, reason: 'expired' }
  try {
    return { ok: true, value: JSON.parse(env.d), updatedAt: env.t || 0 }
  } catch {
    return { ok: false, reason: 'corrupt' }
  }
}

// ---------------------------------------------------------------------------
// 底层读写
// ---------------------------------------------------------------------------

/**
 * 写入并返回是否真的落进了 storage（false 表示走了内存兜底）。
 * 调用方一般不需要关心返回值，除非要做「保存失败」的强提示。
 */
export async function setJSON(fullKey, value, { area = 'local', ttlMs = 0, expiresAt = 0 } = {}) {
  let raw
  try {
    raw = encode(value, { ttlMs, expiresAt })
  } catch (err) {
    emitError({ type: 'serialize', key: fullKey, message: err.message })
    return false
  }
  const bytes = byteLength(fullKey, raw)
  if (bytes > SOFT_LIMIT_BYTES) {
    // 不阻断写入，但明确告诉调用方：这条太大了，该换介质
    emitError({
      type: 'oversize',
      key: fullKey,
      bytes,
      message: `单条本地记录 ${(bytes / 1024).toFixed(0)}KB，超过建议上限 ${(SOFT_LIMIT_BYTES / 1024).toFixed(0)}KB`,
    })
  }
  return driver.set(area, fullKey, raw)
}

/** 读取并校验；任何异常都返回 null，不向上抛。 */
export async function getJSON(fullKey, { area = 'local' } = {}) {
  let raw = null
  try {
    raw = await driver.get(area, fullKey)
  } catch (err) {
    degrade(err, fullKey)
    return null
  }
  if (raw === null || raw === undefined) return null
  const result = decode(raw)
  if (!result.ok) {
    // 脏数据就地清掉：版本不兼容、校验失败、过期，留着只会反复踩
    await driver.remove(area, fullKey)
    return null
  }
  return result.value
}

export async function removeKey(fullKey, { area = 'local' } = {}) {
  return driver.remove(area, fullKey)
}

/** UTF-16 估算字节数：localStorage 按字符计配额，中文与 ASCII 都占 2 字节。 */
function byteLength(key, raw) {
  return (key.length + raw.length) * 2
}

// ---------------------------------------------------------------------------
// 命名空间
// ---------------------------------------------------------------------------

/** 拼 key 时把冒号换掉，避免 context 里带冒号导致层级解析错位。 */
function sanitize(part) {
  return String(part === undefined || part === null ? '' : part).replace(/:/g, '_')
}

/** key 形如 `sss:upload:1024:123-SOURCE`。 */
export function buildKey(module, userId, context) {
  return `${PREFIX}:${sanitize(module)}:${sanitize(userId) || 'anon'}:${sanitize(context)}`
}

const KEY_PATTERN = new RegExp(`^${PREFIX}:([^:]+):([^:]+):(.*)$`)

/** 解析 key，认不出返回 null。 */
export function parseKey(fullKey) {
  const match = KEY_PATTERN.exec(fullKey || '')
  if (!match) return null
  if (match[1] === '__probe') return null
  return { module: match[1], userId: match[2], context: match[3] }
}

/**
 * 创建一个模块命名空间。
 *
 * ```js
 * const ns = namespace('upload', user.id, { area: 'local', ttlMs: 24 * 3600e3 })
 * await ns.set(`${selectionId}-SOURCE`, snapshot)
 * const restored = await ns.get(`${selectionId}-SOURCE`)
 * ```
 *
 * @param {string} module  upload / form / steps / draft / async / settings
 * @param {string|number} userId 当前登录用户 id，缺省 `anon`
 * @param {{area?: 'local'|'session', ttlMs?: number}} options 默认介质与默认 TTL
 */
export function namespace(module, userId, options = {}) {
  const defaults = { area: 'local', ttlMs: 0, ...options }

  const api = {
    module,
    userId: sanitize(userId) || 'anon',
    scope: `${PREFIX}:${sanitize(module)}:${sanitize(userId) || 'anon'}`,

    key(context) {
      return buildKey(module, userId, context)
    },

    async set(context, value, opts = {}) {
      return setJSON(api.key(context), value, { ...defaults, ...opts })
    },

    async get(context, opts = {}) {
      return getJSON(api.key(context), { ...defaults, ...opts })
    },

    async remove(context, opts = {}) {
      return removeKey(api.key(context), { ...defaults, ...opts })
    },

    /** 本命名空间下的所有 context（不含 module/userId 前缀）。 */
    async keys(opts = {}) {
      const all = await driver.keys((opts.area || defaults.area))
      return all
        .map((key) => (key.startsWith(`${api.scope}:`) ? key.slice(api.scope.length + 1) : null))
        .filter((item) => item !== null)
    },

    /** 清空本命名空间（某个用户某个模块的全部上下文）。 */
    async clear(opts = {}) {
      const contexts = await api.keys(opts)
      const area = opts.area || defaults.area
      await Promise.all(contexts.map((context) => removeKey(api.key(context), { area })))
      return contexts.length
    },

    /**
     * 监听本命名空间某个 context 被**其它标签页**改写。
     * 注意：`storage` 事件不会在写入的那个标签页里触发，这是浏览器规范。
     */
    watch(context, callback, opts = {}) {
      return watchKey(api.key(context), callback, opts)
    },
  }

  return api
}

// ---------------------------------------------------------------------------
// 跨标签页监听
// ---------------------------------------------------------------------------

const watchers = new Map() // fullKey -> Set<callback>
let listening = false

function ensureListener() {
  if (listening) return
  const w = win()
  if (!w || typeof w.addEventListener !== 'function') return
  w.addEventListener('storage', (event) => {
    if (!event || !event.key) return
    // 事件不带「哪个 area」，靠 storageArea 反查；取不到时按 local 处理
    let area = 'local'
    try {
      if (event.storageArea && w.sessionStorage && event.storageArea === w.sessionStorage) {
        area = 'session'
      }
    } catch {
      /* 隐私模式下访问 sessionStorage 可能抛错，保持默认 local */
    }
    const set = watchers.get(`${area}|${event.key}`)
    if (!set) return
    const result = decode(event.newValue)
    set.forEach((fn) =>
      safely(() =>
        fn(
          result.ok ? result.value : null,
          { key: event.key, oldValue: event.oldValue, newValue: event.newValue, external: true },
        ),
      ),
    )
  })
  listening = true
}

/**
 * 监听某个 key 的跨标签页变化。
 * @returns {() => void} 取消订阅
 */
export function watchKey(fullKey, callback, { area = 'local' } = {}) {
  ensureListener()
  const scoped = `${area}|${fullKey}`
  if (!watchers.has(scoped)) watchers.set(scoped, new Set())
  watchers.get(scoped).add(callback)
  return () => {
    const set = watchers.get(scoped)
    if (!set) return
    set.delete(callback)
    if (!set.size) watchers.delete(scoped)
  }
}

// ---------------------------------------------------------------------------
// 盘点与清理
// ---------------------------------------------------------------------------

/** 列出全部 `sss:*` 记录（含大小、是否过期），给「本地缓存」面板用。 */
export async function list() {
  const rows = []
  for (const area of ['local', 'session']) {
    const keys = await driver.keys(area)
    for (const key of keys) {
      const meta = parseKey(key)
      if (!meta) continue
      let raw = null
      try {
        raw = await driver.get(area, key)
      } catch {
        continue
      }
      if (typeof raw !== 'string') continue
      const result = decode(raw)
      rows.push({
        key,
        area,
        ...meta,
        bytes: byteLength(key, raw),
        updatedAt: result.ok ? result.updatedAt : 0,
        expired: !result.ok && result.reason === 'expired',
        invalid: !result.ok && result.reason !== 'expired',
        reason: result.ok ? '' : result.reason,
      })
    }
  }
  return rows.sort((a, b) => b.updatedAt - a.updatedAt)
}

/** 汇总占用：总量 + 按模块分组 + 按介质分组。 */
export async function stats() {
  const rows = await list()
  const byModule = {}
  const byArea = { local: 0, session: 0 }
  let total = 0
  for (const row of rows) {
    total += row.bytes
    byModule[row.module] = (byModule[row.module] || 0) + row.bytes
    byArea[row.area] = (byArea[row.area] || 0) + row.bytes
  }
  return { count: rows.length, totalBytes: total, byModule, byArea }
}

/**
 * 清理过期、版本不兼容、校验失败的记录。
 * @param {{now?: number}} options 预留 now 便于测试；真实清理总是按当前时间
 * @returns {Promise<{removed: string[], kept: number}>}
 */
export async function cleanup() {
  const rows = await list()
  const removed = []
  for (const row of rows) {
    if (row.expired || row.invalid) {
      await removeKey(row.key, { area: row.area })
      removed.push(row.key)
    }
  }
  return { removed, kept: rows.length - removed.length }
}

/** 按模块清空（如一次性清掉所有草稿）。 */
export async function clearModule(module) {
  const rows = (await list()).filter((row) => row.module === module)
  await Promise.all(rows.map((row) => removeKey(row.key, { area: row.area })))
  return rows.length
}

/** 清空本应用的全部本地数据（不影响同源其它应用）。 */
export async function clearAll() {
  const rows = await list()
  await Promise.all(rows.map((row) => removeKey(row.key, { area: row.area })))
  return rows.length
}

/** 探测存储可用性；隐私模式下返回 false 且不抛异常。 */
export async function probe() {
  const store = areaOf('local')
  if (!store) {
    degrade(new Error('storage unavailable'), PROBE_KEY)
    return false
  }
  try {
    store.setItem(PROBE_KEY, '1')
    store.removeItem(PROBE_KEY)
    patchHealth({ available: true, memoryOnly: false })
    return true
  } catch (err) {
    degrade(err, PROBE_KEY)
    return false
  }
}

/** 给 UI 用的统一门面，省得调用方记一堆散装函数名。 */
export const storage = {
  namespace,
  list,
  stats,
  cleanup,
  clearAll,
  clearModule,
  probe,
  health: storageHealth,
  onHealth: onStorageHealth,
  onError: onStorageError,
  setJSON,
  getJSON,
  removeKey,
  buildKey,
  parseKey,
  watchKey,
  hashOf,
  encode,
  decode,
  useDriver,
  SOFT_LIMIT_BYTES,
  SCHEMA_VERSION,
}
