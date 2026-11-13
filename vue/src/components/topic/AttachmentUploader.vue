<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import Icon from '../Icon.vue'
import { api } from '../../api/endpoints'
import { toast, toastError } from '../../stores/toast'

/**
 * 题目附件上传器。
 *
 * 设计取舍：一个文件一次请求。后端 /files/batch 本身能接收多个文件，但浏览器只有一个
 * 上传进度事件（xhr.upload.onprogress），一次请求传多个文件就只能得到「总进度」。
 * 要做到「每个文件独立进度 + 独立重试 + 独立取消」，只能拆成一文件一请求，
 * 每请求仍走同一个批量端点（files 数组里放一个），契约不变。
 */
const props = defineProps({
  /** 编辑已有题目时传入；为空表示新建（附件只暂存在前端，随创建请求一起提交） */
  topicId: { type: String, default: '' },
  /** 服务端已有的附件（编辑模式初值） */
  existing: { type: Array, default: () => [] },
  /** 后端下发的上传策略；取不到时用与后端默认值一致的兜底 */
  policy: {
    type: Object,
    default: () => ({
      allowedExtensions: ['pdf', 'doc', 'docx'],
      maxFileBytes: 20 * 1024 * 1024,
      maxFilesPerRequest: 5,
      maxTotalBytes: 50 * 1024 * 1024,
      maxPerTopic: 20,
    }),
  },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['update:fileIds', 'changed'])

const items = ref([])
const dragOver = ref(false)
const fileInput = ref(null)
const previewOpen = ref(false)
const previewUrl = ref('')
const previewName = ref('')
const controllers = new Map()
const seeded = ref(false)

const allowed = computed(() => (props.policy.allowedExtensions || []).map((e) => String(e).toLowerCase()))
const accept = computed(() => allowed.value.map((e) => '.' + e).join(','))
const uploading = computed(() => items.value.some((i) => i.status === 'uploading'))
const canAddMore = computed(() => items.value.length < (props.policy.maxPerTopic || 20))

const uid = () => 'a-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7)

function humanSize(bytes) {
  const n = Number(bytes) || 0
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(n >= 10 * 1024 * 1024 ? 0 : 1) + ' MB'
  if (n >= 1024) return Math.round(n / 1024) + ' KB'
  return n + ' B'
}

function extOf(name) {
  const dot = String(name || '').lastIndexOf('.')
  return dot < 0 ? '' : String(name).slice(dot + 1).toLowerCase()
}

function isPdf(item) {
  return item.contentType === 'application/pdf' || extOf(item.name) === 'pdf'
}

/* ---------------- 初始化与同步 ---------------- */

watch(
  () => props.existing,
  (list) => {
    // 只吃第一份初值：编辑弹窗里上传过程中父组件刷新列表，若跟着重置会打断进行中的上传
    if (seeded.value) return
    seeded.value = true
    items.value = (list || []).map((a) => ({
      uid: 's-' + a.fileId,
      server: true,
      fileId: a.fileId,
      name: a.filename,
      size: a.size,
      contentType: a.contentType,
      status: 'success',
      progress: 100,
      error: '',
      file: null,
    }))
  },
  { immediate: true, deep: true },
)

let lastIds = ''
function syncFileIds() {
  if (props.topicId) return
  const ids = items.value.filter((i) => i.status === 'success' && i.fileId).map((i) => i.fileId)
  const key = ids.join(',')
  if (key === lastIds) return
  lastIds = key
  emit('update:fileIds', ids)
}

/* ---------------- 选择 / 拖拽 ---------------- */

function pick() {
  if (props.disabled) return
  fileInput.value?.click()
}

function onPicked(e) {
  addFiles(e.target.files)
  e.target.value = ''
}

function onDrop(e) {
  dragOver.value = false
  if (props.disabled) return
  addFiles(e.dataTransfer?.files)
}

function addFiles(fileList) {
  const files = Array.from(fileList || [])
  if (!files.length) return
  const limit = props.policy.maxPerTopic || 20
  const room = limit - items.value.length
  if (room <= 0) {
    toast.info('附件数量已满', `单个题目最多 ${limit} 个附件`)
    return
  }
  if (files.length > room) {
    toast.info('部分文件未加入', `最多再添加 ${room} 个，多余的 ${files.length - room} 个已忽略`)
  }
  let total = items.value.reduce((sum, i) => sum + (Number(i.size) || 0), 0)

  files.slice(0, room).forEach((file) => {
    const name = file.name || 'attachment'
    const ext = extOf(name)
    // 必须是 reactive：push 进数组后再用原始对象改 progress，Vue 不会收到通知，进度条就卡住不动
    const item = reactive({
      uid: uid(),
      server: false,
      fileId: '',
      name,
      size: file.size,
      contentType: file.type || '',
      status: 'pending',
      progress: 0,
      error: '',
      file,
    })
    // 客户端先拦一道：格式、单文件大小、累计总量，省掉一次必然失败的请求
    if (!allowed.value.includes(ext)) {
      item.status = 'error'
      item.error = `格式不支持，仅允许 ${allowed.value.map((e) => e.toUpperCase()).join(' / ')}`
    } else if (file.size <= 0) {
      item.status = 'error'
      item.error = '文件为空'
    } else if (file.size > props.policy.maxFileBytes) {
      item.status = 'error'
      item.error = `超过单文件上限 ${humanSize(props.policy.maxFileBytes)}`
    } else if (total + file.size > props.policy.maxTotalBytes) {
      item.status = 'error'
      item.error = `超出本次总量上限 ${humanSize(props.policy.maxTotalBytes)}`
    }
    total += file.size
    items.value.push(item)
    if (item.status === 'pending') startUpload(item)
  })
  syncFileIds()
}

/* ---------------- 上传 / 重试 / 取消 ---------------- */

async function startUpload(item) {
  if (!item.file) return
  const controller = new AbortController()
  controllers.set(item.uid, controller)
  item.status = 'uploading'
  item.progress = 0
  item.error = ''
  try {
    const data = await api.uploadTopicFile(item.file, {
      signal: controller.signal,
      onProgress: (p) => {
        item.progress = p
      },
    })
    const ok = data?.succeeded?.[0]
    if (!ok) {
      item.status = 'error'
      item.error = data?.failed?.[0]?.reason || '上传失败'
      return
    }
    item.fileId = ok.fileId
    item.name = ok.filename || item.name
    item.size = ok.size ?? item.size
    item.contentType = ok.contentType || item.contentType
    item.progress = 100
    item.status = 'success'
    if (props.topicId) await attachToTopic(item)
  } catch (err) {
    if (err?.code === -2) {
      item.status = 'canceled'
      item.error = '已取消'
    } else {
      item.status = 'error'
      item.error = err?.message || '上传失败'
    }
  } finally {
    controllers.delete(item.uid)
    syncFileIds()
  }
}

async function attachToTopic(item) {
  try {
    const list = await api.addTopicAttachments(props.topicId, [item.fileId])
    emit('changed', list)
  } catch (err) {
    // 文件已上传成功但挂载失败：保留 fileId，便于用户重试挂载而不是重传
    item.status = 'error'
    item.error = err?.message || '挂载附件失败'
    toastError(err, '挂载附件失败')
  }
}

function retry(item) {
  if (item.fileId && !item.server && props.topicId) {
    attachToTopic(item).then(() => {
      if (item.status !== 'error') item.status = 'success'
    })
    return
  }
  if (!item.fileId) {
    startUpload(item)
    return
  }
  // 服务端附件失败（极少见）：直接重新上传原文件
  if (item.file) startUpload(item)
}

function cancel(item) {
  controllers.get(item.uid)?.abort()
}

function cancelAll() {
  controllers.forEach((c) => c.abort())
}

async function remove(item) {
  if (item.status === 'uploading') {
    cancel(item)
    return
  }
  if (props.topicId && item.server && item.fileId) {
    try {
      const list = await api.removeTopicAttachment(props.topicId, item.fileId)
      emit('changed', list)
    } catch (err) {
      toastError(err, '删除附件失败')
      return
    }
  }
  items.value = items.value.filter((i) => i.uid !== item.uid)
  syncFileIds()
}

async function move(item, dir) {
  const idx = items.value.indexOf(item)
  const to = idx + dir
  if (idx < 0 || to < 0 || to >= items.value.length) return
  const next = items.value.slice()
  next.splice(idx, 1)
  next.splice(to, 0, item)
  items.value = next
  syncFileIds()
  if (!props.topicId) return
  const ids = items.value.filter((i) => i.fileId).map((i) => i.fileId)
  if (ids.length !== items.value.length) return
  try {
    const list = await api.reorderTopicAttachments(props.topicId, ids)
    emit('changed', list)
  } catch (err) {
    toastError(err, '调整顺序失败')
  }
}

/* ---------------- 预览 / 下载 ---------------- */

async function preview(item) {
  if (!item.fileId) return
  if (!isPdf(item)) {
    download(item)
    return
  }
  try {
    previewUrl.value = await api.topicAttachmentBlobUrl(item.fileId)
    previewName.value = item.name
    previewOpen.value = true
  } catch (err) {
    toastError(err, '预览失败')
  }
}

function closePreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewOpen.value = false
}

