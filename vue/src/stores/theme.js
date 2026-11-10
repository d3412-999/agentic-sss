import { reactive, watchEffect } from 'vue'

const KEY = 'sss.theme'
const saved = localStorage.getItem(KEY)

export const theme = reactive({
  mode: saved === 'dark' || saved === 'light'
    ? saved
    : window.matchMedia?.('(prefers-color-scheme: dark)').matches
      ? 'dark'
      : 'light',
})

watchEffect(() => {
  document.documentElement.dataset.theme = theme.mode
  localStorage.setItem(KEY, theme.mode)
})

export function toggleTheme() {
  theme.mode = theme.mode === 'dark' ? 'light' : 'dark'
}
