import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  nextPollDelay,
  shouldStopPolling,
  stageView,
  overallView,
  retryTargets,
  shouldShowRefreshHint,
  elapsedText,
} from '../src/utils/reviewPoll.js'

test('退避从 2s 起步、10s 封顶', () => {
  assert.equal(nextPollDelay(0), 2000)
  assert.equal(nextPollDelay(1), 4000)
  assert.equal(nextPollDelay(2), 8000)
  assert.equal(nextPollDelay(3), 10000)
  assert.equal(nextPollDelay(10), 10000)
  // 非法输入不应算出 NaN 定时器
  assert.equal(nextPollDelay(-3), 2000)
  assert.equal(nextPollDelay(Number.NaN), 2000)
})

test('只有 finished 才停止轮询', () => {
  assert.equal(shouldStopPolling(null), false)
  assert.equal(shouldStopPolling({ overall: 'RUNNING', finished: false }), false)
  assert.equal(shouldStopPolling({ overall: 'DONE', finished: true }), true)
  assert.equal(shouldStopPolling({ overall: 'FAILED', finished: true }), true)
})

test('阶段视图区分成功 / 失败 / 缺材料', () => {
  assert.equal(stageView({ status: 'SUCCEEDED', score: 88.5, grade: 'B' }).label, '已完成')
  assert.match(stageView({ status: 'SUCCEEDED', score: 88.5, grade: 'B' }).detail, /88.5/)
  const failed = stageView({ status: 'FAILED', error: 'AI 超时', retryable: true })
  assert.equal(failed.tone, 'danger')
  assert.equal(failed.retryable, true)
  assert.equal(failed.detail, 'AI 超时')
  assert.equal(stageView({ status: 'MISSING' }).label, '未提交')
  assert.equal(stageView(null).label, '未提交')
})

test('一个阶段跑完、另一个还在跑时不收尾', () => {
  const view = overallView({
    overall: 'RUNNING',
    finished: false,
    source: { status: 'SUCCEEDED', score: 90 },
    report: { status: 'RUNNING' },
  })
  assert.equal(view.running, true)
  assert.match(view.text, /报告评审中/)
  assert.doesNotMatch(view.text, /源码评审中/)
})

test('部分失败与全部失败的文案不同', () => {
  const partial = overallView({
    overall: 'PARTIAL',
    source: { status: 'SUCCEEDED', score: 90 },
    report: { status: 'FAILED', error: '解析失败' },
  })
  assert.equal(partial.tone, 'warn')
  const failed = overallView({
    overall: 'FAILED',
    source: { status: 'FAILED' },
    report: { status: 'FAILED' },
  })
  assert.equal(failed.tone, 'danger')
  assert.equal(overallView({ overall: 'NONE' }).phase, 'NONE')
})

test('只对失败且可重试的阶段给重试入口', () => {
  const targets = retryTargets({
    source: { submissionId: '1', type: 'SOURCE', status: 'SUCCEEDED' },
    report: { submissionId: '2', type: 'REPORT', status: 'FAILED', retryable: true },
  })
  assert.equal(targets.length, 1)
  assert.equal(targets[0].submissionId, '2')
  assert.equal(targets[0].label, '报告评审')

  const running = retryTargets({
    report: { submissionId: '2', type: 'REPORT', status: 'RUNNING', retryable: false },
  })
  assert.equal(running.length, 0)
})

test('30 秒后才提示手动刷新', () => {
  assert.equal(shouldShowRefreshHint(29999), false)
  assert.equal(shouldShowRefreshHint(30000), true)
  assert.equal(elapsedText(5000), '5 秒')
  assert.equal(elapsedText(75000), '1 分 15 秒')
})
