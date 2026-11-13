import { ref } from 'vue'
import { api } from '../api/endpoints'

/**
 * 答辩页防作弊「辅助检测」。
 *
 * 先说清楚这个模块不是什么东西：它不是判据，也不是防线。浏览器沙箱决定了
 * 前端既看不见别的标签页（豆包 / Copilot 开在旁边完全无从得知），也拦不住
 * 用手机查答案。这里做的是**提高作弊成本 + 留下可复核的证据链**，
 * 最终裁决权在教师（后端 VIOLATED 只是暂停键，教师可恢复或维持终止）。
 *
 * 检测项与口径：
 * - 切标签页 / 最小化（visibilitychange）：计为一次 TAB_SWITCH；
 * - 窗口失焦（blur）：同一动作常与 visibilitychange 同时触发，1.5s 内去重，
 *   否则一次切屏会被记两次、阈值 2 就直接误杀；
 * - 开发者工具：用「外框与视口尺寸差」判断停靠面板，分离窗口/内嵌模式检测不到，
 *   所以只做辅助；F12 / Ctrl+Shift+I 一律拦截并单独记为 SHORTCUT_BLOCK（不计阈值）；
 * - 粘贴：只在「粘贴进本页」时可见，且本场答辩是语音作答、没有输入框，
 *   实际几乎不会触发 —— 保留它是为了留下「页面内粘贴」的痕迹，不是主要手段；
 * - 复制 / 保存：拦截属于体验层，拦截成功不等于违规，因此只记 MINOR。
 *
 * 首次进入有 8 秒宽限期：摄像头授权弹窗、系统通知都会让窗口短暂失焦，
 * 这段时间内的失焦一律不计。
 */

/** 计入终止阈值的类型；服务端仍是最终判定者，这里只用于「要不要弹警告」。 */
const MAJOR_TYPES = new Set(['TAB_SWITCH', 'DEVTOOLS', 'PASTE_PATTERN'])
/** 进入页面 / 重新开始后的宽限期（毫秒）。 */
const GRACE_MS = 8000
/** 同一次离开的去重窗口（毫秒）。 */
const DEDUPE_MS = 1500
/** 开发者工具尺寸轮询间隔。 */
const DEVTOOLS_INTERVAL_MS = 2000
/** 同一类证据的最短上报间隔，避免事件风暴把接口打满。 */
const REPORT_COOLDOWN_MS = 10000
/** 粘贴内容达到这个长度才当作可疑（短复制粘贴是正常操作）。 */
const PASTE_MIN_CHARS = 200

/**
 * @param {object} options
 * @param {import('vue').Ref<string>} options.sessionId 答辩会话 ID
 * @param {import('vue').Ref<boolean>} options.active 是否处于「应当被检测」的状态
 * @param {(result:object)=>void} [options.onWarn] 已记录但未终止时回调（弹一次警告）
 * @param {(result:object)=>void} [options.onTerminated] 触发终止时回调
 */
