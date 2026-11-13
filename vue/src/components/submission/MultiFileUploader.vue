<script setup>
/**
 * 多文件提交控件（源码 / 报告共用）。
 *
 * 交互对齐 PTA / 学习通：一次选 1~9 份，逐文件进度与失败原因，
 * 可删除、可重试、可拖拽排序，全部成功后才能点「提交」。
 *
 * 为什么一文件一请求：浏览器只有一个上传进度事件，批量 multipart 拿不到
 * 「每个文件传到哪了」，失败时也无法只重试坏的那一个。
 *
 * 状态机（唯一事实来源 = item.status，不再用 progress/error 反推）：
 *   PENDING ──选中即入队──▶ UPLOADING ──成功──▶ SUCCESS
 *      ▲                        │
 *      │                        ├──失败──▶ FAILED ──重试──┐
 *      │                        └──取消──▶ CANCELED ──重试─┤
 *      └──────────────────────────────────────────────────┘
 *   MISSING：刷新后从快照恢复、但浏览器拿不回 File 本体，需要用户重新选择
 *
 * 持久化（见 persist/uploadStore.js）：
 *   已成功 → localStorage（有 fileId 就够，不必重传）
 *   队列   → sessionStorage（只对当前标签页有意义）
 *   File 对象本身无法持久化，只能记 name/size/lastModified 作为「票据」，
 *   用户重选同名同大小文件时接回原队列位置。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '../Icon.vue'
import { api } from '../../api/endpoints'
import { toast, toastError } from '../../stores/toast'
import { onStorageHealth, storageHealth } from '../../utils/storage'
import { createUploadStore, OWNER_BEAT_MS, snapshotOf } from '../../persist/uploadStore'

const props = defineProps({
  selectionId: { type: String, required: true },
  type: { type: String, required: true },
  /** { maxFiles, maxFileBytes, allowedExtensions } —— 来自 /meta/submission-policy */
  policy: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['submitted'])

const PENDING = 'pending'
const UPLOADING = 'uploading'
const SUCCESS = 'success'
const FAILED = 'failed'
const CANCELED = 'canceled'
const MISSING = 'missing'

/** 同时最多传几个：9 份 100MB 一起发会把上行带宽和浏览器连接数打满。 */
const MAX_CONCURRENT = 3

const items = ref([])
const submitting = ref(false)
const restoring = ref(false)
const dragFrom = ref(-1)
const overIndex = ref(-1)
/** 另一标签页正在操作本提交时置 true：本页只读，不抢队列、不写同一个 key。 */
const readOnly = ref(false)
const peerNote = ref('')
const storageWarn = ref('')
const rePickInput = ref(null)

let seq = 0
/** 上传泵是否在跑。防止 addFiles / retry / submit 三处并发各起一轮。 */
let pumping = false
/** 本标签页身份：多标签页占用判断用，只在本页生命周期内有效。 */
const tabId = `t${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`

const store = createUploadStore({ selectionId: props.selectionId, type: props.type })

const isSource = computed(() => props.type === 'SOURCE')
const label = computed(() => (isSource.value ? '源码' : '报告'))
const accept = computed(() => (props.policy?.allowedExtensions || []).map((e) => `.${e}`).join(','))
const maxFiles = computed(() => props.policy?.maxFiles || 9)
const maxBytes = computed(() => props.policy?.maxFileBytes || 0)
const full = computed(() => items.value.length >= maxFiles.value)
const uploadedCount = computed(() => items.value.filter((i) => i.status === SUCCESS).length)
const uploadingCount = computed(() => items.value.filter((i) => i.status === UPLOADING).length)
const missingCount = computed(() => items.value.filter((i) => i.missing).length)
const canSubmit = computed(
  () =>
    !props.disabled &&
    !readOnly.value &&
    !submitting.value &&
    !restoring.value &&
    items.value.length > 0 &&
    uploadedCount.value === items.value.length,
)
const locked = computed(() => props.disabled || readOnly.value)

