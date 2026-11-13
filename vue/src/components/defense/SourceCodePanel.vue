<script setup>
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import Icon from '../Icon.vue'
import { api } from '../../api/endpoints'

/**
 * 答辩页「我的源码」面板：只读、不可复制、不可下载。
 *
 * 三条设计底线：
 * 1. 内容只来自 `GET /defense/{id}/source-code` 的纯文本，组件里没有任何下载/导出/复制入口；
 * 2. 高亮器按需懒加载：只有真的打开面板、且文件语言需要时才 import 对应语言包，
 *    答辩首屏不会因为多一个 highlight.js 而变慢；
 * 3. 所有「防复制」都是体验层拦截，真正的边界在服务端——这里拦住只提高作弊成本，
 *    不构成安全承诺。因此被拦下的动作会 emit 给上层，作为弱证据上报，而不是静默吞掉。
 */
const props = defineProps({
  sessionId: { type: String, required: true },
  /** 答辩开始时间（ISO），用于显示「已进行多久」，提醒学生别在源码区停留太久。 */
  startedAt: { type: String, default: '' },
})
const emit = defineEmits(['blocked', 'collapse'])

/** 单文件最多渲染多少行：5MB 的压缩代码可能有十万行，直接进 DOM 会卡死浏览器。 */
const MAX_RENDER_LINES = 4000

const loading = ref(true)
const loadError = ref('')
const data = ref(null)
const keyword = ref('')
const sortBy = ref('path') // path | size
const activePath = ref('')
const collapsed = ref(new Set())

/* ---------------- 加载 ---------------- */

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await api.defenseSourceCode(props.sessionId)
    data.value = result || null
    pickDefaultFile()
  } catch (err) {
    loadError.value = err?.message || '源码加载失败'
  } finally {
    loading.value = false
  }
}

function pickDefaultFile() {
  const files = data.value?.files || []
  if (!files.length) {
    activePath.value = ''
    return
  }
  if (files.some((file) => file.path === activePath.value)) return
  // 优先定位到「最像是主代码」的文件：浅层 .java，其次任意浅层文本
  const java = files
    .filter((file) => file.language === 'java')
    .sort((a, b) => depthOf(a.path) - depthOf(b.path) || a.path.localeCompare(b.path))
  activePath.value = java[0]?.path || files[0].path
}

/* ---------------- 文件树 ---------------- */

const files = computed(() => (Array.isArray(data.value?.files) ? data.value.files : []))

const matchedFiles = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return files.value
  return files.value.filter((file) => file.path.toLowerCase().includes(q))
})

/** 把扁平路径拼成树，再压平成「带缩进的一行行」——比递归组件少一层，展开/折叠更好控制。 */
const rows = computed(() => {
  const filtered = matchedFiles.value
  const root = { children: [] }
  const dirs = new Map()
  const searching = Boolean(keyword.value.trim())

  for (const file of filtered) {
    const segments = file.path.split('/')
    let parent = root
    let prefix = ''
    for (let i = 0; i < segments.length - 1; i++) {
      prefix = prefix ? `${prefix}/${segments[i]}` : segments[i]
      let dir = dirs.get(prefix)
      if (!dir) {
        dir = { type: 'dir', name: segments[i], path: prefix, size: 0, count: 0, children: [] }
        dirs.set(prefix, dir)
        parent.children.push(dir)
      }
      dir.size += Number(file.size) || 0
      dir.count += 1
      parent = dir
    }
    parent.children.push({
      type: 'file',
      name: segments[segments.length - 1],
      path: file.path,
      size: Number(file.size) || 0,
      file,
    })
  }

  const sortNodes = (nodes) => {
    nodes.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'dir' ? -1 : 1
      if (sortBy.value === 'size' && a.type === 'file') return b.size - a.size || a.name.localeCompare(b.name)
      return a.name.localeCompare(b.name, 'zh-CN')
    })
    for (const node of nodes) {
      if (node.children) sortNodes(node.children)
    }
  }
  sortNodes(root.children)

  const out = []
  const walk = (nodes, depth) => {
    for (const node of nodes) {
      out.push({ ...node, depth })
      if (node.type === 'dir' && (searching || !collapsed.value.has(node.path))) {
        walk(node.children, depth + 1)
      }
    }
  }
  walk(root.children, 0)
  return out
})

