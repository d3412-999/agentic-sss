/**
 * 表单/步骤草稿的通用持久化（组合式函数）。
 *
 * <h3>它解决什么</h3>
 * 「填了半天，一不小心点到别处 / 弹窗被 Esc 关掉 / 手滑刷新」——这是本项目里
 * 最容易被骂的一类丢失。统一做法：值变化 → 防抖 400ms 落 localStorage →
 * 下次打开同一表单时询问是否恢复 → 提交成功后立刻清掉。
 *
 * <h3>为什么不用 watch + storage 写死在每个组件里</h3>
 * 6 个表单就是 6 份「防抖 + try/catch + 清理」的复制粘贴，迟早有一份漏了清理
 * 留下幽灵草稿。这里收口成一份，组件只描述「快照长什么样、怎么填回去」。
 *
 * <h3>两种草稿的区别</h3>
 * - `draft` 模块：普通表单（新建教学班、题目编辑）。
 * - `steps` 模块：带步骤的流程（批量导入学生），额外记录 `step`，恢复时跳回原步骤。
 *
 * 介质统一用 localStorage（TTL 7 天）：草稿要跨浏览器重启才叫草稿，
 * 放 sessionStorage 一关标签页就没了，等于没存。
 */

import { onBeforeUnmount, watch } from 'vue'
import { namespace } from '../utils/storage.js'
import { currentUserId } from '../persist/identity.js'

/** 草稿默认存活 7 天：一周没回来改，基本可以认为是废案。 */
export const DRAFT_TTL_MS = 7 * 24 * 60 * 60 * 1000

/**
 * @param {object} options
 * @param {'draft'|'steps'|'form'} [options.module]  存储模块名（决定 key 前缀）
 * @param {() => string} options.context  上下文（如 `topic-9201-new`），换一条记录就该换 key
 * @param {() => object} [options.source] 快照取值函数；传了它就会自动 watch 并防抖保存
 * @param {(data: object) => void} options.apply 恢复时把数据填回表单
 * @param {(data: object) => boolean} [options.isEmpty] 判断「等于空表单」，为空则直接删草稿
 * @param {number} [options.ttlMs] 存活时间，默认 7 天
 * @param {number} [options.debounceMs] 防抖间隔，默认 400ms
 */
export function useDraft({
  module = 'draft',
  context,
  source,
  apply,
  isEmpty,
  ttlMs = DRAFT_TTL_MS,
  debounceMs = 400,
} = {}) {
  const ns = namespace(module, currentUserId(), { area: 'local' })
  let timer = null
  /** 提交成功 / 主动放弃后暂停，避免紧接着的表单重置又把空壳写回去。 */
  let paused = false

  function keyOf() {
    try {
      return typeof context === 'function' ? context() : context
    } catch {
      return ''
    }
  }

  async function flush() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    if (paused) return
    const ctx = keyOf()
    if (!ctx) return
    let data
    try {
      data = source ? source() : null
    } catch {
      return
    }
    if (!data) return
    if (isEmpty && isEmpty(data)) {
      await ns.remove(ctx)
      return
    }
    await ns.set(ctx, { data, savedAt: Date.now() }, { ttlMs })
  }

  function schedule() {
    if (paused) return
    if (timer) clearTimeout(timer)
    timer = setTimeout(flush, debounceMs)
  }

  /** 读一眼有没有草稿，不填回表单（用于打开弹窗前决定要不要问）。 */
  async function peek() {
    const ctx = keyOf()
    if (!ctx) return null
    const saved = await ns.get(ctx)
    if (!saved || !saved.data) return null
    if (isEmpty && isEmpty(saved.data)) {
      await ns.remove(ctx)
      return null
    }
    return saved.data
  }

  /** 有就填回去，返回恢复的数据；没有返回 null。 */
  async function restore() {
    const data = await peek()
    if (!data) return null
    paused = false
    apply?.(data)
    return data
  }

  /** 提交成功 / 用户明确不要了：删干净并暂停自动保存。 */
  async function clear() {
    const ctx = keyOf()
    paused = true
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    if (ctx) await ns.remove(ctx)
  }

  /** 重新开始记录（例如弹窗重新打开、换了一条记录）。 */
  function resume() {
    paused = false
  }

  if (source) {
    watch(source, schedule, { deep: true })
  }

  onBeforeUnmount(() => {
    // 关弹窗/切路由时把 400ms 内没落盘的那次补上
    if (timer) flush()
  })

  return { schedule, flush, peek, restore, clear, resume, keyOf }
}
