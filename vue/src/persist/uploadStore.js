/**
 * 提交物上传的本地快照（跨刷新 / 跨标签页）。
 *
 * <h3>为什么分两个介质</h3>
 * - **已成功上传的文件**（有 `fileId`，文件本体在后端）放 `localStorage`：
 *   关掉浏览器再打开都还在，用户不必重传。TTL 24h——超过一天还没提交，
 *   说明大概率是废弃操作，留着只会变成幽灵数据。
 * - **队列**（待传 / 上传中 / 失败）放 `sessionStorage`：它只对当前标签页有意义，
 *   标签页一关就该消失，否则下次进来会看到一堆「上次没传完」的噪音。
 *   注意 sessionStorage 在**刷新**后仍然存在，所以刷新恢复是有效的。
 *
 * <h3>File 对象为什么恢复不了</h3>
 * 浏览器的安全模型不允许页面读回磁盘上的文件内容，`File` 也无法序列化。
 * 所以能存的只有 `name + size + lastModified`，用户重新选择同名同大小的文件时
 * 我们把这几个字段当「票据」对上，接回原来的队列位置——这是唯一可行的做法。
 *
 * <h3>多标签页占用</h3>
 * `localStorage` 里的 `<context>-owner` 是心跳（TTL 15s，每 5s 刷一次）。
 * 后打开的标签页看到别人的新鲜心跳就转只读；持有者关页后心跳自然过期。
 */

import { namespace } from '../utils/storage.js'
import { currentUserId } from './identity.js'

/** 已上传列表的存活时间：一天没用就当过期。 */
export const DONE_TTL_MS = 24 * 60 * 60 * 1000

/** 占用心跳的 TTL 与续期间隔。 */
export const OWNER_TTL_MS = 15 * 1000
export const OWNER_BEAT_MS = 5 * 1000

/** 当前用户 id 统一由 persist/identity.js 提供（草稿、任务中心都复用它）。 */
export { currentUserId } from './identity.js'

/** 上传快照的上下文：同一选题的源码与报告互不干扰。 */
export function contextOf(selectionId, type) {
  return `${selectionId}-${type}`
}

/**
 * 为一个提交上下文创建存储门面。
 * @param {{selectionId: string, type: string, userId?: string}} options
 */
export function createUploadStore({ selectionId, type, userId = currentUserId() }) {
  const context = contextOf(selectionId, type)
  const local = namespace('upload', userId, { area: 'local' })
  const queueNs = namespace('upload', userId, { area: 'session' })
  const ownerContext = `${context}-owner`

  return {
    context,
    userId,

    /** 已成功上传的文件（跨会话）。 */
    async loadDone() {
      const snapshot = await local.get(context)
      const list = snapshot && Array.isArray(snapshot.done) ? snapshot.done : []
      // 只认真实存在的字段，防止旧数据/手改数据把 File 结构带进来
      return list
        .filter((item) => item && item.fileId && item.name)
        .map((item) => ({
          name: String(item.name),
          size: Number(item.size) || 0,
          lastModified: Number(item.lastModified) || 0,
          fileId: String(item.fileId),
          uploadedAt: Number(item.uploadedAt) || 0,
        }))
    },

    async saveDone(list) {
      if (!list || !list.length) {
        await local.remove(context)
        return
      }
      await local.set(context, { done: list }, { ttlMs: DONE_TTL_MS })
    },

    /** 未完成的队列（同标签页，刷新后仍可恢复）。 */
    async loadQueue() {
      const snapshot = await queueNs.get(context)
      const list = snapshot && Array.isArray(snapshot.queue) ? snapshot.queue : []
      return list
        .filter((item) => item && item.name)
        .map((item) => ({
          name: String(item.name),
          size: Number(item.size) || 0,
          lastModified: Number(item.lastModified) || 0,
          status: String(item.status || 'failed'),
          progress: Number(item.progress) || 0,
          error: String(item.error || ''),
        }))
    },

    async saveQueue(list) {
      if (!list || !list.length) {
        await queueNs.remove(context)
        return
      }
      await queueNs.set(context, { queue: list })
    },

    /** 提交成功后彻底清掉，不留残迹。 */
    async clear() {
      await Promise.all([local.remove(context), queueNs.remove(context)])
    },

    /** 只清队列（已成功列表保留，用于「全部提交完成后才清空」之外的场景）。 */
    async clearQueue() {
      await queueNs.remove(context)
    },

    /* ---------------- 多标签页占用 ---------------- */

    async readOwner() {
      return local.get(ownerContext)
    },

    async claim(tabId) {
      return local.set(ownerContext, { tabId, at: Date.now() }, { ttlMs: OWNER_TTL_MS })
    },

    /** 只有持有者自己才能释放，避免只读页把别人的占用抹掉。 */
    async release(tabId) {
      const owner = await local.get(ownerContext)
      if (owner && owner.tabId === tabId) {
        await local.remove(ownerContext)
      }
    },

    watchOwner(callback) {
      return local.watch(ownerContext, callback)
    },

    watchQueue(callback) {
      return queueNs.watch(context, callback)
    },
  }
}

/**
 * 把组件内部的 items 拆成「已完成」与「队列」两份可持久化快照。
 * `File`、`AbortController` 这类不可序列化的字段一律不写。
 */
export function snapshotOf(items) {
  const done = []
  const queue = []
  for (const item of items || []) {
    const base = {
      name: item.name,
      size: item.size,
      lastModified: item.lastModified || 0,
    }
    if (item.status === 'success' && item.fileId) {
      done.push({ ...base, fileId: item.fileId, uploadedAt: Date.now() })
    } else if (item.status !== 'success') {
      queue.push({
        ...base,
        // 中途刷新时状态一定是 uploading，恢复时按「被中断」处理
        status: item.status === 'uploading' ? 'interrupted' : item.status,
        progress: item.progress || 0,
        error: item.error || '',
      })
    }
  }
  return { done, queue }
}
