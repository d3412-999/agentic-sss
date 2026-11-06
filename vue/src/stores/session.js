import { reactive } from 'vue'

const STORAGE_KEY = 'sss.session'

function restore() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    return JSON.parse(raw)
  } catch {
    return null
  }
}

const saved = restore()

export const session = reactive({
  authenticated: Boolean(saved?.authenticated),
  role: saved?.role || 'student',
  name: saved?.name || '',
  account: saved?.account || ''
})

export const demoAccounts = {
  student: { name: '张三', account: '2023010101', label: '学生演示账号' },
  teacher: { name: '李文博', account: 'T2019008', label: '教师演示账号' }
}

export function signIn(role) {
  const account = demoAccounts[role] || demoAccounts.student
  session.authenticated = true
  session.role = role
  session.name = account.name
  session.account = account.account
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
}

export function signOut() {
  session.authenticated = false
  session.name = ''
  session.account = ''
  localStorage.removeItem(STORAGE_KEY)
}
