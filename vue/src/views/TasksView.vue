<script setup>
import { computed, onMounted } from 'vue'
import Icon from '../components/Icon.vue'
import PageHeader from '../components/layout/PageHeader.vue'
import StatusPill from '../components/StatusPill.vue'
import EmptyState from '../components/EmptyState.vue'
import { activeTasks, cancelTask, clearFinished, refresh, resumeTracking, tasks } from '../stores/tasks'
import { toast } from '../stores/toast'

const items = computed(() => tasks.items)

const KIND_LABEL = {
  RECALC: '重算成绩',
  EXPORT: '成绩导出',
  PARSE: '材料解析',
  REANALYZE: '重新评审',
  TASK: '后台任务',
}

function fmt(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

async function doCancel(task) {
  await cancelTask(task.taskId)
  toast.info('已请求取消', '后端只把任务标记为 CANCELED，已在执行的线程会在下一个检查点退出')
}

async function doRefresh(task) {
  await refresh(task.taskId)
}

function resultText(task) {
  if (!task.result) return ''
  try {
    return JSON.stringify(task.result, null, 2)
  } catch {
    return String(task.result)
  }
}

onMounted(resumeTracking)
</script>

<template>
  <div>
    <PageHeader
      title="任务中心"
      description="重算成绩、导出 CSV、解析材料、AI 重新评审都是异步任务。本页记录你在本机发起的任务并自动轮询状态（记录保存在浏览器本地）。"
    >
      <template #actions>
        <span class="pill" :class="activeTasks.length ? 'pill--info' : 'pill--ok'">
          {{ activeTasks.length ? `${activeTasks.length} 个进行中` : '当前无进行中任务' }}
        </span>
        <button class="btn btn--ghost btn--sm" @click="clearFinished">清理已完成</button>
      </template>
    </PageHeader>

    <EmptyState
      v-if="!items.length"
      icon="clock"
      title="还没有任务"
      hint="在成绩页重算或导出后，任务会自动出现在这里"
    />

    <div v-else class="card">
      <div v-for="t in items" :key="t.taskId" class="list-row" style="align-items: flex-start">
        <div style="min-width: 0; flex: 1">
          <div class="row" style="gap: 8px">
            <span class="tag">{{ KIND_LABEL[t.kind] || '任务' }}</span>
            <span class="list-row__title truncate">{{ t.label }}</span>
            <StatusPill :status="t.status" />
          </div>
          <div class="list-row__meta">
            {{ fmt(t.createdAt) }} · <span class="mono">{{ t.taskId?.slice(0, 8) }}…</span>
            <span v-if="t.error" style="color: var(--danger)"> · {{ t.error }}</span>
          </div>
          <div v-if="['PENDING', 'RUNNING'].includes(t.status)" style="margin-top: 8px; max-width: 420px">
            <div class="bar"><div class="bar__fill" :style="{ width: (t.progress || 0) + '%' }" /></div>
            <div class="small dim num" style="margin-top: 4px">{{ t.progress || 0 }}%</div>
          </div>
          <details v-if="t.result" style="margin-top: 8px">
            <summary class="small" style="cursor: pointer; color: var(--brand)">查看结果</summary>
            <pre class="mono small" style="
              margin: 8px 0 0;
              padding: 10px 12px;
              border-radius: 10px;
              background: var(--surface-3);
              max-height: 220px;
              overflow: auto;
              white-space: pre-wrap;
            ">{{ resultText(t) }}</pre>
          </details>
        </div>
        <div class="row" style="gap: 2px">
          <button class="btn btn--icon btn--sm" title="刷新" @click="doRefresh(t)"><Icon name="refresh" :size="14" /></button>
          <button
            v-if="['PENDING', 'RUNNING'].includes(t.status)"
            class="btn btn--icon btn--sm"
            title="取消"
            @click="doCancel(t)"
          >
            <Icon name="x" :size="14" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
