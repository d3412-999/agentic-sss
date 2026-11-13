<script setup>
/**
 * 二级目录：多级页面（课程详情）左侧的纵向功能列表。
 *
 * - 纯展示组件，不感知路由；选中态由 `modelValue` 受控，切换通过 `update:modelValue` 抛出。
 * - 支持分组标题（如「教学准备 / 教学执行 / 课程沉淀」），窄屏下标题隐藏、条目横向滚动。
 * - 样式在 styles/main.css 的 `.subnav` 段，复用现有 token。
 */
import Icon from '../Icon.vue'

defineProps({
  groups: { type: Array, default: () => [] },
  modelValue: { type: String, default: '' },
})

defineEmits(['update:modelValue'])
</script>

<template>
  <nav class="subnav" aria-label="页面分区">
    <template v-for="group in groups" :key="group.label || 'default'">
      <div v-if="group.label" class="subnav__group">{{ group.label }}</div>
      <button
        v-for="item in group.items"
        :key="item.key"
        type="button"
        class="subnav__item"
        :class="{ 'is-active': modelValue === item.key }"
        :aria-current="modelValue === item.key ? 'page' : undefined"
        @click="$emit('update:modelValue', item.key)"
      >
        <Icon v-if="item.icon" :name="item.icon" :size="15" />
        <span>{{ item.label }}</span>
        <span v-if="item.count != null" class="subnav__item-count">{{ item.count }}</span>
      </button>
    </template>
  </nav>
</template>
