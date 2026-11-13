<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import Icon from '../Icon.vue'
import Modal from '../Modal.vue'
import { api } from '../../api/endpoints'
import { confirm } from '../../stores/confirm'
import { errorText, toast, toastError } from '../../stores/toast'
import { useDraft } from '../../composables/useDraft'

/**
 * 添加学生：文本粘贴 / 电子表格导入两条主路径 + 一个次要的单个添加。
 *
 * 解析与校验全部在后端做（同一套分隔符与表头规则），这里只负责：
 * 1）输入；2）展示逐行预览并允许过滤异常；3）确认导入后展示逐行结果。
 * 弹窗内任何一步都不展示、也不生成密码——学生账号的初始密码是学号，首次登录强制修改。
 */
const props = defineProps({
  offeringId: { type: String, required: true },
})
const emit = defineEmits(['close', 'imported'])

/** 兜底值与后端 application.yaml 的 sss.student.* 保持一致，取到策略后会被覆盖。 */
const DEFAULT_POLICY = { allowedExtensions: ['xlsx', 'xls', 'docx', 'csv'], maxFileBytes: 2 * 1024 * 1024, maxRows: 500 }

const step = ref('input') // input | preview | result
const mode = ref('text') // text | file
const single = ref(false)
const policy = ref({ ...DEFAULT_POLICY })

const text = ref('')
const parsing = ref(false)
const parseError = ref('')

const fileInput = ref(null)
const dragOver = ref(false)
const pickedFile = ref(null)
const uploadProgress = ref(0)

const preview = ref(null)
const onlyInvalid = ref(false)
const confirming = ref(false)
const result = ref(null)

const singleForm = reactive({ studentNo: '', name: '', email: '' })
const singleBusy = ref(false)
const singleError = ref('')

const accept = computed(() => policy.value.allowedExtensions.map((e) => '.' + e).join(','))
const extText = computed(() => policy.value.allowedExtensions.map((e) => e.toUpperCase()).join(' / '))
const maxRows = computed(() => policy.value.maxRows)
const maxSizeText = computed(() => humanSize(policy.value.maxFileBytes))
const exampleText = '2023010104 赵六\n2023010105 钱七'

/**
 * 导入流程（input → preview → result）的草稿。
 *
 * 这条链路最容易丢：选了 Excel、解析出 200 行预览、正在勾「只看异常」，
 * 结果点到遮罩就全没了，回到第一步重选文件、重等解析。
 * 存 sessionStorage 语义的 `steps` 模块 + localStorage 介质（要跨浏览器重启），
 * 恢复时直接把 step 跳回 preview。
 *
 * 注意：解析结果可能已过期（这期间名单变了），所以恢复时明确提示「建议重新解析」，
 * 并且结果页（result）不恢复——那是一次性回执，重放没有意义。
 */
const draftArmed = ref(false)
const importDraft = useDraft({
  module: 'steps',
  context: () => `student-import-${props.offeringId}`,
  source: () =>
    draftArmed.value
      ? {
          step: step.value,
          mode: mode.value,
          single: single.value,
          text: text.value,
          preview: preview.value,
          onlyInvalid: onlyInvalid.value,
          singleForm: { ...singleForm },
        }
      : null,
  apply: (data) => {
    if (data.mode === 'text' || data.mode === 'file') mode.value = data.mode
    if (typeof data.text === 'string') text.value = data.text
    single.value = Boolean(data.single)
    onlyInvalid.value = Boolean(data.onlyInvalid)
    if (data.singleForm && typeof data.singleForm === 'object') {
      singleForm.studentNo = data.singleForm.studentNo || ''
      singleForm.name = data.singleForm.name || ''
      singleForm.email = data.singleForm.email || ''
    }
    if (data.preview && Array.isArray(data.preview.rows) && data.preview.rows.length) {
      preview.value = data.preview
      // result 是一次性回执，不能重放；恢复时统一回到预览
      step.value = 'preview'
    } else if (data.step === 'input') {
      step.value = 'input'
    }
  },
  isEmpty: (data) =>
    !String(data?.text || '').trim() &&
    !(data?.preview && data.preview.rows && data.preview.rows.length) &&
    !String(data?.singleForm?.studentNo || '').trim() &&
    !String(data?.singleForm?.name || '').trim(),
})