function sizeText(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`
}

function extOf(name) {
  const idx = (name || '').lastIndexOf('.')
  return idx >= 0 ? name.slice(idx + 1).toLowerCase() : ''
}

/** 前端即时校验，规则与后端完全同源（都来自 /meta/submission-policy）。 */
function check(file) {
  if (!file || file.size === 0) return '文件为空'
  const allowed = (props.policy?.allowedExtensions || []).map((e) => e.toLowerCase())
  const ext = extOf(file.name)
  if (!allowed.includes(ext)) {
    if (ext === 'doc') return '请另存为 .docx 后上传'
    return `只支持 ${allowed.map((e) => `.${e}`).join(' / ')}`
  }
  if (maxBytes.value && file.size > maxBytes.value) {
    return `超过单文件上限 ${sizeText(maxBytes.value)}`
  }
  return ''
}

function makeItem(file) {
  return {
    key: `f${++seq}`,
    file,
    name: file.name,
    size: file.size,
    lastModified: file.lastModified || 0,
    status: PENDING,
    progress: 0,
    error: '',
    fileId: '',
    controller: null,
    canceled: false,
    restored: false,
    /** true 表示只有票据、没有 File 本体（刷新恢复或用户删了原文件） */
    missing: false,
  }
}

function addFiles(list) {
  if (locked.value) return
  const incoming = Array.from(list || [])
  if (!incoming.length) return
  const accepted = []
  for (const file of incoming) {
    const reason = check(file)
    if (reason) {
      toast.error(`${file.name} 无法上传`, reason)
      continue
    }
    // 优先接回「上次选了但没传完」的占位项：name + size 就是票据
    const placeholder = items.value.find(
      (i) => i.missing && i.name === file.name && i.size === file.size,
    )
    if (placeholder) {
      placeholder.file = file
      placeholder.lastModified = file.lastModified || 0
      placeholder.missing = false
      placeholder.status = PENDING
      placeholder.progress = 0
      placeholder.error = ''
      toast.info('已接回队列', `${file.name} 与上次选择的文件匹配，继续上传`)
      continue
    }
    if (items.value.length + accepted.length >= maxFiles.value) {
      toast.error('已达数量上限', `${label.value}最多 ${maxFiles.value} 份，超出部分已忽略`)
      break
    }
    if (items.value.some((i) => i.name === file.name) || accepted.some((i) => i.name === file.name)) {
      // 允许同名：不静默覆盖，而是并列展示，由排序决定主次
      toast.info('存在同名文件', `${file.name} 会并列展示，请确认是否需要保留两份`)
    }
    accepted.push(makeItem(file))
  }
  if (!accepted.length) {
    // 只接回了占位项：同样要重新跑泵
    if (items.value.some((i) => i.status === PENDING && i.file)) pump()
    return
  }
  items.value.push(...accepted)
  // 选中即入队：之前这里只是入列表、把上传留给 submit，
  // 而 submit 又被「全部成功」的门槛挡住，两边互锁导致永远停在待上传。
  pump()
}

function onPick(event) {
  addFiles(event.target.files)
  event.target.value = ''
}

function onDropFiles(event) {
  event.preventDefault()
  addFiles(event.dataTransfer?.files)
}

/** 「重新选择」：复用同一个多选 input，靠 name+size 匹配接回占位项。 */
function openRePick() {
  if (locked.value) return
  rePickInput.value?.click()
}

async function uploadItem(item) {
  if (!item || item.status === SUCCESS || item.status === UPLOADING) return
  if (!item.file) {
    // 没有 File 本体（刷新恢复的占位项）：不能发请求，转为待重选
    item.missing = true
    item.status = MISSING
    return
  }
  item.status = UPLOADING
  item.progress = 0
  item.error = ''
  item.canceled = false
  const controller = new AbortController()
  item.controller = controller
  let lastPaint = 0
  try {
    const vo = await api.uploadSubmissionFile(props.selectionId, props.type, item.file, {
      onProgress: (p) => {
        // 100ms 节流：9 个文件同时推进时，逐 tick 渲染会把主线程刷爆
        const now = Date.now()
        if (p < 100 && now - lastPaint < 100) return
        lastPaint = now
        item.progress = p
      },
      signal: controller.signal,
    })
    if (item.canceled) {
      item.status = CANCELED
      return
    }
    item.fileId = vo?.fileId || ''
    if (!item.fileId) throw new Error('服务端未返回 fileId')
    item.progress = 100
    item.status = SUCCESS
  } catch (err) {
    // 用户取消（code=-2）不是失败，不弹红色原因
    if (item.canceled || err?.code === -2) {
      item.status = CANCELED
      item.error = ''
    } else {
      item.status = FAILED
      item.error = err?.message || '上传失败'
    }
  } finally {
    if (item.controller === controller) item.controller = null
  }
}

/**
 * 上传泵：分批并发，每批结束再取下一批。
 *
 * 每轮重新扫描 PENDING 而不是用静态队列快照——这样「取消后重试」「失败后重试」
 * 的文件也能被同一轮泵捡起来，不会出现队列消费完就没人管的情况。
 */
async function pump() {
  if (pumping || locked.value) return
  pumping = true
  try {
    for (;;) {
      const batch = items.value.filter((i) => i.status === PENDING && i.file).slice(0, MAX_CONCURRENT)
      if (!batch.length) break
      await Promise.all(batch.map((item) => uploadItem(item)))
    }
  } finally {
    pumping = false
  }
}

function cancel(item) {
  if (locked.value) return
  item.canceled = true
  item.controller?.abort()
  // 立刻置位，不依赖 abort 回调的时序，避免中间有一帧还显示「上传中」
  item.status = CANCELED
  item.progress = 0
  item.error = ''
}

function retry(item) {
  if (!item || item.status === UPLOADING || locked.value) return
  if (!item.file) {
    // 恢复出来的占位项没有 File，只能让用户重新选
    item.missing = true
    item.status = MISSING
    openRePick()
    return
  }
  item.status = PENDING
  item.error = ''
  pump()
}

function retryAllFailed() {
  if (locked.value) return
  const failed = items.value.filter((i) => i.status === FAILED || i.status === CANCELED)
  const restorable = failed.filter((i) => i.file)
  failed
    .filter((i) => !i.file)
    .forEach((i) => {
      i.missing = true
      i.status = MISSING
    })
  restorable.forEach((i) => {
    i.status = PENDING
    i.error = ''
  })
  if (restorable.length) pump()
  if (failed.length > restorable.length) openRePick()
}

function removeItem(target) {
  if (locked.value) return
  target.canceled = true
  target.controller?.abort()
  items.value = items.value.filter((i) => i.key !== target.key)
}

function move(index, delta) {
  const target = index + delta
  if (target < 0 || target >= items.value.length) return
  const list = [...items.value]
  ;[list[index], list[target]] = [list[target], list[index]]
  items.value = list
}

function onDragStart(index) {
  dragFrom.value = index
}

function onDragOver(index) {
  overIndex.value = index
}

function onDropAt(index) {
  const from = dragFrom.value
  dragFrom.value = -1
  overIndex.value = -1
  if (from < 0 || from === index) return
  const list = [...items.value]
  const [moved] = list.splice(from, 1)
  list.splice(index, 0, moved)
  items.value = list
}

async function submit() {
  if (submitting.value || locked.value) return
  if (items.value.some((i) => i.status === PENDING)) await pump()
  if (!items.value.length) return
  if (items.value.some((i) => i.status !== SUCCESS || !i.fileId)) {
    toast.error('还有文件没传成功', '请重试或移除失败文件后再提交')
    return
  }
  submitting.value = true
  try {
    const result = await api.bindSubmissionFiles(props.selectionId, {
      type: props.type,
      fileIds: items.value.map((i) => i.fileId),
      remark: '',
    })
    toast.ok('提交成功', `已生成 v${result?.version ?? '-'}，共 ${items.value.length} 个文件`)
    items.value = []
    // 提交成功即清理：本地不再留任何与本提交相关的快照
    await store.clear()
    emit('submitted', result)
  } catch (err) {
    toastError(err, '提交失败')
  } finally {
    submitting.value = false
  }
}

// ---------------------------------------------------------------------------
// 持久化
// ---------------------------------------------------------------------------

let persistTimer = null

/** 200ms 节流写：进度事件每 100ms 一次，逐条落盘会把主线程拖慢。 */
function schedulePersist() {
  if (persistTimer) return
  persistTimer = setTimeout(() => {
    persistTimer = null
    persist()
  }, 200)
}

async function persist() {
  if (restoring.value) return
  const snap = snapshotOf(items.value)
  await store.saveDone(snap.done)
  await store.saveQueue(snap.queue)
}

watch(
  items,
  () => {
    schedulePersist()
    syncOwnership()
  },
  { deep: true },
)

// ---------------------------------------------------------------------------
// 多标签页占用（后来者只读）
// ---------------------------------------------------------------------------

let beatTimer = null
let ownerTimer = null
const disposers = []

function heartbeat() {
  if (readOnly.value || !items.value.length) return
  store.claim(tabId)
}

function startBeat() {
  if (beatTimer || readOnly.value) return
  heartbeat()
  beatTimer = setInterval(heartbeat, OWNER_BEAT_MS)
}

function stopBeat() {
  if (beatTimer) {
    clearInterval(beatTimer)
    beatTimer = null
  }
  // 只有持有者自己才会真的删掉占用记录
  store.release(tabId)
}

/** 有文件（不管成功还是待传）就占住本提交，清空即释放。 */
function syncOwnership() {
  if (!items.value.length) stopBeat()
  else if (!items.value.every((i) => i.status === SUCCESS)) startBeat()
}

async function checkOwner() {
  const owner = await store.readOwner()
  const mine = Boolean(owner && owner.tabId === tabId)
  const busy = items.value.some((i) => i.status === UPLOADING)
  if (owner && !mine && !busy) {
    if (!readOnly.value) {
      readOnly.value = true
      peerNote.value = '另一标签页正在操作此提交，本页已切换为只读。关闭该标签页后本页会自动恢复。'
      stopBeat()
    }
    return
  }
  if (!owner && readOnly.value) {
    readOnly.value = false
    peerNote.value = ''
    toast.ok('已可操作', '另一个标签页已释放该提交，本页可以继续上传')
    if (!items.value.length) restore()
  }
}

// ---------------------------------------------------------------------------
// 恢复
// ---------------------------------------------------------------------------

function chunk(list, size) {
  const out = []
  for (let i = 0; i < list.length; i += size) out.push(list.slice(i, i + size))
  return out
}

/**
 * 首次挂载时恢复：已成功的回查是否还在，未完成的转「需重新选择」。
 * 校验请求按 3 并发发，9 份也不会瞬间打出一串请求。
 */
async function restore() {
  if (restoring.value) return
  restoring.value = true
  try {
    const [done, queue] = await Promise.all([store.loadDone(), store.loadQueue()])
    if (!done.length && !queue.length) return

    const alive = []
    const dropped = []
    for (const group of chunk(done, 3)) {
      const checked = await Promise.all(
        group.map(async (item) => {
          try {
            const info = await api.fileInfo(item.fileId)
            return info && info.exists ? item : null
          } catch {
            // 403 / 网络失败都按「不可用」处理：宁可让用户重传，也不要留幽灵项
            return null
          }
        }),
      )
      checked.forEach((item, index) => {
        if (item) alive.push(item)
        else dropped.push(group[index].name)
      })
    }

    const restoredItems = []
    alive.forEach((item) => {
      restoredItems.push({
        key: `r${++seq}`,
        file: null,
        name: item.name,
        size: item.size,
        lastModified: item.lastModified,
        status: SUCCESS,
        progress: 100,
        error: '',
        fileId: item.fileId,
        controller: null,
        canceled: false,
        restored: true,
        missing: false,
      })
    })
    queue.forEach((item) => {
      const interrupted = item.status === 'interrupted'
      restoredItems.push({
        key: `r${++seq}`,
        file: null,
        name: item.name,
        size: item.size,
        lastModified: item.lastModified,
        status: MISSING,
        progress: 0,
        error: interrupted
          ? '上次上传被中断，请重新选择该文件继续'
          : item.error || '上次未完成上传，请重新选择该文件',
        fileId: '',
        controller: null,
        canceled: false,
        restored: true,
        missing: true,
      })
    })

    // 已有的在途会话不能被打断（正常不会走到这里，防御性判断）
    if (!items.value.length) items.value = restoredItems

    if (dropped.length) {
      // 校验失败的记录立刻从「已上传」里剔除，避免下次刷新又捞起来
      await store.saveDone(alive)
      toast.error(
        `${dropped.length} 个文件已失效`,
        `服务端已不存在：${dropped.slice(0, 3).join('、')}${dropped.length > 3 ? ' 等' : ''}`,
      )
    }
    if (alive.length || queue.length) {
      const parts = []
      if (alive.length) parts.push(`已恢复 ${alive.length} 个已上传文件`)
      if (queue.length) parts.push(`${queue.length} 个待重新选择`)
      const text = parts.join('，')
      if (queue.length) toast.info('已恢复上次的上传', text)
      else toast.ok('已恢复上次的上传', text)
    }
    syncOwnership()
  } finally {
    restoring.value = false
  }
}

onMounted(async () => {
  storageWarn.value = storageHealth().memoryOnly
    ? '当前浏览器无法保存本地数据（隐私模式或存储已满），刷新后上传进度会丢失'
    : ''
  disposers.push(
    onStorageHealth((state) => {
      storageWarn.value = state.memoryOnly
        ? '当前浏览器无法保存本地数据（隐私模式或存储已满），刷新后上传进度会丢失'
        : ''
    }),
  )

  await checkOwner()
  if (!readOnly.value) await restore()

  disposers.push(store.watchOwner(() => checkOwner()))
  disposers.push(
    store.watchQueue(() => {
      // 另一标签页改了队列：本页只读时提示一下，避免用户以为卡住了
      if (readOnly.value) peerNote.value = '另一标签页已更新上传列表，本页保持只读。'
    }),
  )
  ownerTimer = setInterval(checkOwner, OWNER_BEAT_MS)
})

onBeforeUnmount(() => {
  // 离开页面时把在途请求断掉，否则 XHR 会挂到超时，后端也可能写进半个文件
  items.value.forEach((i) => i.controller?.abort())
  if (persistTimer) {
    clearTimeout(persistTimer)
    persistTimer = null
  }
  // 卸载前落一次盘：路由跳走时 watch 的 200ms 节流可能还没触发
  persist()
  stopBeat()
  if (ownerTimer) clearInterval(ownerTimer)
  disposers.forEach((off) => {
    try {
      off()
    } catch {
      /* 订阅取消失败不影响卸载 */
    }
  })
})

defineExpose({ submit })
</script>

<template>
  <div>
    <div class="row" style="justify-content: space-between; align-items: baseline">
      <div class="section-title">
        <Icon :name="isSource ? 'code' : 'file'" :size="14" />{{ isSource ? '源码包' : '报告' }}
        <span class="small dim" style="font-weight: 400">
          （{{ (policy?.allowedExtensions || []).map((e) => `.${e}`).join(' / ') }} ≤
          {{ sizeText(maxBytes) }}，1~{{ maxFiles }} 份）
        </span>
      </div>
      <span class="small dim num">{{ items.length }} / {{ maxFiles }}</span>
    </div>

    <div v-if="readOnly" class="small" style="margin-top: 8px; color: var(--warn-text, #92400e)">
      <Icon name="alert" :size="13" /> {{ peerNote }}
    </div>
    <div v-else-if="storageWarn" class="small dim" style="margin-top: 8px">
      <Icon name="alert" :size="13" /> {{ storageWarn }}（不影响本次上传）
    </div>

    <label
      class="dropzone"
      style="min-height: 118px"
      :class="{ 'is-disabled': full || locked }"
      @dragover.prevent
      @drop="onDropFiles"
    >
      <input
        type="file"
        :accept="accept"
        multiple
        hidden
        :disabled="full || locked"
        @change="onPick"
      />
      <Icon name="upload" :size="22" />
      <strong style="font-size: 13px">
        {{ readOnly ? '另一标签页正在操作' : full ? `已达 ${maxFiles} 份上限` : `点击或拖拽添加${label}（可多选）` }}
      </strong>
    </label>

    <!-- 「重新选择」按钮用：与上面的多选共用一套 addFiles 匹配逻辑。
         必须放在 <label> 外面，否则 label 的点击转发会让文件框弹两次。 -->
    <input ref="rePickInput" type="file" :accept="accept" multiple hidden @change="onPick" />

    <div v-if="items.length" style="margin-top: 10px; display: grid; gap: 6px">
      <div
        v-for="(item, index) in items"
        :key="item.key"
        class="card"
        style="padding: 8px 10px"
        :style="{ borderColor: overIndex === index ? 'var(--brand, #3b82f6)' : undefined }"
        draggable="true"
        @dragstart="onDragStart(index)"
        @dragover.prevent="onDragOver(index)"
        @drop.prevent="onDropAt(index)"
      >
        <div class="row" style="gap: 8px; min-width: 0">
          <span class="num small dim" style="width: 16px; text-align: right">{{ index + 1 }}</span>
          <div style="min-width: 0; flex: 1">
            <div class="list-row__title" style="font-size: 12.5px">{{ item.name }}</div>
            <div class="small dim num">{{ sizeText(item.size) }}</div>
          </div>

          <span v-if="item.status === 'success'" class="pill pill--ok small">已完成</span>
          <span v-else-if="item.status === 'failed'" class="pill pill--warn small">失败</span>
          <span v-else-if="item.status === 'canceled'" class="pill small">已取消</span>
          <span v-else-if="item.status === 'missing'" class="pill pill--warn small">需重新选择</span>
          <span v-else-if="item.status === 'uploading'" class="small dim num">{{ item.progress }}%</span>
          <span v-else class="small dim">待上传</span>

          <div class="row" style="gap: 2px">
            <button class="btn btn--icon btn--sm" title="上移" :disabled="index === 0" @click="move(index, -1)">
              <!-- 图标集里没有 chevron-up，用 chevron-down 旋转，避免多引一套 SVG -->
              <Icon name="chevron-down" :size="13" style="transform: rotate(180deg)" />
            </button>
            <button
              class="btn btn--icon btn--sm"
              title="下移"
              :disabled="index === items.length - 1"
              @click="move(index, 1)"
            >
              <Icon name="chevron-down" :size="13" />
            </button>
            <button
              v-if="item.status === 'uploading'"
              class="btn btn--icon btn--sm"
              title="取消上传"
              @click="cancel(item)"
            >
              <Icon name="x" :size="13" />
            </button>
            <button
              v-else-if="item.missing"
              class="btn btn--ghost btn--sm"
              title="重新选择同名文件"
              :disabled="locked"
              @click="openRePick"
            >
              重新选择
            </button>
            <button
              v-else-if="['failed', 'canceled'].includes(item.status)"
              class="btn btn--icon btn--sm"
              title="重试"
              @click="retry(item)"
            >
              <Icon name="refresh" :size="13" />
            </button>
            <button class="btn btn--icon btn--sm" title="移除" @click="removeItem(item)">
              <Icon name="trash" :size="13" />
            </button>
          </div>
        </div>

        <div v-if="item.status === 'uploading'" class="bar" style="margin-top: 6px">
          <div class="bar__fill" :style="{ width: item.progress + '%' }" />
        </div>
        <div v-if="item.error" class="small" style="color: var(--danger, #dc2626); margin-top: 4px">
          {{ item.error }}
        </div>
        <div v-else-if="item.missing" class="small dim" style="margin-top: 4px">
          上次选择过 {{ item.name }}（{{ sizeText(item.size) }}），浏览器无法恢复文件本体，请重新选择同名文件接回队列
        </div>
      </div>
    </div>

    <div class="row" style="margin-top: 10px; gap: 8px">
      <button class="btn btn--primary btn--sm" :disabled="!canSubmit" @click="submit">
        {{ submitting ? '提交中…' : `提交${label}` }}
      </button>
      <button
        v-if="items.some((i) => ['failed', 'canceled'].includes(i.status))"
        class="btn btn--ghost btn--sm"
        :disabled="locked"
        @click="retryAllFailed"
      >
        全部重试
      </button>
      <span class="small dim">
        <template v-if="restoring">正在恢复上次的上传…</template>
        <template v-else-if="readOnly">{{ peerNote }}</template>
        <template v-else-if="missingCount">有 {{ missingCount }} 个文件需要重新选择，其余已就绪</template>
        <template v-else-if="!items.length">选择文件后会自动上传，全部完成后才能提交</template>
        <template v-else-if="uploadingCount">正在上传 {{ uploadingCount }} 个…</template>
        <template v-else>已上传 {{ uploadedCount }} / {{ items.length }}</template>
      </span>
    </div>
  </div>
</template>
