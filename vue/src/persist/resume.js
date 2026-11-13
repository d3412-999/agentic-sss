/**
 * 恢复中枢：全局性的「进页面时该恢复什么」统一在这里触发。
 *
 * <h3>为什么放路由守卫</h3>
 * 有几类恢复是**和具体页面无关**的：清理过期本地数据、把上次没跑完的异步任务
 * 重新挂上轮询。这类逻辑如果散落在各页面的 `onMounted` 里，就会出现「某个页面
 * 忘了写 → 从那个页面进来任务不跟进」的坑。放到 `router.afterEach` 里一次搞定。
 *
 * <h3>什么样的恢复不在这里</h3>
 * 需要组件内部状态的恢复（比如「这个上传控件的队列」「这个弹窗的表单草稿」）
 * 必须由组件自己做——守卫里拿不到它们的 ref。那种恢复走 `useDraft` /
 * `createUploadStore`，在组件挂载或弹窗打开时问一句「要不要恢复」。
 * 这里只管「全局、无组件状态」的部分。
 */

import { cleanup } from '../utils/storage'
import { hydrateTasks } from '../stores/tasks'

/** 两次自动清理之间至少隔 5 分钟：路由切换很频繁，别每次都扫一遍存储。 */
const CLEANUP_INTERVAL_MS = 5 * 60 * 1000

let lastCleanupAt = 0
const hooks = new Map()

/**
 * 注册一个全局恢复钩子。
 * @param {string} name 唯一名称，重复注册会覆盖
 * @param {(to: object) => void|Promise<void>} fn
 * @returns {() => void} 注销函数
 */
export function registerResume(name, fn) {
  hooks.set(name, fn)
  return () => hooks.delete(name)
}

/**
 * 路由进入后调用：先按需清理，再跑所有钩子。
 * 任何钩子抛错都只 console.warn，绝不影响导航。
 */
export async function resumeFor(to) {
  const now = Date.now()
  if (now - lastCleanupAt > CLEANUP_INTERVAL_MS) {
    lastCleanupAt = now
    try {
      await cleanup()
    } catch (err) {
      console.warn('[persist] 本地数据清理失败', err)
    }
  }
  for (const [name, fn] of hooks) {
    try {
      await fn(to)
    } catch (err) {
      console.warn(`[persist] 恢复钩子 ${name} 执行失败`, err)
    }
  }
}

/** 给设置页用的「手动清理」入口，顺便把节流时间戳重置，避免刚清完又自动清一次。 */
export async function cleanupNow() {
  const result = await cleanup()
  lastCleanupAt = Date.now()
  return result
}

// ---------------------------------------------------------------------------
// 内置钩子：任务中心
// ---------------------------------------------------------------------------

// 任务中心的数据在 localStorage 里，刷新后要把列表捞回来并重新挂上轮询。
// 注册在这里而不是 TasksView 里，这样「从任意页面进来」都能续上未完成的任务。
registerResume('async-tasks', async () => {
  await hydrateTasks()
})
