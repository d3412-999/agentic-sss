<template>
  <span class="chip" :class="tone">
    <span v-if="dot" class="dot" />
    {{ text }}
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  status: { type: String, default: '' },
  label: { type: String, default: '' },
  dot: { type: Boolean, default: true }
})

const map = {
  DRAFT: ['草稿', ''],
  PUBLISHED: ['已发布', 'blue'],
  CLOSED: ['已关闭', ''],
  ARCHIVED: ['已归档', ''],
  SELECTED: ['已选题', 'blue'],
  SUBMITTED: ['已提交', 'violet'],
  DEFENDING: ['答辩中', 'amber'],
  DEFENDED: ['已答辩', 'green'],
  GRADED: ['已出分', 'green'],
  CANCELED: ['已退选', ''],
  CREATED: ['待生成题目', ''],
  GENERATING: ['题目生成中', 'amber'],
  READY: ['待开始', 'blue'],
  IN_PROGRESS: ['进行中', 'amber'],
  COMPLETED: ['已完成', 'green'],
  EVALUATED: ['已评分', 'green'],
  EXPIRED: ['已过期', 'red'],
  AUTO: ['系统评分', 'blue'],
  ADJUSTED: ['教师调整', 'violet'],
  LOCKED: ['已发布', 'green'],
  ANALYZED: ['已分析', 'green'],
  ANALYZING: ['分析中', 'amber'],
  VALID: ['已校验', 'green'],
  INVALID: ['校验失败', 'red'],
  PARSING: ['解析中', 'amber'],
  PENDING: ['待开始', ''],
  HIGH: ['高', 'red'],
  MEDIUM: ['中', 'amber'],
  LOW: ['低', ''],
  EASY: ['简单', 'green'],
  HARD: ['较难', 'red']
}

const pair = computed(() => map[props.status] || [props.status || '—', ''])
const text = computed(() => props.label || pair.value[0])
const tone = computed(() => pair.value[1])
</script>
