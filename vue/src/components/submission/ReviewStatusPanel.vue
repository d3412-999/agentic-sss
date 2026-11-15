<script setup>
/**
 * AI 评审状态面板：源码与报告各自独立展示，互不阻塞。
 *
 * <p>「卡住」的两个典型形态在这里被明确消解：</p>
 * <ul>
 *   <li>还在跑：显示阶段、已用时，30 秒后出现「仍在评审中」+ 手动刷新，而不是无限骨架屏；</li>
 *   <li>跑挂了：显示失败原因 + 重新触发，不会把用户留在加载态。</li>
 * </ul>
 */
import { computed, onMounted } from 'vue'
import Icon from '../Icon.vue'
import { useReviewStatus } from '../../composables/useReviewStatus'
import { elapsedText, shouldShowRefreshHint } from '../../utils/reviewPoll'
import { toast, toastError } from '../../stores/toast'

const props = defineProps({
  submissionId: { type: String, required: true },
  /** 紧凑模式：只显示一行状态（列表页用） */
  compact: { type: Boolean, default: false },
})

const emit = defineEmits(['finished'])

const {
  status,
  loading,
  error,
  elapsed,
  overall,
  sourceStage,
  reportStage,
  retryables,
  start,
  refresh,
  retry,
} = useReviewStatus(
  () => props.submissionId,
  { onFinished: (data) => emit('finished', data) },
)

const SEVERITY_META = {
  CRITICAL: { label: '严重', tone: 'danger' },
  HIGH: { label: '高', tone: 'danger' },
  MEDIUM: { label: '中', tone: 'warn' },
  LOW: { label: '低', tone: 'info' },
  INFO: { label: '提示', tone: '' },
}
const VERDICT_META = {
  BLOCK: { label: '阻断 · 需修复', tone: 'danger' },
  WARN: { label: '有风险', tone: 'warn' },
  PASS: { label: '通过', tone: 'ok' },
}
const SOURCE_LABEL = { RULE: '规则', TOOL: '静态工具', LLM: '语义补充' }

const sourceFindings = computed(() => {
  const raw = status.value?.source?.findings
  return Array.isArray(raw) ? raw : []
})
const sourceVerdict = computed(() => status.value?.source?.verdict || '')
const ruleSummary = computed(() => {
  const raw = status.value?.source?.ruleSummary
  return raw && typeof raw === 'object' ? raw : null
})
const groupedFindings = computed(() => {
  const groups = new Map()
  for (const item of sourceFindings.value) {
    const file = item?.file || '（未标注文件）'
    if (!groups.has(file)) groups.set(file, [])
    groups.get(file).push(item)
  }
  return [...groups.entries()].map(([file, items]) => ({ file, items }))
})

function severityMeta(value) {
  return SEVERITY_META[String(value || '').toUpperCase()] || { label: value || '问题', tone: '' }
}
function verdictMeta(value) {
  return VERDICT_META[String(value || '').toUpperCase()] || null
}
function sourceLabel(value) {
  return SOURCE_LABEL[String(value || '').toUpperCase()] || value || ''
}

async function doRetry(target) {
  try {
    await retry(target)
    toast.info('已重新提交评审', `${target.label}将重新调用 AI 服务`)
  } catch (err) {
    toastError(err, '重新触发失败')
  }
}

onMounted(start)
</script>

