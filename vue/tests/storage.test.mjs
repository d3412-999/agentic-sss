/**
 * storage 工具层单元测试。
 *
 * 用 Node 内置 `node --test`，不引入 vitest/jsdom：
 * 这里要测的是纯逻辑（信封编解码、降级、TTL、事件），只需要两个假 Storage 对象。
 *
 * 运行：`cd vue && npm run test:storage`
 */

import test from 'node:test'
import assert from 'node:assert/strict'

import {
  namespace,
  storage,
  setJSON,
  getJSON,
  buildKey,
  parseKey,
  encode,
  decode,
  hashOf,
  watchKey,
  cleanup,
  list,
  stats,
  clearAll,
  probe,
  storageHealth,
  onStorageHealth,
  SCHEMA_VERSION,
} from '../src/utils/storage.js'

// ---------------------------------------------------------------------------
// 假 Storage
// ---------------------------------------------------------------------------

/** 按 UTF-16 字节计配额的假 localStorage；limit 用来模拟写满。 */
class MockStorage {
  constructor(limit = Infinity) {
    this.map = new Map()
    this.limit = limit
  }

  get length() {
    return this.map.size
  }

  key(index) {
    return [...this.map.keys()][index] ?? null
  }

  getItem(key) {
    return this.map.has(key) ? this.map.get(key) : null
  }

  setItem(key, value) {
    const size = (key.length + String(value).length) * 2
    let used = 0
    for (const [k, v] of this.map) {
      if (k !== key) used += (k.length + String(v).length) * 2
    }
    if (used + size > this.limit) {
      const err = new Error('quota exceeded')
      err.name = 'QuotaExceededError'
      err.code = 22
      throw err
    }
    this.map.set(key, String(value))
  }

  removeItem(key) {
    this.map.delete(key)
  }

  clear() {
    this.map.clear()
  }
}

/** 隐私模式：任何读写都抛 SecurityError。 */
class BlockedStorage {
  get length() {
    return 0
  }

  key() {
    return null
  }

  getItem() {
    throw Object.assign(new Error('blocked'), { name: 'SecurityError' })
  }

  setItem() {
    throw Object.assign(new Error('blocked'), { name: 'SecurityError' })
  }

  removeItem() {
    throw Object.assign(new Error('blocked'), { name: 'SecurityError' })
  }
}

const listeners = []

function installWindow(localStorage, sessionStorage) {
  globalThis.window = {
    localStorage,
    sessionStorage,
    addEventListener(type, fn) {
      if (type === 'storage') listeners.push(fn)
    },
  }
}

/** 模拟另一个标签页写入：storage 事件不会在写入方自己的标签页触发，所以只能手工派发。 */
function fireStorageEvent(key, newValue, area) {
  listeners.forEach((fn) => fn({ key, newValue, oldValue: null, storageArea: area }))
}

let mockLocal
let mockSession

function reset() {
  mockLocal = new MockStorage()
  mockSession = new MockStorage()
  listeners.length = 0
  installWindow(mockLocal, mockSession)
}

// 每个用例都从干净的假存储开始：模块内的内存兜底与 health 通过 probe() 复位
test.beforeEach(async () => {
  reset()
  await clearAll()
  await probe()
})

// ---------------------------------------------------------------------------
// 1. 基本读写与命名空间隔离
// ---------------------------------------------------------------------------

test('set/get 往返，且 key 形如 sss:{module}:{userId}:{context}', async () => {
  const ns = namespace('upload', 1024)
  const snapshot = { files: [{ fileId: '1', filename: 'a.zip', size: 10 }], progress: 60 }

  await ns.set('123-SOURCE', snapshot)
  const restored = await ns.get('123-SOURCE')

  assert.deepEqual(restored, snapshot)
  assert.ok(mockLocal.getItem('sss:upload:1024:123-SOURCE'), '写入的 key 应符合命名规范')
  assert.equal(buildKey('upload', 1024, '123-SOURCE'), 'sss:upload:1024:123-SOURCE')
})

test('不同 userId 的同名 context 互不覆盖（多账号不串味）', async () => {
  const alice = namespace('draft', 'A')
  const bob = namespace('draft', 'B')

  await alice.set('topic-new', { title: 'A 的草稿' })
  await bob.set('topic-new', { title: 'B 的草稿' })

  assert.deepEqual(await alice.get('topic-new'), { title: 'A 的草稿' })
  assert.deepEqual(await bob.get('topic-new'), { title: 'B 的草稿' })
  assert.notEqual(alice.key('topic-new'), bob.key('topic-new'))
})

