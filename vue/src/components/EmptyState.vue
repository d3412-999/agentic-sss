<script setup>
import Icon from './Icon.vue'

defineProps({
  icon: { type: String, default: 'inbox' },
  title: { type: String, default: '暂无数据' },
  hint: { type: String, default: '' },
})
</script>

<template>
  <div class="empty">
    <div class="empty__icon">
      <Icon :name="icon" :size="20" />
    </div>
    <strong>{{ title }}</strong>
    <span v-if="hint" class="small">{{ hint }}</span>
    <slot />
  </div>
</template>