const previewRows = computed(() => {
  const rows = preview.value?.rows || []
  return onlyInvalid.value ? rows.filter((r) => r.state === 'INVALID') : rows
})
const submittable = computed(() => (preview.value?.rows || []).filter((r) => r.state === 'NEW' || r.state === 'LINK'))
const invalidRows = computed(() => (preview.value?.rows || []).filter((r) => r.state === 'INVALID'))

const STATE_LABEL = { NEW: '新建账号', LINK: '加入本班', IN_ROSTER: '已在本班', INVALID: '异常' }
const STATE_CLASS = { NEW: 'pill--ok', LINK: 'pill--info', IN_ROSTER: '', INVALID: 'pill--danger' }

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

onMounted(async () => {
  try {
    const data = await api.studentImportPolicy()
    if (data) policy.value = { ...DEFAULT_POLICY, ...data }
  } catch {
    // 取不到就用兜底值，不阻塞添加流程
  }

  // 草稿恢复放在策略加载之后：恢复的行要按最新的 maxRows 等规则展示
  importDraft.resume()
  const draft = await importDraft.peek()
  if (draft) {
    const ok = await confirm({
      title: '恢复上次的导入进度？',
      message: draft.preview?.rows?.length
        ? `检测到上次已解析但未确认的 ${draft.preview.rows.length} 行名单，是否继续确认？`
        : '检测到上次输入的名单，是否继续？',
      confirmText: '恢复',
      cancelText: '重新开始',
    })
    if (ok) {
      await importDraft.restore()
      toast.info('已恢复上次的导入进度', '名单可能已变化，建议重新解析后再确认导入')
    } else {
      await importDraft.clear()
      importDraft.resume()
    }
  }
  draftArmed.value = true
})

/* ---------------- 文本粘贴 ---------------- */

async function copyExample() {
  try {
    await navigator.clipboard.writeText(exampleText)
    toast.info('已复制格式示例')
  } catch {
    toast.error('复制失败', '请手动选中示例复制')
  }
}

async function parseText() {
  parseError.value = ''
  if (!text.value.trim()) {
    parseError.value = '请先粘贴学生数据，每行一位学生'
    return
  }
  parsing.value = true
  try {
    preview.value = await api.parseStudentText(props.offeringId, text.value)
    onlyInvalid.value = false
    step.value = 'preview'
  } catch (err) {
    // 带上 traceId：后端 500 时用户把这行贴回来就能直接定位日志
    parseError.value = errorText(err, '解析失败')
  } finally {
    parsing.value = false
  }
}

/* ---------------- 文件导入 ---------------- */

function pickFile() {
  if (parsing.value) return
  fileInput.value?.click()
}

function onPicked(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (file) importFile(file)
}

function onDrop(e) {
  dragOver.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) importFile(file)
}

async function importFile(file) {
  parseError.value = ''
  const ext = extOf(file.name)
  if (!policy.value.allowedExtensions.includes(ext)) {
    parseError.value = ext === 'doc'
      ? '旧版 .doc 不支持解析，请另存为 .docx 或 .xlsx 后重新上传'
      : `不支持的格式（.${ext || '未知'}），请上传 ${extText.value}`
    return
  }
  if (file.size > policy.value.maxFileBytes) {
    parseError.value = `文件 ${humanSize(file.size)}，超过 ${maxSizeText.value} 上限`
    return
  }
  pickedFile.value = file
  parsing.value = true
  uploadProgress.value = 0
  try {
    preview.value = await api.parseStudentFile(props.offeringId, file, {
      onProgress: (p) => (uploadProgress.value = p),
    })
    onlyInvalid.value = false
    step.value = 'preview'
  } catch (err) {
    parseError.value = errorText(err, '解析失败，请确认文件格式与表头')
  } finally {
    parsing.value = false
  }
}

function downloadTemplate() {
  api.downloadStudentImportTemplate(props.offeringId).catch((err) => toastError(err, '下载样例失败'))
}

/* ---------------- 预览 / 提交 ---------------- */

function back() {
  step.value = 'input'
  preview.value = null
  onlyInvalid.value = false
}

function downloadErrors() {
  const rows = [['行号', '学号', '姓名', '原因'], ...invalidRows.value.map((r) => [r.rowNo, r.studentNo, r.name, r.message])]
  const csv = '\uFEFF' + rows
    .map((cells) => cells.map((v) => `"${String(v ?? '').replace(/"/g, '""')}"`).join(','))
    .join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = '学生导入错误清单.csv'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 4000)
}

