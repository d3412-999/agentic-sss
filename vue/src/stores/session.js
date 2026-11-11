import { computed, reactive } from 'vue'

// 与后端 AuthDtos.TokenResponse / MeVO 对应。
// 文件名与路径来自 AuthDtos.java 注释里约定的「前端 stores/session.js」。
//
// 存储策略（安全权衡）：
// - accessToken / refreshToken 只放 sessionStorage：按标签页隔离，关闭标签页即清除，
//   不会长期落盘；同一标签页刷新页面仍保持登录。
// - user / me 等非敏感资料放 localStorage：跨标签页、重启浏览器后仍可显示。
// - 残留风险：同源 XSS 仍然能读到 sessionStorage 里的令牌，这只是缩短令牌驻留窗口；
//   彻底修复需要后端下发 HttpOnly + Secure + SameSite 的会话 Cookie（本次不在范围内）。
const LEGACY_KEY = 'sss.session' // 旧版：令牌与资料一起保存在 localStorage
const PROFILE_KEY = 'sss.profile' // 新版：localStorage 只保存非敏感资料
const ACCESS_TOKEN_KEY = 'sss.accessToken' // 新版：sessionStorage
const REFRESH_TOKEN_KEY = 'sss.refreshToken' // 新版：sessionStorage

function readJSON(storage, key) {
  try {
    const raw = storage.getItem(key)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    // 损坏的 JSON 按空处理，不让坏数据卡住应用启动
    return {}
  }
}

function readRaw(storage, key) {
  try {
    return storage.getItem(key) || ''
  } catch {
    return ''
  }
}

function writeRaw(storage, key, value) {
  try {
    if (value) storage.setItem(key, value)
    else storage.removeItem(key)
  } catch {
    // 隐私模式等禁用存储的场景：保持内存态即可，刷新后需要重新登录
  }
}

/** 旧版把令牌和资料一起放在 localStorage 的 sss.session 里：首次加载时迁移到新结构。 */
function migrateLegacySession() {
  try {
    const legacy = readJSON(localStorage, LEGACY_KEY)
    const hasSomething = legacy.accessToken || legacy.refreshToken || legacy.user || legacy.me
    if (!hasSomething) {
      localStorage.removeItem(LEGACY_KEY)
      return
    }
    // 同标签页里已有新格式令牌时以新令牌为准（可能刚 refresh 过）。
    if (legacy.accessToken && !readRaw(sessionStorage, ACCESS_TOKEN_KEY)) {
      sessionStorage.setItem(ACCESS_TOKEN_KEY, legacy.accessToken)
    }
    if (legacy.refreshToken && !readRaw(sessionStorage, REFRESH_TOKEN_KEY)) {
      sessionStorage.setItem(REFRESH_TOKEN_KEY, legacy.refreshToken)
    }
    // localStorage 只保留资料，且不覆盖已经存在的新资料。
    if (!readRaw(localStorage, PROFILE_KEY)) {
      localStorage.setItem(
        PROFILE_KEY,
        JSON.stringify({ user: legacy.user || null, me: legacy.me || null }),
      )
    }
    // 旧键里连令牌一起存过，迁移后必须删除，避免令牌继续留在 localStorage。
    localStorage.removeItem(LEGACY_KEY)
  } catch {
    // 存储不可用时跳过迁移：最坏情况是需要重新登录一次
  }
}

migrateLegacySession()

const savedProfile = readJSON(localStorage, PROFILE_KEY)

const state = reactive({
  accessToken: readRaw(sessionStorage, ACCESS_TOKEN_KEY),
  refreshToken: readRaw(sessionStorage, REFRESH_TOKEN_KEY),
  user: savedProfile.user || null,
  me: savedProfile.me || null,
})

function persistTokens() {
  writeRaw(sessionStorage, ACCESS_TOKEN_KEY, state.accessToken)
  writeRaw(sessionStorage, REFRESH_TOKEN_KEY, state.refreshToken)
}

function persistProfile() {
  try {
    localStorage.setItem(PROFILE_KEY, JSON.stringify({ user: state.user, me: state.me }))
  } catch {
    // 写不进去只影响刷新后的资料回填，不影响本次会话
  }
}

function persist() {
  persistTokens()
  persistProfile()
}

export const session = state
export const isAuthenticated = computed(() => Boolean(state.accessToken))

/** 登录成功：后端字段名是 accessToken，不是 token。 */
export function saveLogin(data) {
  state.accessToken = data.accessToken || ''
  state.refreshToken = data.refreshToken || ''
  state.user = data.user || null
  persist()
}

/** refresh 成功后只换令牌，保留 user。 */
export function saveTokens(data) {
  state.accessToken = data.accessToken || ''
  state.refreshToken = data.refreshToken || state.refreshToken
  if (data.user) state.user = data.user
  persist()
}

export function setMe(me) {
  state.me = me
  persist()
}

export function clearSession() {
  state.accessToken = ''
  state.refreshToken = ''
  state.user = null
  state.me = null
  persistTokens()
  try {
    localStorage.removeItem(PROFILE_KEY)
    // 迁移期间旧版程序可能又写入过带令牌的旧键，退出时一并清掉。
    localStorage.removeItem(LEGACY_KEY)
  } catch {
    // 忽略存储不可用
  }
}

export const displayName = computed(() => state.user?.name || state.me?.name || '未登录')
export const currentRole = computed(() => state.user?.role || state.me?.role || '')
