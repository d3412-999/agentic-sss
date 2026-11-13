<script setup>
/**
 * 提交文件预览。
 *
 * <p>三种格式三条路：</p>
 * <ul>
 *   <li>PDF：带鉴权拉成 blob，塞进 iframe —— 浏览器自带渲染，不需要任何依赖；</li>
 *   <li>DOCX：<b>懒加载</b> docx-preview，点开预览时才拉 chunk，不进首屏包体；</li>
 *   <li>其它（zip/jar 等二进制）：明确说不支持内嵌预览，保留下载入口。</li>
 * </ul>
 *
 * <p>blob URL 必须在关闭与卸载时 revoke，否则一个 100MB 的 PDF 会在内存里留到刷新页面。</p>
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Icon from '../Icon.vue'
import Modal from '../Modal.vue'
import { api } from '../../api/endpoints'

const props = defineProps({
  submissionId: { type: String, required: true },
  /** { id, filename, contentType, size } —— id 是 versionFileId */
  file: { type: Object, required: true },
})
const emit = defineEmits(['close'])

const loading = ref(true)
const error = ref('')
const blobUrl = ref('')
const docxHost = ref(null)
const mode = ref('none') // pdf | docx | unsupported
let objectUrl = ''

const ext = computed(() => {
  const name = props.file?.filename || ''
  const idx = name.lastIndexOf('.')
  return idx >= 0 ? name.slice(idx + 1).toLowerCase() : ''
})
const title = computed(() => props.file?.filename || '文件预览')

async function load() {
  loading.value = true
  error.value = ''
  mode.value = 'none'
  const kind = ext.value
  if (kind !== 'pdf' && kind !== 'docx') {
    mode.value = 'unsupported'
    loading.value = false
    return
  }
  try {
    objectUrl = await api.submissionFileBlobUrl(props.submissionId, props.file.id)
    blobUrl.value = objectUrl
    if (kind === 'pdf') {
      mode.value = 'pdf'
      loading.value = false
      return
    }
    // DOCX：动态 import，只有真的点开预览才会下载这几十 KB
    mode.value = 'docx'
    await nextTick()
    const response = await fetch(objectUrl)
    const buffer = await response.arrayBuffer()
    const { renderAsync } = await import('docx-preview')
    if (!docxHost.value) return
    docxHost.value.innerHTML = ''
    await renderAsync(buffer, docxHost.value, undefined, {
      className: 'docx',
      inWrapper: true,
      ignoreWidth: false,
      ignoreHeight: false,
      breakPages: true,
    })
    loading.value = false
  } catch (err) {
    error.value = err?.message || '加载文件失败'
    loading.value = false
  }
}

function download() {
  api
    .downloadSubmissionFile(props.submissionId, props.file.id, props.file.filename)
    .catch((err) => (error.value = err?.message || '下载失败'))
}

function cleanup() {
  if (objectUrl) {
    URL.revokeObjectURL(objectUrl)
    objectUrl = ''
    blobUrl.value = ''
  }
}

watch(() => props.file?.id, load, { immediate: true })
onBeforeUnmount(cleanup)
</script>

<template>
  <Modal :title="title" size="xl" @close="cleanup(); emit('close')">
    <div v-if="error" class="alert alert--danger">
      <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
      <div style="min-width: 0">
        <div>{{ error }}</div>
        <button class="btn btn--ghost btn--sm" style="margin-top: 8px" @click="download">
          <Icon name="download" :size="13" />改为下载
        </button>
      </div>
    </div>

    <div v-else-if="mode === 'unsupported'" class="alert alert--info">
      <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
      <div style="min-width: 0">
        <div>{{ ext ? `.${ext}` : '该类型' }} 不支持内嵌预览，请下载后查看。</div>
        <button class="btn btn--primary btn--sm" style="margin-top: 8px" @click="download">
          <Icon name="download" :size="13" />下载文件
        </button>
      </div>
    </div>

    <div v-else-if="loading" class="card__body">
      <div class="skeleton" style="height: 320px" />
      <div class="small dim" style="margin-top: 8px">正在加载预览…</div>
    </div>

    <iframe
      v-else-if="mode === 'pdf'"
      :src="blobUrl"
      title="PDF 预览"
      style="width: 100%; height: 72vh; border: 0; border-radius: 10px; background: var(--surface-2)"
    />

    <div
      v-else-if="mode === 'docx'"
      ref="docxHost"
      style="max-height: 72vh; overflow: auto; padding: 8px; background: var(--surface-2); border-radius: 10px"
    />

    <template #foot>
      <button class="btn btn--ghost btn--sm" @click="download">
        <Icon name="download" :size="13" />下载
      </button>
      <button class="btn btn--primary btn--sm" @click="cleanup(); emit('close')">关闭</button>
    </template>
  </Modal>
</template>

<style scoped>
:deep(.docx-wrapper) {
  background: transparent;
  padding: 0;
}
</style>