async function confirmImport() {
  if (confirming.value || !submittable.value.length) return
  confirming.value = true
  try {
    const rows = submittable.value.map((r) => ({
      studentNo: r.studentNo,
      name: r.name,
      email: r.email || undefined,
      rowNo: r.rowNo,
    }))
    result.value = await api.confirmStudentImport(props.offeringId, rows)
    step.value = 'result'
    // 导入已落库，本地草稿没有保留价值
    await importDraft.clear()
    emit('imported', result.value)
  } catch (err) {
    toastError(err, '导入失败')
  } finally {
    confirming.value = false
  }
}

function finish() {
  const summary = result.value
  if (summary && (summary.created || summary.linked) ) {
    toast.ok('已加入教学班', `新建 ${summary.created} 人 · 加入 ${summary.linked} 人`)
  }
  emit('close')
}

/* ---------------- 单个添加（次要入口） ---------------- */

async function submitSingle() {
  singleError.value = ''
  if (!singleForm.studentNo.trim() || !singleForm.name.trim()) {
    singleError.value = '学号与姓名必填'
    return
  }
  singleBusy.value = true
  try {
    const created = await api.addStudent(props.offeringId, {
      studentNo: singleForm.studentNo.trim(),
      name: singleForm.name.trim(),
      email: singleForm.email.trim() || undefined,
    })
    toast.ok('已加入教学班', created?.name || singleForm.name.trim())
    emit('imported', { created: 1, linked: 0, skipped: 0, failed: 0 })
    emit('close')
  } catch (err) {
    singleError.value = errorText(err, '加入失败')
  } finally {
    singleBusy.value = false
  }
}
</script>

<template>
  <Modal :title="single ? '单个添加学生' : '添加学生'" size="xl" @close="emit('close')">
    <!-- 步骤 1：输入 -->
    <div v-if="step === 'input' && !single">
      <div class="tabs" style="margin-bottom: 16px">
        <button class="tabs__item" :class="{ 'is-active': mode === 'text' }" @click="mode = 'text'">文本输入</button>
        <button class="tabs__item" :class="{ 'is-active': mode === 'file' }" @click="mode = 'file'">电子表格</button>
      </div>

      <div v-if="mode === 'text'" style="display: grid; gap: 12px">
        <div class="format-box">
          <div class="row" style="gap: 8px">
            <span class="small" style="font-weight: 600">数据格式</span>
            <span class="tag">每行一位学生</span>
            <span class="spacer" />
            <button class="btn btn--ghost btn--sm" @click="copyExample"><Icon name="file" :size="13" />复制格式示例</button>
          </div>
          <pre class="format-box__code">学号 姓名