function depthOf(path) {
  return String(path || '').split('/').length
}

function toggleDir(path) {
  const next = new Set(collapsed.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  collapsed.value = next
}

/* ---------------- 当前文件 ---------------- */

const activeFile = computed(() => files.value.find((file) => file.path === activePath.value) || null)

const activeLines = computed(() => {
  const content = activeFile.value?.content
  if (content == null) return []
  return String(content).split('\n')
})

const linesTruncated = computed(() => activeLines.value.length > MAX_RENDER_LINES)

const renderLines = computed(() => activeLines.value.slice(0, MAX_RENDER_LINES))

const lineCount = computed(() => renderLines.value.length)

/* ---------------- 语法高亮（懒加载） ---------------- */

/** 语言包全部用显式动态 import：Vite 才会各自拆成独立 chunk，不会并进答辩首屏。 */
const LANG_LOADERS = {
  java: () => import('highlight.js/lib/languages/java'),
  xml: () => import('highlight.js/lib/languages/xml'),
  javascript: () => import('highlight.js/lib/languages/javascript'),
  typescript: () => import('highlight.js/lib/languages/typescript'),
  python: () => import('highlight.js/lib/languages/python'),
  c: () => import('highlight.js/lib/languages/c'),
  cpp: () => import('highlight.js/lib/languages/cpp'),
  go: () => import('highlight.js/lib/languages/go'),
  rust: () => import('highlight.js/lib/languages/rust'),
  kotlin: () => import('highlight.js/lib/languages/kotlin'),
  php: () => import('highlight.js/lib/languages/php'),
  ruby: () => import('highlight.js/lib/languages/ruby'),
  csharp: () => import('highlight.js/lib/languages/csharp'),
  sql: () => import('highlight.js/lib/languages/sql'),
  css: () => import('highlight.js/lib/languages/css'),
  scss: () => import('highlight.js/lib/languages/scss'),
  less: () => import('highlight.js/lib/languages/less'),
  json: () => import('highlight.js/lib/languages/json'),
  yaml: () => import('highlight.js/lib/languages/yaml'),
  properties: () => import('highlight.js/lib/languages/properties'),
  ini: () => import('highlight.js/lib/languages/ini'),
  groovy: () => import('highlight.js/lib/languages/groovy'),
  markdown: () => import('highlight.js/lib/languages/markdown'),
}

const hljs = shallowRef(null)
const html = ref('')
const highlightState = ref('') // '' | loading | done | plain
let highlightSeq = 0

function escapeHtml(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

async function renderHighlight() {
  const seq = ++highlightSeq
  const file = activeFile.value
  if (!file) {
    html.value = ''
    highlightState.value = ''
    return
  }
  const source = renderLines.value.join('\n')
  const language = String(file.language || 'text').toLowerCase()
  const loader = LANG_LOADERS[language]
  if (!loader) {
    html.value = escapeHtml(source)
    highlightState.value = 'plain'
    return
  }
  highlightState.value = 'loading'
  try {
    if (!hljs.value) {
      const core = await import('highlight.js/lib/core')
      hljs.value = core.default
    }
    const engine = hljs.value
    if (!engine.getLanguage(language)) {
      const mod = await loader()
      engine.registerLanguage(language, mod.default)
    }
    if (seq !== highlightSeq) return
    html.value = engine.highlight(source, { language, ignoreIllegals: true }).value
    highlightState.value = 'done'
  } catch {
    // 语言包加载失败只降级为纯文本，不影响查看
    if (seq === highlightSeq) {
      html.value = escapeHtml(source)
      highlightState.value = 'plain'
    }
  }
}

watch([activePath, renderLines], renderHighlight, { immediate: true })

/* ---------------- 只读拦截 ---------------- */

const BLOCKED_KEYS = new Set(['c', 's', 'u', 'p', 'a', 'x'])

function onKeydown(event) {
  const key = String(event.key || '').toLowerCase()
  const withCtrl = event.ctrlKey || event.metaKey
  const devtoolsCombo = withCtrl && event.shiftKey && ['i', 'j', 'c', 'k'].includes(key)
  if (event.key === 'F12' || devtoolsCombo || (withCtrl && BLOCKED_KEYS.has(key))) {
    event.preventDefault()
    event.stopPropagation()
    emit('blocked', devtoolsCombo || event.key === 'F12' ? 'SHORTCUT_BLOCK' : 'COPY_BLOCK')
    return false
  }
  return true
}

function onCopyBlocked() {
  emit('blocked', 'COPY_BLOCK')
}

/* ---------------- 已用时提示 ---------------- */

const now = ref(Date.now())
let clockTimer = null

const elapsedText = computed(() => {
  if (!props.startedAt) return ''
  const started = new Date(props.startedAt).getTime()
  if (!Number.isFinite(started)) return ''
  const seconds = Math.max(0, Math.floor((now.value - started) / 1000))
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return `${minutes} 分 ${String(rest).padStart(2, '0')} 秒`
})

function fmtSize(bytes) {
  const value = Number(bytes) || 0
  if (value <= 0) return '0 B'
  if (value < 1024) return `${value} B`
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 ** 2).toFixed(1)} MB`
}

onMounted(() => {
  void load()
  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 15000)
})

onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
  clockTimer = null
})
</script>

<template>
  <article class="card source">
    <div class="card__head">
      <div style="min-width: 0">
        <h3 class="row" style="gap: 8px">
          <Icon name="code" :size="15" />
          <span>我的源码</span>
          <span v-if="data?.versionLabel" class="pill pill--brand">{{ data.versionLabel }}</span>
          <span v-if="data?.archiveCount > 1" class="pill">{{ data.archiveCount }} 个包</span>
        </h3>
        <div class="small dim">
          只读 · 仅本人 · 仅答辩进行中可查看
          <span v-if="elapsedText"> · 已进行 {{ elapsedText }}</span>
        </div>
      </div>
      <div class="row" style="margin-left: auto; gap: 8px; flex-wrap: wrap">
        <input
          v-if="!loading && !loadError && files.length"
          v-model="keyword"
          class="input source__search"
          placeholder="搜索文件路径"
        />
        <button
          v-if="!loading && !loadError && files.length"
          class="btn btn--ghost btn--sm"
          :title="sortBy === 'path' ? '当前按路径排序，点击改为按大小' : '当前按大小排序，点击改为按路径'"
          @click="sortBy = sortBy === 'path' ? 'size' : 'path'"
        >
          <Icon name="filter" :size="14" />{{ sortBy === 'path' ? '按路径' : '按大小' }}
        </button>
        <button class="btn btn--ghost btn--sm" @click="emit('collapse')">
          <Icon name="x" :size="14" />收起
        </button>
      </div>
    </div>

    <div v-if="loading" class="card__body">
      <div class="skeleton" style="height: 260px" />
    </div>

    <div v-else-if="loadError" class="card__body">
      <div class="alert alert--warn" style="align-items: flex-start">
        <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
        <div style="min-width: 0">
          <strong>源码暂不可用</strong>
          <div class="small" style="margin-top: 4px">{{ loadError }}</div>
          <button class="btn btn--ghost btn--sm" style="margin-top: 8px" @click="load">
            <Icon name="refresh" :size="14" />重试
          </button>
        </div>
      </div>
    </div>

    <div v-else-if="!files.length" class="card__body">
      <div class="alert alert--info" style="align-items: flex-start">
        <Icon name="file" :size="15" style="flex: none; margin-top: 2px" />
        <span>这个源码包里没有可在线查看的文本文件（可能只包含二进制或资源文件）。</span>
      </div>
    </div>

    <div v-else class="source__body">
      <aside class="source__tree">
        <div class="source__tree-head">
          <span class="small dim">{{ matchedFiles.length }} / {{ files.length }} 个文件</span>
        </div>
        <ul class="tree">
          <li
            v-for="row in rows"
            :key="row.path"
            class="tree__row"
            :class="{
              'tree__row--dir': row.type === 'dir',
              'is-active': row.type === 'file' && row.path === activePath,
            }"
            :style="{ paddingLeft: `${8 + row.depth * 14}px` }"
          >
            <button
              v-if="row.type === 'dir'"
              class="tree__item tree__item--dir"
              @click="toggleDir(row.path)"
            >
              <Icon :name="collapsed.has(row.path) && !keyword.trim() ? 'chevron-right' : 'chevron-down'" :size="12" />
              <Icon name="folder" :size="13" />
              <span class="truncate">{{ row.name }}</span>
              <span class="tree__meta">{{ row.count }}</span>
            </button>
            <button v-else class="tree__item" :title="row.path" @click="activePath = row.path">
              <Icon name="file" :size="13" />
              <span class="truncate">{{ row.name }}</span>
              <span class="tree__meta">{{ fmtSize(row.size) }}</span>
            </button>
          </li>
        </ul>
      </aside>

      <section class="source__viewer">
        <div class="source__viewer-head">
          <div style="min-width: 0">
            <div class="mono small truncate" :title="activeFile?.path">{{ activeFile?.path }}</div>
            <div class="small dim">
              {{ activeFile?.language || 'text' }} · {{ fmtSize(activeFile?.size) }} ·
              {{ activeLines.length }} 行
              <span v-if="activeFile?.truncated" class="pill pill--warn" style="margin-left: 6px">
                文件过大，仅展示开头
              </span>
            </div>
          </div>
          <span class="spacer" />
          <span class="pill pill--info"><Icon name="lock" :size="12" />只读</span>
        </div>

        <div
          class="source__code"
          tabindex="0"
          @contextmenu.prevent
          @copy.prevent="onCopyBlocked"
          @cut.prevent="onCopyBlocked"
          @dragstart.prevent
          @keydown="onKeydown"
        >
          <div class="code">
            <div class="code__gutter">
              <span v-for="n in lineCount" :key="n">{{ n }}</span>
            </div>
            <pre class="code__pre"><code class="code__text" v-html="html" /></pre>
          </div>
        </div>

        <div v-if="linesTruncated" class="alert alert--warn" style="margin: 0 10px 10px">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
          <span>该文件行数过多，仅展示前 {{ MAX_RENDER_LINES }} 行（完整内容请在本机开发环境查看）。</span>
        </div>
        <div v-if="data?.truncated" class="alert alert--info" style="margin: 0 10px 10px">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
          <span>部分文件超过单文件展示上限，已截断；标记「仅展示开头」的文件不是完整内容。</span>
        </div>
        <div v-if="data?.skippedFiles" class="small dim" style="padding: 0 12px 10px">
          已跳过 {{ data.skippedFiles }} 个非文本文件（图片、class、node_modules 等）。
        </div>
      </section>
    </div>
  </article>
</template>

<style scoped>
.source__search {
  width: 160px;
  height: 30px;
  padding: 0 9px;
  font-size: 12px;
}

.source__body {
  display: grid;
  grid-template-columns: minmax(180px, 250px) minmax(0, 1fr);
  border-top: 1px solid var(--line);
  height: min(56vh, 560px);
  min-height: 300px;
}

.source__tree {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--line);
  background: var(--surface-2);
}

.source__tree-head {
  padding: 8px 10px;
  border-bottom: 1px solid var(--line);
}

.tree {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 6px 0;
  overflow: auto;
  list-style: none;
}

.tree__row {
  padding-right: 8px;
}

.tree__item {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 4px 6px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text-2);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}

.tree__item:hover {
  background: var(--surface-3);
  color: var(--text);
}

.tree__row.is-active .tree__item {
  background: var(--brand-soft);
  color: var(--brand-strong);
  font-weight: 600;
}

.tree__item--dir {
  color: var(--text);
  font-weight: 600;
}

.tree__meta {
  margin-left: auto;
  padding-left: 6px;
  color: var(--text-3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.source__viewer {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.source__viewer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line);
}

.source__code {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: var(--surface-3);
  /* 只读核心：禁用选中 + 禁用拖拽，Ctrl+C 拦截在事件层再做一次 */
  user-select: none;
  -webkit-user-select: none;
  -moz-user-select: none;
  cursor: default;
}

.code {
  display: flex;
  align-items: flex-start;
  min-height: 100%;
  font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 12.5px;
  line-height: 1.55;
}

.code__gutter {
  position: sticky;
  left: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  flex: none;
  padding: 10px 8px 10px 10px;
  border-right: 1px solid var(--line);
  background: var(--surface-2);
  color: var(--text-3);
  text-align: right;
  user-select: none;
  font-variant-numeric: tabular-nums;
}

.code__gutter span {
  display: block;
  height: 1.55em;
}

.code__pre {
  flex: 1;
  min-width: 0;
  margin: 0;
  padding: 10px 14px;
  overflow: visible;
  background: transparent;
  color: var(--text);
  white-space: pre;
  tab-size: 4;
}

.code__text {
  /* 行号与代码行高必须同源，否则一行错位会越滚越夸张 */
  line-height: 1.55;
  font-family: inherit;
}

/* highlight.js 的 token 配色：不引外部主题 css，避免和暖色/深色两套主题打架 */
.code__text :deep(.hljs-comment),
.code__text :deep(.hljs-quote) {
  color: var(--text-3);
  font-style: italic;
}

.code__text :deep(.hljs-keyword),
.code__text :deep(.hljs-selector-tag),
.code__text :deep(.hljs-literal),
.code__text :deep(.hljs-doctag) {
  color: #7c3aed;
}

.code__text :deep(.hljs-string),
.code__text :deep(.hljs-regexp),
.code__text :deep(.hljs-addition) {
  color: #15803d;
}

.code__text :deep(.hljs-number),
.code__text :deep(.hljs-symbol),
.code__text :deep(.hljs-bullet) {
  color: #b45309;
}

.code__text :deep(.hljs-title),
.code__text :deep(.hljs-section),
.code__text :deep(.hljs-name),
.code__text :deep(.hljs-selector-id) {
  color: #1d4ed8;
}

.code__text :deep(.hljs-attr),
.code__text :deep(.hljs-attribute),
.code__text :deep(.hljs-variable),
.code__text :deep(.hljs-template-variable) {
  color: #0e7490;
}

.code__text :deep(.hljs-type),
.code__text :deep(.hljs-built_in),
.code__text :deep(.hljs-class) {
  color: #9d174d;
}

.code__text :deep(.hljs-meta),
.code__text :deep(.hljs-tag) {
  color: #6b7280;
}

:global([data-theme='dark']) .code__text :deep(.hljs-keyword),
:global([data-theme='dark']) .code__text :deep(.hljs-selector-tag),
:global([data-theme='dark']) .code__text :deep(.hljs-literal),
:global([data-theme='dark']) .code__text :deep(.hljs-doctag) {
  color: #c4b5fd;
}

:global([data-theme='dark']) .code__text :deep(.hljs-string),
:global([data-theme='dark']) .code__text :deep(.hljs-regexp),
:global([data-theme='dark']) .code__text :deep(.hljs-addition) {
  color: #86efac;
}

:global([data-theme='dark']) .code__text :deep(.hljs-number),
:global([data-theme='dark']) .code__text :deep(.hljs-symbol),
:global([data-theme='dark']) .code__text :deep(.hljs-bullet) {
  color: #fcd34d;
}

:global([data-theme='dark']) .code__text :deep(.hljs-title),
:global([data-theme='dark']) .code__text :deep(.hljs-section),
:global([data-theme='dark']) .code__text :deep(.hljs-name),
:global([data-theme='dark']) .code__text :deep(.hljs-selector-id) {
  color: #93c5fd;
}

:global([data-theme='dark']) .code__text :deep(.hljs-attr),
:global([data-theme='dark']) .code__text :deep(.hljs-attribute),
:global([data-theme='dark']) .code__text :deep(.hljs-variable),
:global([data-theme='dark']) .code__text :deep(.hljs-template-variable) {
  color: #67e8f9;
}

:global([data-theme='dark']) .code__text :deep(.hljs-type),
:global([data-theme='dark']) .code__text :deep(.hljs-built_in),
:global([data-theme='dark']) .code__text :deep(.hljs-class) {
  color: #f9a8d4;
}

@media (max-width: 900px) {
  .source__body {
    grid-template-columns: minmax(0, 1fr);
    height: min(62vh, 520px);
  }

  .source__tree {
    max-height: 148px;
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
}
</style>
