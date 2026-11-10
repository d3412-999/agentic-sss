import { reactive } from 'vue'

/**
 * Promise 化的确认对话框：await confirm({...}) -> true / false
 * ConfirmHost.vue 负责渲染。
 */
export const dialog = reactive({
  open: false,
  title: '',
  message: '',
  confirmText: '确认',
  cancelText: '取消',
  danger: false,
  _resolve: null,
})

export function confirm(options = {}) {
  if (dialog.open && dialog._resolve) dialog._resolve(false)
  Object.assign(dialog, {
    title: '请确认',
    message: '',
    confirmText: '确认',
    cancelText: '取消',
    danger: false,
    ...options,
    open: true,
  })
  return new Promise((resolve) => {
    dialog._resolve = resolve
  })
}

export function settle(value) {
  dialog.open = false
  const resolve = dialog._resolve
  dialog._resolve = null
  if (resolve) resolve(value)
}
