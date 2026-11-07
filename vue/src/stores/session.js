import { computed, reactive } from 'vue'

// 与后端 AuthDtos.TokenResponse / MeVO 对应。
// 文件名与路径来自 AuthDtos.java 注释里约定的「前端 stores/session.js」。
const STORAGE_KEY = 'sss.session'

function readStorage() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')
  } catch {
    return {}
  }
}

const saved = readStorage()

const state = reactive({
  accessToken: saved.accessToken || '',
  refreshToken: saved.refreshToken || '',
  user: saved.user || null,
  me: saved.me || null,
})

function persist() {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      accessToken: state.accessToken,
      refreshToken: state.refreshToken,
      user: state.user,
      me: state.me,
    }),
  )
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
  localStorage.removeItem(STORAGE_KEY)
}

export const displayName = computed(() => state.user?.name || state.me?.name || '未登录')
export const currentRole = computed(() => state.user?.role || state.me?.role || '')
