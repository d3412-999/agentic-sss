<template>
  <div class="stat card">
    <div class="stat-top">
      <span class="stat-label">{{ label }}</span>
      <span class="stat-icon" :class="tone">
        <AppIcon :name="icon" :size="18" />
      </span>
    </div>
    <div class="stat-value">
      {{ value }}<span v-if="suffix" class="stat-suffix">{{ suffix }}</span>
    </div>
    <div v-if="hint" class="stat-hint">{{ hint }}</div>
  </div>
</template>

<script setup>
import AppIcon from './AppIcon.vue'

defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], required: true },
  suffix: { type: String, default: '' },
  hint: { type: String, default: '' },
  icon: { type: String, default: 'chart' },
  tone: { type: String, default: 'blue' }
})
</script>

<style scoped>
.stat { padding: 18px 20px; }
.stat-top { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.stat-label { font-size: 14px; color: var(--c-text-2); }
.stat-icon {
  width: 34px; height: 34px; border-radius: 10px;
  display: grid; place-items: center;
  background: var(--c-blue-50); color: var(--c-blue-700);
}
.stat-icon.green { background: var(--c-green-50); color: var(--c-green-700); }
.stat-icon.amber { background: var(--c-amber-50); color: var(--c-amber-700); }
.stat-icon.violet { background: var(--c-violet-50); color: var(--c-violet); }
.stat-value {
  margin-top: 12px;
  font-size: 31px;
  line-height: 1.1;
  font-weight: 500;
  letter-spacing: -.8px;
  font-variant-numeric: tabular-nums;
}
.stat-suffix { font-size: 15px; font-weight: 400; color: var(--c-text-3); margin-left: 4px; letter-spacing: 0; }
.stat-hint { margin-top: 6px; font-size: 13px; color: var(--c-text-3); }
</style>
