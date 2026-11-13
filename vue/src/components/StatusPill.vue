<script setup>
import { computed } from 'vue'

const props = defineProps({
  status: { type: String, default: '' },
  kind: { type: String, default: '' }, // offering | topic | selection | submission | grade | task | student | defense
  dot: { type: Boolean, default: true },
})

const LABELS = {
  // 开课 / 题目
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  CLOSED: '已关闭',
  ARCHIVED: '已归档',
  // 选题
  SELECTED: '已选题',
  SUBMITTED: '已提交',
  DEFENDING: '答辩中',
  DEFENDED: '已答辩',
  GRADED: '已评分',
  CANCELED: '已退选',
  // 提交物
  UPLOADED: '已上传',
  PARSING: '解析中',
  VALID: '校验通过',
  INVALID: '校验失败',
  ANALYZING: 'AI 评审中',
  ANALYZED: '已评审',
  DELETED: '已删除',
  // 成绩
  AUTO: '自动计算',
  ADJUSTED: '已调整',
  LOCKED: '已发布锁定',
  // 任务
  PENDING: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  // 账号
  ACTIVE: '在用',
  DISABLED: '已停用',
  INACTIVE: '已停用',
  // 答辩
  CREATED: '待开始',
  GENERATING: '出题中',
  READY: '待作答',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  EVALUATED: '已评分',
  EXPIRED: '已过期',
  VIOLATED: '违规终止',
}

const TONES = {
  ok: ['PUBLISHED', 'VALID', 'ANALYZED', 'SUCCEEDED', 'LOCKED', 'GRADED', 'DEFENDED', 'ACTIVE', 'EVALUATED', 'COMPLETED'],
  brand: ['SELECTED', 'READY', 'AUTO'],
  info: ['SUBMITTED', 'DEFENDING', 'IN_PROGRESS', 'PARSING', 'ANALYZING', 'RUNNING', 'GENERATING'],
  warn: ['DRAFT', 'UPLOADED', 'ADJUSTED', 'PENDING', 'CLOSED', 'CREATED'],
  danger: ['INVALID', 'FAILED', 'CANCELED', 'DISABLED', 'INACTIVE', 'EXPIRED', 'DELETED', 'VIOLATED'],
  muted: ['ARCHIVED'],
}

const tone = computed(() => {
  const s = (props.status || '').toUpperCase()
  for (const [key, list] of Object.entries(TONES)) {
    if (list.includes(s)) return key === 'muted' ? '' : key
  }
  return ''
})

const label = computed(() => LABELS[(props.status || '').toUpperCase()] || props.status || '—')
</script>

<template>
  <span class="pill" :class="tone ? `pill--${tone}` : ''">
    <span v-if="dot" class="pill__dot" />
    {{ label }}
  </span>
</template>