test('keys / clear 只作用于本命名空间', async () => {
  const ns = namespace('form', 7)
  const other = namespace('form', 8)
  await ns.set('a', { v: 1 })
  await ns.set('b', { v: 2 })
  await other.set('a', { v: 3 })

  assert.deepEqual((await ns.keys()).sort(), ['a', 'b'])
  assert.equal(await ns.clear(), 2)
  assert.deepEqual(await ns.keys(), [])
  assert.deepEqual(await other.get('a'), { v: 3 }, '别的用户的数据不受影响')
})

// ---------------------------------------------------------------------------
// 2. 版本升级、TTL、校验
// ---------------------------------------------------------------------------

test('schema 版本不匹配的记录读取时丢弃并清理', async () => {
  const key = buildKey('upload', 1, 'v0')
  // 手工塞一条旧版本信封：v 比当前版本小 1
  mockLocal.setItem(
    key,
    JSON.stringify({ v: SCHEMA_VERSION - 1, t: Date.now(), e: 0, h: hashOf('{"a":1}'), d: '{"a":1}' }),
  )

  assert.equal(await getJSON(key), null)
  assert.equal(mockLocal.getItem(key), null, '脏数据应被就地删除，避免每次读都踩')
})

test('TTL 过期后读不到', async () => {
  const ns = namespace('upload', 1)
  await ns.set('expired', { v: 1 }, { expiresAt: Date.now() - 1 })
  assert.equal(await ns.get('expired'), null)
})

test('未过期时 TTL 不影响读取', async () => {
  const ns = namespace('upload', 1)
  await ns.set('alive', { v: 1 }, { ttlMs: 60_000 })
  assert.deepEqual(await ns.get('alive'), { v: 1 })
})

test('内容被篡改（哈希不匹配）时丢弃', async () => {
  const key = buildKey('upload', 1, 'tampered')
  const raw = encode({ v: 1 })
  const env = JSON.parse(raw)
  env.d = JSON.stringify({ v: 999 }) // 改内容但留旧哈希
  mockLocal.setItem(key, JSON.stringify(env))

  assert.equal(await getJSON(key), null)
})

test('encode/decode 能识别非信封内容', () => {
  assert.equal(decode('not-json').ok, false)
  assert.equal(decode('hello').reason, 'corrupt')
  assert.equal(decode(JSON.stringify({ foo: 1 })).reason, 'corrupt')
  assert.deepEqual(decode(encode({ a: 1 })).value, { a: 1 })
})

// ---------------------------------------------------------------------------
// 3. 降级：配额超限 / 隐私模式
// ---------------------------------------------------------------------------

test('QuotaExceeded 时不抛异常，转内存态且能读回', async () => {
  mockLocal = new MockStorage(120) // 只够放很小的一条
  installWindow(mockLocal, mockSession)

  const ns = namespace('upload', 1)
  const big = { blob: 'x'.repeat(500) }
  const ok = await ns.set('big', big)

  assert.equal(ok, false, '写不进 storage 应返回 false 表示降级')
  assert.equal(storageHealth().quotaExceeded, true)
  assert.equal(storageHealth().memoryOnly, true)
  assert.deepEqual(await ns.get('big'), big, '内存兜底保证当前页面仍能读到')
})

test('隐私模式（storage 读写抛错）下不阻塞，仅降级', async () => {
  installWindow(new BlockedStorage(), new BlockedStorage())

  const ns = namespace('draft', 1)
  const ok = await ns.set('x', { title: '草稿' })

  assert.equal(ok, false)
  assert.equal(storageHealth().memoryOnly, true)
  assert.deepEqual(await ns.get('x'), { title: '草稿' })
  assert.equal(await probe(), false, 'probe 应如实报告不可用，而不是抛错')
})

test('写入失败会通知订阅者（供 UI 弹提示）', async () => {
  mockLocal = new MockStorage(60)
  installWindow(mockLocal, mockSession)

  const events = []
  const off = storage.onError((payload) => events.push(payload))

  await namespace('upload', 1).set('big', { blob: 'y'.repeat(400) })
  off()

  assert.ok(events.some((e) => e.type === 'quota'), `应收到配额事件，实际 ${JSON.stringify(events)}`)
  assert.equal(typeof events[0].message, 'string')
})

test('单条记录过大时发 oversize 提示（仍然写入）', async () => {
  const events = []
  const off = storage.onError((payload) => events.push(payload))

  const ok = await namespace('upload', 1).set('huge', { blob: 'z'.repeat(300 * 1024) })
  off()

  assert.equal(ok, true)
  assert.ok(events.some((e) => e.type === 'oversize'))
})

