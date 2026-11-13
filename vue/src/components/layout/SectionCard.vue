<script setup>
import Icon from '../Icon.vue'

/**
 * 统一分区卡片：标题 / 说明 / 右侧操作 / 内容。
 * 仅统一结构，不改变内部业务渲染。
 */
defineProps({
  title: { type: String, default: '' },
  hint: { type: String, default: '' },
  icon: { type: String, default: '' },
  flush: { type: Boolean, default: false },
})
</script>

<template>
  <section class="card">
    <div v-if="title || $slots.actions" class="card__head">
      <Icon v-if="icon" :name="icon" :size="14" class="dim" />
      <h3 v-if="title">{{ title }}</h3>
      <span v-if="hint" class="small dim">{{ hint }}</span>
      <div v-if="$slots.actions" class="spacer row">
        <slot name="actions" />
      </div>
    </div>
    <div :class="flush ? 'card__body card__body--flush' : 'card__body'">
      <slot />
    </div>
  </section>
</template>