<template>
  <div v-if="compact" class="row row--wrap" style="gap: 8px">
    <span class="pill" :class="overall.tone ? `pill--${overall.tone}` : ''">{{ overall.text }}</span>
    <span v-if="status?.updatedAt" class="small dim">更新于 {{ new Date(status.updatedAt).toLocaleTimeString('zh-CN') }}</span>
    <button class="btn btn--ghost btn--sm" :disabled="loading" @click="refresh">
      <Icon name="refresh" :size="13" />刷新
    </button>
  </div>

  <div v-else class="card">
    <div class="card__head">
      <div>
        <h3><Icon name="chart" :size="15" style="margin-right: 6px; vertical-align: -2px" />AI 评审状态</h3>
        <div class="small dim">
          源码与报告是两条独立的异步任务，任一完成即展示结果，互不阻塞
        </div>
      </div>
      <span class="pill" :class="overall.tone ? `pill--${overall.tone}` : ''" style="margin-left: auto">
        {{ overall.text }}
      </span>
    </div>

    <div class="card__body" style="display: grid; gap: 10px">
      <div v-for="stage in [sourceStage, reportStage]" :key="stage.type || stage.label" class="review-stage">
        <div class="row" style="gap: 8px">
          <Icon :name="stage.type === 'REPORT' ? 'file' : 'code'" :size="14" />
          <strong>{{ stage.type === 'REPORT' ? '报告评审' : '源码评审' }}</strong>
          <span class="pill" :class="stage.tone ? `pill--${stage.tone}` : ''">{{ stage.label }}</span>
          <span class="spacer" />
          <span v-if="stage.status === 'SUCCEEDED'" class="num mono" style="font-weight: 650">
            {{ stage.score ?? '—' }} 分
          </span>
        </div>
        <div class="small dim" style="margin-top: 4px">{{ stage.detail }}</div>
      </div>

      <div v-if="sourceFindings.length" class="findings">
        <div class="row" style="gap: 8px; align-items: center">
          <Icon name="code" :size="14" />
          <strong>源码评审明细</strong>
          <span v-if="verdictMeta(sourceVerdict)" class="pill" :class="`pill--${verdictMeta(sourceVerdict).tone}`">
            {{ verdictMeta(sourceVerdict).label }}
          </span>
          <span class="spacer" />
          <span v-if="ruleSummary" class="small dim">
            共 {{ ruleSummary.total ?? sourceFindings.length }} 条
            <template v-if="ruleSummary.critical"> · 严重 {{ ruleSummary.critical }}</template>
            <template v-if="ruleSummary.high"> · 高 {{ ruleSummary.high }}</template>
            <template v-if="ruleSummary.medium"> · 中 {{ ruleSummary.medium }}</template>
            <template v-if="ruleSummary.low"> · 低 {{ ruleSummary.low }}</template>
          </span>
        </div>
        <div class="small dim" style="margin-top: 2px">
          规则引擎 + 静态工具 + LLM 语义补充，按文件分组；行号可对照源码查看。
        </div>

        <details v-for="(group, index) in groupedFindings" :key="group.file" class="finding-file" :open="index === 0">
          <summary>
            <Icon name="file" :size="13" />
            <span class="mono">{{ group.file }}</span>
            <span class="pill pill--info">{{ group.items.length }} 条</span>
          </summary>
          <ul class="finding-list">
            <li v-for="(item, itemIndex) in group.items" :key="`${group.file}-${itemIndex}`" class="finding">
              <div class="finding__head">
                <span class="pill" :class="`pill--${severityMeta(item.severity).tone}`">
                  {{ severityMeta(item.severity).label }}
                </span>
                <span class="mono finding__loc">L{{ item.line ?? '?' }}</span>
                <strong class="finding__title">{{ item.title || item.message || '代码问题' }}</strong>
                <span v-if="item.source" class="small dim">{{ sourceLabel(item.source) }}</span>
              </div>
              <div v-if="item.message && item.message !== item.title" class="small">{{ item.message }}</div>
              <div v-if="item.suggestion" class="small dim" style="margin-top: 2px">建议：{{ item.suggestion }}</div>
              <code v-if="item.evidence" class="finding__evidence">{{ item.evidence }}</code>
            </li>
          </ul>
        </details>
      </div>

      <div v-if="overall.running" class="alert alert--info" style="align-items: flex-start">
        <Icon name="clock" :size="15" style="flex: none; margin-top: 2px" />
        <div style="min-width: 0; flex: 1">
          <div>
            仍在评审中，已等待 <span class="num mono">{{ elapsedText(elapsed) }}</span>。
            <template v-if="shouldShowRefreshHint(elapsed)">大模型处理较慢时通常 1~3 分钟，无需刷新页面。</template>
          </div>
          <div class="small dim" style="margin-top: 2px">
            页面重新可见时会自动补拉，不需要手动刷新
          </div>
        </div>
        <button class="btn btn--ghost btn--sm" style="flex: none" :disabled="loading" @click="refresh">
          <Icon name="refresh" :size="13" />立即刷新
        </button>
      </div>

      <div v-if="retryables.length" class="alert alert--danger" style="align-items: flex-start">
        <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
        <div style="min-width: 0; flex: 1">
          <div>部分材料评审失败，可单独重新触发（不会影响已完成的那一侧）。</div>
          <div class="small dim" style="margin-top: 2px">{{ error || '失败原因见上方阶段说明' }}</div>
        </div>
        <div class="row" style="gap: 6px; flex: none">
          <button
            v-for="target in retryables"
            :key="target.type"
            class="btn btn--primary btn--sm"
            @click="doRetry(target)"
          >
            <Icon name="refresh" :size="13" />{{ target.label }}
          </button>
        </div>
      </div>

      <div v-else-if="error" class="alert alert--warn">
        <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
        <span>{{ error }}（将自动重试）</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.review-stage {
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: var(--surface-2);
}

.findings {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: var(--surface-2);
}

.finding-file {
  border: 1px solid var(--line);
  border-radius: var(--r);
  padding: 6px 10px;
  background: var(--surface);
}

.finding-file summary {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  list-style: none;
}

.finding-file summary::-webkit-details-marker {
  display: none;
}

.finding-list {
  margin: 8px 0 2px;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 8px;
}

.finding {
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: var(--surface-2);
}

.finding__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.finding__loc {
  font-size: 12px;
  color: var(--text-2);
}

.finding__title {
  font-size: 13px;
}

.finding__evidence {
  display: block;
  margin-top: 6px;
  padding: 6px 8px;
  font-size: 12px;
  line-height: 1.5;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  border-radius: 6px;
  background: var(--surface-3, rgba(0, 0, 0, 0.28));
  color: var(--text-2);
}
</style>
