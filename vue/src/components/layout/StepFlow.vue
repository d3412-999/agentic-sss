<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

/**
 * 页面级步骤条（纯展示）。
 *
 * - 复用全局 `.steps / .step / .step__dot / .step__line` 与 `is-done / is-current`，
 *   不引入任何新色值，深浅色主题自动跟随。
 * - 只根据 `current` 与 `steps` 的先后顺序推导状态；调用方不需要传 state。
 * - `steps[].to` 存在时渲染成链接，不存在时渲染成纯文本，不影响任何业务跳转。
 */
const props = defineProps({
  steps: { type: Array, default: () => [] },
  /** 当前所处步骤的 key；找不到时全部按未完成渲染。 */
  current: { type: String, default: '' },
  /** 紧凑模式：字号更小，用于卡片内或页头下方。 */
  dense: { type: Boolean, default: false },
  label: { type: String, default: '流程进度' },
})

const currentIndex = computed(() => props.steps.findIndex((step) => step.key === props.current))

function stateOf(step, index) {
  if (step.state) return step.state
  if (currentIndex.value < 0) return ''
  if (index < currentIndex.value) return 'is-done'
  if (index === currentIndex.value) return 'is-current'
  return ''
}
</script>

<template>
  <nav class="steps flow" :class="{ 'flow--dense': dense }" :aria-label="label">
    <template v-for="(step, index) in steps" :key="step.key">
      <RouterLink
        v-if="step.to"
        :to="step.to"
        class="step flow__step"
        :class="stateOf(step, index)"
        :aria-current="stateOf(step, index) === 'is-current' ? 'step' : undefined"
      >
        <span class="step__dot">{{ index + 1 }}</span>{{ step.label }}
      </RouterLink>
      <span
        v-else
        class="step"
        :class="stateOf(step, index)"
        :aria-current="stateOf(step, index) === 'is-current' ? 'step' : undefined"
      >
        <span class="step__dot">{{ index + 1 }}</span>{{ step.label }}
      </span>
      <span v-if="index < steps.length - 1" class="step__line" aria-hidden="true" />
    </template>
  </nav>
</template>

<style scoped>
.flow {
  flex-wrap: wrap;
  gap: 6px 0;
  min-width: 0;
}

.flow--dense .step {
  font-size: 11.5px;
  gap: 6px;
}

.flow--dense .step__dot {
  width: 17px;
  height: 17px;
  font-size: 9.5px;
}

.flow__step {
  color: var(--text-3);
  transition: color 0.16s var(--ease);
}

.flow__step:hover {
  color: var(--text-2);
}

/* 已完成 / 当前态沿用全局配色，这里只补回链接的默认色覆盖 */
.flow__step.is-done,
.flow__step.is-current {
  color: inherit;
}

.flow__step.is-done {
  color: var(--text-2);
}

.flow__step.is-current {
  color: var(--text);
}
</style>
