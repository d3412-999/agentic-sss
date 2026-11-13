/**
 * 评审状态轮询的纯函数策略层。
 *
 * <p>刻意与 Vue 解耦：退避曲线、停止条件、阶段文案这些规则可以单测，
 * 不用起浏览器、不用 mock 组件。{@link ../composables/useReviewStatus.js} 只负责
 * 「什么时候调接口、什么时候停」，判断标准全部在这里。</p>
 */

/** 单阶段状态文案（PENDING / RUNNING / SUCCEEDED / FAILED / MISSING）。 */
export const STAGE_STATUS = {
  PENDING: { label: '排队中', tone: 'info' },
  RUNNING: { label: '评审中', tone: 'info' },
  SUCCEEDED: { label: '已完成', tone: 'ok' },
  FAILED: { label: '评审失败', tone: 'danger' },
  MISSING: { label: '未提交', tone: '' },
}

export const STAGE_TYPE_LABEL = {
  SOURCE: '源码评审',
  REPORT: '报告评审',
}

/**
 * 指数退避：2s → 4s → 8s → 10s 封顶。
 *
 * @param {number} attempt 第几次重试（0 开始）
 * @param {{base?: number, max?: number}} [options]
 * @returns {number} 毫秒
 */
export function nextPollDelay(attempt, options = {}) {
  const base = options.base ?? 2000
  const max = options.max ?? 10000
  const safe = Math.max(0, Number.isFinite(attempt) ? Math.floor(attempt) : 0)
  return Math.min(max, base * 2 ** safe)
}

/**
 * 是否停止轮询。
 *
 * <p>只认服务端的 {@code finished}：它由后端按「两个阶段是否都进入终态」计算，
 * 前端自己拼状态很容易出现「源码 DONE、报告还在 PENDING 就停了」的旧问题。</p>
 */
export function shouldStopPolling(status) {
  return Boolean(status && status.finished === true)
}

/** 单个阶段视图：文案 + 色调 + 是否可以重试。 */
export function stageView(stage) {
  if (!stage || !stage.status) {
    return { type: '', label: '未提交', tone: '', score: null, error: '', retryable: false, detail: '' }
  }
  const meta = STAGE_STATUS[stage.status] || { label: stage.status, tone: '' }
  const detail = stage.error
    ? stage.error
    : stage.status === 'SUCCEEDED'
      ? `${stage.score ?? '—'} 分${stage.grade ? ` · ${stage.grade}` : ''}`
      : stage.status === 'MISSING'
        ? '还没有可评审的材料'
        : 'AI 正在处理，完成后会自动刷新'
  return {
    type: stage.type,
    label: meta.label,
    tone: meta.tone,
    score: stage.score ?? null,
    error: stage.error || '',
    retryable: stage.retryable === true,
    detail,
    status: stage.status,
  }
}

/**
 * 汇总视图：分阶段提示（源码评审中 / 报告评审中 / 已完成 / 部分失败 / 失败）。
 *
 * @param {{overall?: string, source?: object, report?: object}} status
 */
export function overallView(status) {
  if (!status || !status.overall || status.overall === 'NONE') {
    return { phase: 'NONE', text: '还没有提交材料', tone: '', running: false }
  }
  const source = stageView(status.source)
  const report = stageView(status.report)
  switch (status.overall) {
    case 'RUNNING': {
      const parts = []
      if (source.tone === 'info' && source.status !== 'MISSING') parts.push('源码评审中')
      if (report.tone === 'info' && report.status !== 'MISSING') parts.push('报告评审中')
      return {
        phase: 'RUNNING',
        text: parts.length ? parts.join(' · ') : '评审中',
        tone: 'info',
        running: true,
      }
    }
    case 'DONE':
      return { phase: 'DONE', text: '评审已完成', tone: 'ok', running: false }
    case 'PARTIAL':
      return { phase: 'PARTIAL', text: '部分材料评审失败，可单独重试', tone: 'warn', running: false }
    case 'FAILED':
      return { phase: 'FAILED', text: '评审失败，可重新触发', tone: 'danger', running: false }
    default:
      return { phase: status.overall, text: status.overall, tone: '', running: false }
  }
}

/**
 * 可重试的阶段：只有 FAILED 且后端标记 retryable 的才给按钮。
 *
 * <p>不提供「没结果就重试」：那会让用户在 AI 还在跑的时候重复触发，
 * 同一条提交上堆出多个任务，最后分数被后写的覆盖。</p>
 */
export function retryTargets(status) {
  if (!status) return []
  const targets = []
  for (const stage of [status.source, status.report]) {
    const view = stageView(stage)
    if (view.retryable && stage?.submissionId) {
      targets.push({
        type: stage.type,
        submissionId: stage.submissionId,
        label: STAGE_TYPE_LABEL[stage.type] || stage.type,
      })
    }
  }
  return targets
}

/**
 * 超过阈值仍未完成时展示「仍在评审中」与手动刷新，避免无限转圈让人以为卡死。
 */
export function shouldShowRefreshHint(elapsedMs, options = {}) {
  const threshold = options.threshold ?? 30000
  return Number(elapsedMs) >= threshold
}

/** 已用时的短文案：只用于提示，不做精确计时。 */
export function elapsedText(elapsedMs) {
  const seconds = Math.max(0, Math.floor(Number(elapsedMs) / 1000))
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  return `${minutes} 分 ${seconds % 60} 秒`
}