function download(item) {
  if (!item.fileId) return
  api.downloadFile(item.fileId, item.name).catch((err) => toastError(err, '下载失败'))
}

onBeforeUnmount(() => {
  cancelAll()
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})

defineExpose({ uploading, cancelAll })
</script>

<template>
  <div>
    <div
      class="upload-zone"
      :class="{ 'is-over': dragOver, 'is-disabled': disabled || !canAddMore }"
      @click="pick"
      @dragover.prevent="dragOver = true"
      @dragleave.prevent="dragOver = false"
      @drop.prevent="onDrop"
    >
      <Icon name="upload" :size="18" />
      <div>
        <div style="font-weight: 600; font-size: 13px">
          {{ canAddMore ? '拖拽文件到这里，或点击选择' : '附件数量已达上限' }}
        </div>
        <div class="small dim">
          支持 {{ allowed.map((e) => e.toUpperCase()).join(' / ') }}，单个不超过
          {{ humanSize(policy.maxFileBytes) }}，最多 {{ policy.maxPerTopic }} 个
        </div>
      </div>
      <span class="spacer" />
      <button v-if="uploading" class="btn btn--ghost btn--sm" type="button" @click.stop="cancelAll">
        <Icon name="x" :size="13" />取消全部
      </button>
      <input
        ref="fileInput"
        type="file"
        multiple
        :accept="accept"
        style="display: none"
        @change="onPicked"
      />
    </div>

    <div v-if="items.length" style="display: grid; gap: 8px; margin-top: 10px">
      <div v-for="(item, index) in items" :key="item.uid" class="att-row">
        <span class="att-row__icon" :class="`is-${item.status}`">
          <Icon :name="item.status === 'error' ? 'alert' : 'file'" :size="14" />
        </span>

        <div style="min-width: 0; flex: 1">
          <div class="row" style="gap: 6px">
            <span class="truncate small" style="font-weight: 550" :title="item.name">{{ item.name }}</span>
            <span class="tag">{{ (extOf(item.name) || 'file').toUpperCase() }}</span>
            <span class="small dim">{{ humanSize(item.size) }}</span>
          </div>

          <div v-if="item.status === 'uploading'" class="bar" style="margin-top: 6px; height: 4px">
            <div class="bar__fill" :style="{ width: Math.max(4, item.progress) + '%' }" />
          </div>
          <div v-else-if="item.status === 'error'" class="small" style="color: var(--danger); margin-top: 3px">
            {{ item.error }}
          </div>
          <div v-else-if="item.status === 'canceled'" class="small dim" style="margin-top: 3px">已取消上传</div>
        </div>

        <span v-if="item.status === 'success'" class="pill pill--ok">已上传</span>
        <span v-else-if="item.status === 'uploading'" class="small dim num">{{ item.progress }}%</span>
        <span v-else-if="item.status === 'error'" class="pill pill--danger">失败</span>

        <div class="row" style="gap: 2px; flex: none">
          <button
            v-if="item.status === 'success'"
            class="btn btn--icon btn--sm"
            type="button"
            :title="isPdf(item) ? '预览' : '下载'"
            @click="preview(item)"
          >
            <Icon :name="isPdf(item) ? 'eye' : 'download'" :size="13" />
          </button>
          <button
            v-if="item.status === 'success' && isPdf(item)"
            class="btn btn--icon btn--sm"
            type="button"
            title="下载"
            @click="download(item)"
          >
            <Icon name="download" :size="13" />
          </button>
          <button
            v-if="item.status === 'error' || item.status === 'canceled'"
            class="btn btn--icon btn--sm"
            type="button"
            title="重试"
            @click="retry(item)"
          >
            <Icon name="refresh" :size="13" />
          </button>
          <button
            v-if="item.status === 'uploading'"
            class="btn btn--icon btn--sm"
            type="button"
            title="取消"
            @click="cancel(item)"
          >
            <Icon name="x" :size="13" />
          </button>
          <button
            class="btn btn--icon btn--sm"
            type="button"
            :disabled="index === 0"
            title="上移"
            @click="move(item, -1)"
          >
            ↑
          </button>
          <button
            class="btn btn--icon btn--sm"
            type="button"
            :disabled="index === items.length - 1"
            title="下移"
            @click="move(item, 1)"
          >
            ↓
          </button>
          <button class="btn btn--icon btn--sm" type="button" title="删除" @click="remove(item)">
            <Icon name="trash" :size="13" />
          </button>
        </div>
      </div>
    </div>

    <div v-if="previewOpen" class="preview-mask" @click.self="closePreview">
      <div class="preview-box">
        <div class="row" style="padding: 10px 14px; border-bottom: 1px solid var(--line)">
          <Icon name="file" :size="14" />
          <span class="truncate small" style="font-weight: 600">{{ previewName }}</span>
          <span class="spacer" />
          <button class="btn btn--ghost btn--sm" type="button" @click="closePreview">关闭</button>
        </div>
        <iframe :src="previewUrl" title="附件预览" style="width: 100%; height: 68vh; border: 0" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.upload-zone {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px dashed var(--line);
  border-radius: 12px;
  background: var(--surface-2, transparent);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.upload-zone.is-over {
  border-color: var(--brand);
  background: var(--brand-ring);
}
.upload-zone.is-disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.att-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
}
.att-row__icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--surface-2, rgba(0, 0, 0, 0.04));
  flex: none;
}
.att-row__icon.is-error {
  color: var(--danger);
}
.preview-mask {
  position: fixed;
  inset: 0;
  z-index: 90;
  background: rgba(16, 24, 40, 0.55);
  display: grid;
  place-items: center;
  padding: 24px;
}
.preview-box {
  width: min(920px, 100%);
  background: var(--surface);
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 24px 64px rgba(16, 24, 40, 0.28);
}
</style>