export function useDefenseGuard({ sessionId, active, onWarn, onTerminated }) {
  const violations = ref(0)
  const terminated = ref(false)
  const threshold = ref(0)

  let started = false
  let armedAt = 0
  let lastAwayAt = 0
  let lastReportAt = 0
  let devtoolsTimer = null
  let devtoolsFlaggedAt = 0
  let pasteAt = 0
  let sending = false
  const reported = new Map()

  function canCount() {
    return started && active.value && !terminated.value && Date.now() > armedAt
  }

  /** 让某个时间点之后的操作不计违规：开摄像头、录音授权、系统弹窗都用它。 */
  function suppress(ms = 5000) {
    armedAt = Math.max(armedAt, Date.now() + ms)
  }

  /** 冷却：同类型证据最多每 10s 报一次，超出的直接丢弃。 */
  function cooling(type) {
    const now = Date.now()
    if (now - lastReportAt < REPORT_COOLDOWN_MS) return true
    const previous = reported.get(type) || 0
    if (now - previous < REPORT_COOLDOWN_MS) return true
    reported.set(type, now)
    lastReportAt = now
    return false
  }

  async function report(type, detail = {}) {
    if (!canCount() || sending) return
    if (cooling(type)) return
    sending = true
    try {
      const result = await api.reportDefenseViolation(sessionId.value, type, {
        ...detail,
        at: new Date().toISOString(),
        visibility: typeof document === 'undefined' ? '' : document.visibilityState,
      })
      if (result?.threshold) threshold.value = result.threshold
      if (result?.count != null) violations.value = result.count
      if (result?.terminated) {
        terminated.value = true
        onTerminated?.(result)
        return
      }
      // MINOR 类型（复制/快捷键拦截）服务端不计数，不弹「第 N 次违规」以免吓人
      if (result?.accepted && MAJOR_TYPES.has(type)) onWarn?.(result)
    } catch {
      // 上报失败不阻塞答辩：检测本身是辅助，不能因为网络抖动打断学生
    } finally {
      sending = false
    }
  }

  /* ---------------- 事件处理 ---------------- */

  function onVisibilityChange() {
    if (!canCount()) return
    if (document.visibilityState !== 'hidden') return
    const now = Date.now()
    if (now - lastAwayAt < DEDUPE_MS) return
    lastAwayAt = now
    void report('TAB_SWITCH', { reason: 'visibilitychange' })
  }

  function onBlur() {
    if (!canCount()) return
    // 已经切到后台时 visibilitychange 更准；这里只处理「窗口还在但焦点走了」的情况
    if (document.visibilityState === 'hidden') return
    const now = Date.now()
    if (now - lastAwayAt < DEDUPE_MS) return
    lastAwayAt = now
    void report('TAB_SWITCH', { reason: 'window-blur' })
  }

  function devtoolsOpen() {
    const widthGap = Math.abs(window.outerWidth - window.innerWidth)
    const heightGap = Math.abs(window.outerHeight - window.innerHeight)
    // 停靠式面板会撑大外框；正常浏览器外框差通常在 20~120px（窗口边框 + 地址栏）
    return widthGap > 200 || heightGap > 260
  }

  function checkDevtools() {
    if (!canCount()) return
    if (!devtoolsOpen()) return
    const now = Date.now()
    if (now - devtoolsFlaggedAt < 60_000) return
    devtoolsFlaggedAt = now
    void report('DEVTOOLS', { reason: 'viewport-gap', gap: 'docked-panel' })
  }

  function onPaste(event) {
    if (!canCount()) return
    const text = String(event.clipboardData?.getData('text') || '')
    const looksLikeCode = /```|(^|\n)\s*(public|private|class|function|def|import)\s/.test(text)
    if (text.length < PASTE_MIN_CHARS && !looksLikeCode) return
    const now = Date.now()
    if (now - pasteAt < 10_000) return
    pasteAt = now
    void report('PASTE_PATTERN', { length: text.length, codeFence: text.includes('```') })
  }

  /** 输入框里的复制粘贴是正常操作，不能拦也不能记。 */
  function inEditable(target) {
    if (!target || !(target instanceof Element)) return false
    const tag = target.tagName
    return tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable
  }

  function onKeydown(event) {
    if (!canCount()) return
    const key = String(event.key || '').toLowerCase()
    const withCtrl = event.ctrlKey || event.metaKey
    const devtools = event.key === 'F12' || (withCtrl && event.shiftKey && ['i', 'j', 'c'].includes(key))
    const blockCopy = withCtrl && key === 'c' && !inEditable(event.target)
    const blockSave = withCtrl && ['s', 'u', 'p'].includes(key)
    if (!devtools && !blockCopy && !blockSave) return
    event.preventDefault()
    event.stopPropagation()
    void report('SHORTCUT_BLOCK', { key: event.key, ctrl: event.ctrlKey, meta: event.metaKey })
  }

  function onCopy(event) {
    if (!canCount() || inEditable(event.target)) return
    event.preventDefault()
    void report('COPY_BLOCK', { reason: 'copy-event' })
  }

  /* ---------------- 生命周期 ---------------- */

  function start() {
    if (started || typeof document === 'undefined') return
    started = true
    armedAt = Date.now() + GRACE_MS
    document.addEventListener('visibilitychange', onVisibilityChange)
    document.addEventListener('paste', onPaste, true)
    document.addEventListener('copy', onCopy, true)
    document.addEventListener('keydown', onKeydown, true)
    window.addEventListener('blur', onBlur)
    devtoolsTimer = setInterval(checkDevtools, DEVTOOLS_INTERVAL_MS)
  }

  function stop() {
    if (typeof document === 'undefined') return
    started = false
    document.removeEventListener('visibilitychange', onVisibilityChange)
    document.removeEventListener('paste', onPaste, true)
    document.removeEventListener('copy', onCopy, true)
    document.removeEventListener('keydown', onKeydown, true)
    window.removeEventListener('blur', onBlur)
    if (devtoolsTimer) {
      clearInterval(devtoolsTimer)
      devtoolsTimer = null
    }
  }

  /** 源码面板自己的拦截（右键/Ctrl+C）也走同一套上报，保证教师端看到完整证据。 */
  function notifyBlocked(type) {
    if (!canCount()) return
    void report(type === 'SHORTCUT_BLOCK' ? 'SHORTCUT_BLOCK' : 'COPY_BLOCK', { reason: 'panel' })
  }

  return { violations, terminated, threshold, start, stop, suppress, notifyBlocked, report }
}