2023010104 赵六
2023010105 钱七</pre>
          <p class="small dim" style="margin: 8px 0 0">
            支持从 Excel / Word 直接粘贴：换行、制表符、全角空格会自动识别；
            也可用英文逗号、中文逗号或短横线分隔（如 <span class="mono">2023010104,赵六</span>）。
          </p>
        </div>

        <div class="field">
          <label>粘贴学生名单</label>
          <textarea
            v-model="text"
            class="input"
            rows="10"
            style="resize: vertical; font-family: var(--mono, monospace); line-height: 1.7"
            placeholder="2023010104 赵六&#10;2023010105 钱七"
          />
          <p class="field__hint">严格按数据格式每行一条；空行与首尾空白会自动忽略。单次最多 {{ maxRows }} 条。</p>
        </div>

        <div v-if="parseError" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ parseError }}
        </div>
      </div>

      <div v-else style="display: grid; gap: 12px">
        <div class="row row--wrap" style="gap: 8px">
          <span class="small" style="font-weight: 600">样例文件</span>
          <button class="btn btn--ghost btn--sm" @click="downloadTemplate">
            <Icon name="download" :size="13" />下载样例（xlsx）
          </button>
          <span class="small dim">表头固定为「学号、姓名」，可选「邮箱」</span>
        </div>

        <div
          class="dropzone"
          :class="{ 'is-over': dragOver, 'is-busy': parsing }"
          @click="pickFile"
          @dragover.prevent="dragOver = true"
          @dragleave.prevent="dragOver = false"
          @drop.prevent="onDrop"
        >
          <Icon name="upload" :size="22" />
          <div style="font-weight: 600; font-size: 13px">
            {{ parsing ? '正在解析…' : '点击选择文件，或将文件拖拽至此' }}
          </div>
          <div class="small dim">
            支持 {{ extText }}，单文件不超过 {{ maxSizeText }}，单次不超过 {{ maxRows }} 条
          </div>
          <input ref="fileInput" type="file" :accept="accept" style="display: none" @change="onPicked" />
        </div>

        <div v-if="pickedFile" class="file-row">
          <Icon name="file" :size="14" />
          <span class="truncate small" style="font-weight: 550">{{ pickedFile.name }}</span>
          <span class="small dim">{{ humanSize(pickedFile.size) }}</span>
          <span class="spacer" />
          <div v-if="parsing" class="bar" style="width: 120px; height: 4px">
            <div class="bar__fill" :style="{ width: Math.max(4, uploadProgress) + '%' }" />
          </div>
        </div>

        <div class="alert alert--info">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
          下载并修改样例文件后再上传，表头须与样例一致；旧版 .doc 请另存为 .docx 或 .xlsx。
        </div>

        <div v-if="parseError" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ parseError }}
        </div>
      </div>
    </div>

    <!-- 步骤 1'：单个添加 -->
    <div v-else-if="single" style="display: grid; gap: 13px">
      <div class="field">
        <label>学号</label>
        <input v-model="singleForm.studentNo" class="input" placeholder="2023010104" />
      </div>
      <div class="field">
        <label>姓名</label>
        <input v-model="singleForm.name" class="input" placeholder="赵六" />
      </div>
      <div class="field">
        <label>邮箱（选填）</label>
        <input v-model="singleForm.email" class="input" placeholder="student@sss.local" />
      </div>
      <p class="small dim" style="margin: 0">
        学生登录账号与初始密码都是学号，首次登录需修改；教师端不展示、也不需要记录密码。
      </p>
      <div v-if="singleError" class="alert alert--danger">
        <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ singleError }}
      </div>
    </div>

    <!-- 步骤 2：预览确认 -->
    <div v-else-if="step === 'preview'" style="display: grid; gap: 12px">
      <div class="row row--wrap" style="gap: 12px">
        <span class="small">共 <b>{{ preview?.total ?? 0 }}</b> 条</span>
        <span class="small" style="color: var(--ok, #12805c)">可导入 <b>{{ preview?.importable ?? 0 }}</b></span>
        <span class="small dim">已在本班 {{ preview?.skipped ?? 0 }}</span>
        <span class="small" :style="invalidRows.length ? 'color: var(--danger)' : ''">异常 {{ preview?.invalid ?? 0 }}</span>
        <span class="spacer" />
        <label class="small row" style="gap: 6px; cursor: pointer">
          <input v-model="onlyInvalid" type="checkbox" />只看异常
        </label>
      </div>

      <div v-if="preview?.headerDetected" class="alert alert--info">
        <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
        已自动识别并跳过首行表头。
      </div>
      <div v-else-if="preview?.hint" class="alert alert--warn">
        <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ preview.hint }}
      </div>

      <div class="preview-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 64px">行号</th>
              <th>学号</th>
              <th>姓名</th>
              <th style="width: 108px">状态</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in previewRows" :key="r.rowNo">
              <td class="mono small dim">{{ r.rowNo }}</td>
              <td class="mono small">{{ r.studentNo || '—' }}</td>
              <td class="small">{{ r.name || '—' }}</td>
              <td><span class="pill" :class="STATE_CLASS[r.state]">{{ STATE_LABEL[r.state] || r.state }}</span></td>
              <td class="small" :class="{ dim: r.state !== 'INVALID' }">{{ r.message }}</td>
            </tr>
            <tr v-if="!previewRows.length">
              <td colspan="5" class="small dim" style="text-align: center; padding: 18px">没有符合筛选条件的行</td>
            </tr>
          </tbody>
        </table>
      </div>

      <p class="small dim" style="margin: 0">
        只提交状态为「新建账号 / 加入本班」的行；已在本班的行会自动跳过，不会重复建号。
      </p>
    </div>

    <!-- 步骤 3：导入结果 -->
    <div v-else style="display: grid; gap: 14px">
      <div class="result-grid">
        <div class="result-cell">
          <div class="result-cell__num">{{ result?.created ?? 0 }}</div>
          <div class="small dim">新建账号</div>
        </div>
        <div class="result-cell">
          <div class="result-cell__num">{{ result?.linked ?? 0 }}</div>
          <div class="small dim">加入本班</div>
        </div>
        <div class="result-cell">
          <div class="result-cell__num">{{ result?.skipped ?? 0 }}</div>
          <div class="small dim">已在本班</div>
        </div>
        <div class="result-cell">
          <div class="result-cell__num" :style="result?.failed ? 'color: var(--danger)' : ''">{{ result?.failed ?? 0 }}</div>
          <div class="small dim">失败</div>
        </div>
      </div>

      <div v-if="result?.rows?.some((r) => r.result === 'FAILED')" class="preview-wrap" style="max-height: 240px">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 64px">行号</th>
              <th>学号</th>
              <th>姓名</th>
              <th>原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in result.rows.filter((x) => x.result === 'FAILED')" :key="r.rowNo">
              <td class="mono small dim">{{ r.rowNo }}</td>
              <td class="mono small">{{ r.studentNo }}</td>
              <td class="small">{{ r.name }}</td>
              <td class="small" style="color: var(--danger)">{{ r.message }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <p class="small dim" style="margin: 0">
        学生账号与初始密码都是学号，首次登录需修改；教师端不展示密码，也无需再逐个记录。
      </p>
    </div>

    <template #foot>
      <template v-if="step === 'input' && !single">
        <button class="btn btn--ghost btn--sm" style="margin-right: auto" @click="single = true">改为单个添加</button>
        <button class="btn btn--ghost" @click="emit('close')">取消</button>
        <button v-if="mode === 'text'" class="btn btn--primary" :disabled="parsing" @click="parseText">
          <span v-if="parsing" class="spinner" />下一步
        </button>
        <button v-else class="btn btn--primary" :disabled="parsing" @click="pickFile">
          <span v-if="parsing" class="spinner" />选择文件
        </button>
      </template>

      <template v-else-if="single">
        <button class="btn btn--ghost btn--sm" style="margin-right: auto" @click="single = false; step = 'input'">
          返回批量添加
        </button>
        <button class="btn btn--ghost" @click="emit('close')">取消</button>
        <button class="btn btn--primary" :disabled="singleBusy" @click="submitSingle">
          <span v-if="singleBusy" class="spinner" />加入教学班
        </button>
      </template>

      <template v-else-if="step === 'preview'">
        <button class="btn btn--ghost btn--sm" style="margin-right: auto" @click="back">返回修改</button>
        <button v-if="invalidRows.length" class="btn btn--ghost btn--sm" @click="downloadErrors">
          <Icon name="download" :size="13" />下载错误清单
        </button>
        <button class="btn btn--ghost" @click="emit('close')">取消</button>
        <button class="btn btn--primary" :disabled="confirming || !submittable.length" @click="confirmImport">
          <span v-if="confirming" class="spinner" />确认导入 {{ submittable.length }} 条
        </button>
      </template>

      <template v-else>
        <button class="btn btn--primary" @click="finish">完成</button>
      </template>
    </template>
  </Modal>
</template>

<style scoped>
.format-box {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px 14px;
  background: var(--surface-2, rgba(0, 0, 0, 0.02));
}
.format-box__code {
  margin: 10px 0 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--surface, #fff);
  border: 1px dashed var(--line);
  font-family: var(--mono, monospace);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-dim, #666);
  white-space: pre-wrap;
}
.dropzone {
  display: grid;
  place-items: center;
  gap: 6px;
  padding: 28px 16px;
  border: 1px dashed var(--line);
  border-radius: 12px;
  cursor: pointer;
  text-align: center;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.dropzone:hover,
.dropzone.is-over {
  border-color: var(--brand);
  background: var(--brand-ring);
}
.dropzone.is-busy {
  cursor: progress;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
}
.preview-wrap {
  max-height: 42vh;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 10px;
}
.preview-wrap .table thead th {
  position: sticky;
  top: 0;
  background: var(--surface);
  z-index: 1;
}
.result-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.result-cell {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px;
  text-align: center;
}
.result-cell__num {
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
</style>
