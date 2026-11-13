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
import { onMounted } from 'vue'
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
</style>
