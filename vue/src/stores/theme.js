import { reactive, watchEffect } from 'vue'

/**
 * 主题：
 * - warm：米黄暖色调（默认，日间）
 * - light：原有浅色风格
 * - dark：原有深色风格
 *
 * 选择保存在 localStorage，刷新后保持；没有历史选择时默认 warm。
 */
const KEY = 'sss.theme.v2'
const VALID = new Set(['warm', 'light', 'dark'])

export const THEMES = [
  { key: 'warm', label: '暖色', icon: 'sun', hint: '米黄暖色调，日间默认' },
  { key: 'light', label: '浅色', icon: 'sun', hint: '原有白色后台风格' },
  { key: 'dark', label: '深色', icon: 'moon', hint: '原有深色夜间风格' },
]

const META_COLORS = {
  warm: '#f4efe6',
  light: '#f5f6fb',
  dark: '#0a0e1a',
}

const saved = localStorage.getItem(KEY)

export const theme = reactive({
  mode: VALID.has(saved) ? saved : 'warm',
})

export function themeName(mode = theme.mode) {
  return THEMES.find((item) => item.key === mode)?.label || mode
}

export function nextThemeMode(mode = theme.mode) {
  const index = THEMES.findIndex((item) => item.key === mode)
  return THEMES[(index + 1) % THEMES.length].key
}

export function setTheme(mode) {
  if (VALID.has(mode)) theme.mode = mode
}

export function toggleTheme() {
  setTheme(nextThemeMode())
}

watchEffect(() => {
  const mode = theme.mode
  document.documentElement.dataset.theme = mode
  document.documentElement.style.colorScheme = mode === 'dark' ? 'dark' : 'light'
  localStorage.setItem(KEY, mode)

  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', META_COLORS[mode] || META_COLORS.warm)
})
