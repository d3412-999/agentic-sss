/**
 * 上传快照（persist/uploadStore.js）的单元测试。
 *
 * 重点验证「刷新后到底能恢复什么、恢复成什么状态」，这是验收用例 1/2/5 的核心逻辑：
 * - 已成功的文件只靠 fileId 恢复（不需要 File 本体，所以能跨浏览器重启）；
 * - 队列里的文件只能留票据，刷新后一律变成「需重新选择」；
 * - 中途刷新（status=uploading）不能残留成「正在上传」的假状态。
 *
 * 说明：Node 里没有 window/localStorage，这里先装一套假的再动态 import。
 * 用静态 import 会因为 stores/session.js 在模块顶层读 localStorage 而直接炸掉。
 */

import test from 'node:test'
import assert from 'node:assert/strict'

class MockStorage {
  constructor() {
    this.map = new Map()
  }

  get length() {
    return this.map.size
  }

  key(i) {
    return [...this.map.keys()][i] ?? null
  }

  getItem(key) {
    return this.map.has(key) ? this.map.get(key) : null
  }

  setItem(key, value) {
    this.map.set(key, String(value))
  }

  removeItem(key) {
    this.map.delete(key)
  }
}

const local = new MockStorage()
const sessionArea = new MockStorage()
globalThis.localStorage = local
globalThis.sessionStorage = sessionArea
globalThis.window = {
  localStorage: local,
  sessionStorage: sessionArea,
  addEventListener() {},
}

const { createUploadStore, snapshotOf, contextOf } = await import('../src/persist/uploadStore.js')
const { buildKey, storage } = await import('../src/utils/storage.js')

const USER = '9001'
const store = createUploadStore({ selectionId: '555', type: 'SOURCE', userId: USER })

test.beforeEach(async () => {
  local.map.clear()
  sessionArea.map.clear()
  await store.clear()
})

test('context 区分源码与报告，互不覆盖', () => {
  assert.equal(contextOf('555', 'SOURCE'), '555-SOURCE')
  assert.equal(contextOf('555', 'REPORT'), '555-REPORT')
})

test('snapshotOf 把成功项与未完成项分开，中断状态单独标记', () => {
  const snap = snapshotOf([
    { name: 'a.zip', size: 10, lastModified: 1, status: 'success', fileId: 'f1' },
    { name: 'b.zip', size: 20, lastModified: 2, status: 'uploading', progress: 60 },
    { name: 'c.zip', size: 30, lastModified: 3, status: 'failed', error: '网络中断' },
    { name: 'd.zip', size: 40, lastModified: 4, status: 'pending' },
  ])

  assert.equal(snap.done.length, 1)
  assert.deepEqual(snap.done[0].fileId, 'f1')
  assert.equal(snap.queue.length, 3)

  const byName = Object.fromEntries(snap.queue.map((q) => [q.name, q]))
  assert.equal(byName['b.zip'].status, 'interrupted', '刷新时正在传的文件必须标成中断')
  assert.equal(byName['b.zip'].progress, 60)
  assert.equal(byName['c.zip'].error, '网络中断')
  // File / AbortController 这类不可序列化字段不能被带进去
  assert.equal(byName['b.zip'].file, undefined)
  assert.equal(byName['b.zip'].controller, undefined)
})

test('已成功列表跨介质恢复：写入 localStorage，读回字段完整', async () => {
  const snap = snapshotOf([
    { name: 'a.zip', size: 10, lastModified: 111, status: 'success', fileId: 'f1' },
  ])
  await store.saveDone(snap.done)

  assert.ok(local.getItem(buildKey('upload', USER, '555-SOURCE')), '应落在 localStorage')

  const restored = await store.loadDone()
  assert.equal(restored.length, 1)
  assert.deepEqual(
    { name: restored[0].name, size: restored[0].size, fileId: restored[0].fileId },
    { name: 'a.zip', size: 10, fileId: 'f1' },
  )
})

test('队列落在 sessionStorage，刷新后仍可读、关标签页才消失', async () => {
  const snap = snapshotOf([{ name: 'b.zip', size: 20, lastModified: 2, status: 'failed', error: '超时' }])
  await store.saveQueue(snap.queue)

  assert.ok(sessionArea.getItem(buildKey('upload', USER, '555-SOURCE')), '队列应在 sessionStorage')
  const restored = await store.loadQueue()
  assert.equal(restored[0].name, 'b.zip')
  assert.equal(restored[0].error, '超时')
})

test('saveDone([]) 会删掉记录，不留空壳', async () => {
  await store.saveDone([{ name: 'a.zip', size: 1, fileId: 'f1' }])
  await store.saveDone([])
  assert.equal(local.getItem(buildKey('upload', USER, '555-SOURCE')), null)
  assert.deepEqual(await store.loadDone(), [])
})

test('缺少 fileId 的脏记录会被过滤，不产生不可用的「已完成」项', async () => {
  // 直接写一条没有 fileId 的记录（模拟旧版本或手改数据）
  await storage.setJSON(buildKey('upload', USER, '555-SOURCE'), {
    done: [{ name: 'no-id.zip', size: 1 }, { name: 'ok.zip', size: 2, fileId: 'f2' }],
  })
  const restored = await store.loadDone()
  assert.deepEqual(restored.map((r) => r.name), ['ok.zip'])
})

test('损坏的原始数据不会让恢复崩溃', async () => {
  local.setItem(buildKey('upload', USER, '555-SOURCE'), '{not-json')
  assert.deepEqual(await store.loadDone(), [])
})

test('提交成功后 clear() 同时清掉两份记录', async () => {
  await store.saveDone([{ name: 'a.zip', size: 1, fileId: 'f1' }])
  await store.saveQueue([{ name: 'b.zip', size: 2, status: 'failed' }])
  await store.clear()

  assert.equal(local.getItem(buildKey('upload', USER, '555-SOURCE')), null)
  assert.equal(sessionArea.getItem(buildKey('upload', USER, '555-SOURCE')), null)
})

// ---------------------------------------------------------------------------
// 多标签页占用
// ---------------------------------------------------------------------------

test('占用声明：后到者能看到，持有者释放后消失', async () => {
  await store.claim('tab-A')
  const owner = await store.readOwner()
  assert.equal(owner.tabId, 'tab-A')
  assert.ok(owner.at > 0)
})

test('非持有者无法释放别人的占用', async () => {
  await store.claim('tab-A')
  await store.release('tab-B')
  assert.equal((await store.readOwner()).tabId, 'tab-A', 'B 不能把 A 的占用抹掉')

  await store.release('tab-A')
  assert.equal(await store.readOwner(), null)
})