// ---------------------------------------------------------------------------
// 4. 多标签页同步
// ---------------------------------------------------------------------------

test('其它标签页改写时触发 watch 回调', async () => {
  const ns = namespace('upload', 1)
  const received = []
  const off = ns.watch('123-SOURCE', (value, meta) => received.push({ value, meta }))

  // 另一个标签页写入（用同名 key 的信封模拟）
  const foreign = encode({ files: [{ fileId: '9' }], progress: 60 })
  fireStorageEvent('sss:upload:1:123-SOURCE', foreign, mockLocal)

  off()
  assert.equal(received.length, 1)
  assert.equal(received[0].value.progress, 60)
  assert.equal(received[0].meta.external, true)

  // 取消订阅后不再回调
  fireStorageEvent('sss:upload:1:123-SOURCE', foreign, mockLocal)
  assert.equal(received.length, 1)
})

test('与本命名空间无关的 key 不触发回调', async () => {
  const ns = namespace('upload', 1)
  const received = []
  const off = ns.watch('123-SOURCE', (value) => received.push(value))

  fireStorageEvent('sss:upload:2:123-SOURCE', encode({ progress: 10 }), mockLocal)
  fireStorageEvent('sss:draft:1:123-SOURCE', encode({ progress: 10 }), mockLocal)

  off()
  assert.equal(received.length, 0)
})

// ---------------------------------------------------------------------------
// 5. 盘点与清理
// ---------------------------------------------------------------------------

test('list/stats 给出模块、用户、大小与过期标记', async () => {
  await namespace('upload', 1).set('a', { v: 1 })
  await namespace('draft', 1).set('b', { v: 2 }, { expiresAt: Date.now() - 1 })
  // sessionStorage 里的记录也要被盘点出来
  await namespace('steps', 1, { area: 'session' }).set('c', { v: 3 })

  const rows = await list()
  const modules = rows.map((row) => row.module).sort()
  assert.deepEqual(modules, ['draft', 'steps', 'upload'])

  const draftRow = rows.find((row) => row.module === 'draft')
  assert.equal(draftRow.expired, true)
  assert.equal(draftRow.area, 'local')
  assert.ok(draftRow.bytes > 0)

  const summary = await stats()
  assert.equal(summary.count, 3)
  assert.ok(summary.totalBytes > 0)
  assert.ok(summary.byArea.session > 0)
  assert.ok(summary.byModule.upload > 0)
})

test('cleanup 只清过期与非法记录，正常记录保留', async () => {
  const ns = namespace('upload', 1)
  await ns.set('keep', { v: 1 }, { ttlMs: 60_000 })
  await ns.set('gone', { v: 2 }, { expiresAt: Date.now() - 1 })
  mockLocal.setItem(buildKey('upload', 1, 'broken'), '{"v":999}')

  const result = await cleanup()
  assert.equal(result.removed.length, 2)
  assert.deepEqual(await ns.get('keep'), { v: 1 })
})

test('clearAll 只清 sss: 前缀，不动同源其它 key', async () => {
  mockLocal.setItem('other-app-token', 'keep-me')
  await namespace('upload', 1).set('a', { v: 1 })
  await namespace('draft', 1).set('b', { v: 2 })

  const removed = await clearAll()
  assert.equal(removed, 2)
  assert.equal(mockLocal.getItem('other-app-token'), 'keep-me')
})

// ---------------------------------------------------------------------------
// 6. key 解析
// ---------------------------------------------------------------------------

test('parseKey 能拆出模块/用户/上下文，认不出返回 null', () => {
  assert.deepEqual(parseKey('sss:upload:1024:123-SOURCE'), {
    module: 'upload',
    userId: '1024',
    context: '123-SOURCE',
  })
  assert.equal(parseKey('sss.tasks'), null)
  assert.equal(parseKey('other:upload:1:x'), null)
  assert.equal(parseKey(''), null)
})

test('context 里的冒号被替换，不会破坏层级', () => {
  const key = buildKey('upload', 1, 'a:b:c')
  assert.equal(key, 'sss:upload:1:a_b_c')
  assert.equal(parseKey(key).context, 'a_b_c')
})

test('健康状态订阅只在变化时通知', async () => {
  const seen = []
  const off = onStorageHealth((state) => seen.push(state.memoryOnly))
  await setJSON(buildKey('upload', 1, 'x'), { a: 1 })
  off()
  assert.equal(seen.length, 0, '一直健康时不该反复通知')
})
