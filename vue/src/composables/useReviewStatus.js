import { computed, onBeforeUnmount, ref, unref, watch } from 'vue'
import { api } from '../api/endpoints'
import {
  nextPollDelay,
  overallView,
  retryTargets,
  shouldStopPolling,
  stageView,
} from '../utils/reviewPoll'

/**
 * 统一评审状态的轮询控制。
 *
 * <p>把「加载卡住」的四类根因一次堵住：</p>
 * <ol>
 *   <li><b>只拉一次就停</b>：只要后端 <code>finished=false</code> 就继续退避重试（2s→10s 封顶）；</li>
 *   <li><b>异常分支不复位 loading</b>：所有分支都在 finally 里收尾，网络错误也会继续重试而不是干掉轮询；</li>
 *   <li><b>旧响应覆盖新状态</b>：每次请求带自增序号，落后的响应直接丢弃；</li>
 *   <li><b>切走再回来不再刷新</b>：监听 <code>visibilitychange</code>，页面重新可见时立即补拉一次。</li>
 * </ol>
 *
 * <p>组件卸载会 abort 未完成请求并清掉定时器，不会在离开页面后继续打接口。</p>
 *
 * @param {import('vue').Ref<string>|string|Function} source 提交物 ID（可以是 ref 或取值函数）
 * @param {{onFinished?: Function}} [options]
 */
export function useReviewStatus(source, options = {}) {
  const status = ref(null)
  const loading = ref(false)
  const error = ref('')
  const elapsed = ref(0)
  const stopped = ref(true)

  const overall = computed(() => overallView(status.value))
  const sourceStage = computed(() => stageView(status.value?.source))
  const reportStage = computed(() => stageView(status.value?.report))
  const retryables = computed(() => retryTargets(status.value))

  let timer = null
  let ticker = null
  let attempt = 0
  let startedAt = 0
  let seq = 0
  let controller = null
  let finished = false

  function currentId() {
    return typeof source === 'function' ? source() : unref(source)
  }

  function clearTimers() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    if (ticker) {
      clearInterval(ticker)
      ticker = null
    }
  }

  function schedule() {
    clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      void load({ silent: true })
    }, nextPollDelay(attempt))
  }

  async function load({ silent = false } = {}) {
    const id = currentId()
    if (!id) return
    if (finished) return

    controller?.abort()
    controller = typeof AbortController !== 'undefined' ? new AbortController() : null
    const mine = ++seq
    if (!silent) loading.value = true

    try {
      const data = await api.reviewStatus(id, controller ? { signal: controller.signal } : {})
      if (mine !== seq) return
      status.value = data
      error.value = ''
      if (shouldStopPolling(data)) {
        finished = true
        stopped.value = true
        clearTimers()
        if (typeof options.onFinished === 'function') options.onFinished(data)
        return
      }
      attempt += 1
      schedule()
    } catch (err) {
      if (mine !== seq) return
      // -2 = 主动中断（组件卸载 / 切换提交物），不是错误
      if (err?.code === -2 || err?.name === 'AbortError') return
      error.value = err?.message || '读取评审状态失败'
      // 网络抖动也继续重试：用户断网恢复后应该自动补齐，而不是停在错误页
      attempt += 1
      schedule()
    } finally {
      if (mine === seq) loading.value = false
    }
  }

  /** 从头开始：重置退避与已用时，立即拉一次。 */
  function start() {
    clearTimers()
    finished = false
    stopped.value = false
    attempt = 0
    startedAt = Date.now()
    elapsed.value = 0
    clearInterval(ticker)
    ticker = setInterval(() => {
      elapsed.value = Date.now() - startedAt
    }, 1000)
    void load()
  }

  function stop() {
    clearTimers()
    controller?.abort()
    controller = null
    stopped.value = true
  }

  /** 手动刷新：重置退避，避免用户点一次还要等 10s。 */
  function refresh() {
    finished = false
    stopped.value = false
    attempt = 0
    if (!startedAt) startedAt = Date.now()
    void load({ silent: false })
  }

  /** 重新触发某个阶段的评审（后端 reanalyze），成功后恢复轮询。 */
  async function retry(target) {
    if (!target?.submissionId) return
    await api.reanalyzeSubmission(target.submissionId, true)
    refresh()
  }

  function onVisible() {
    if (typeof document === 'undefined' || document.hidden) return
    if (stopped.value || finished) return
    // 切回来时立即补拉，不等下一个退避点
    attempt = 0
    void load({ silent: true })
  }

  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onVisible)
  }

  watch(
    () => currentId(),
    (value, previous) => {
      if (value && value !== previous) start()
    },
  )

  onBeforeUnmount(() => {
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', onVisible)
    }
    stop()
  })

  return {
    status,
    loading,
    error,
    elapsed,
    stopped,
    overall,
    sourceStage,
    reportStage,
    retryables,
    start,
    stop,
    refresh,
    retry,
  }
}
