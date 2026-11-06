<template>
  <div class="ring" :style="{ width: size + 'px', height: size + 'px' }">
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`">
      <circle
        :cx="size / 2" :cy="size / 2" :r="radius"
        fill="none" stroke="var(--c-grey-200)" :stroke-width="stroke"
      />
      <circle
        :cx="size / 2" :cy="size / 2" :r="radius"
        fill="none" :stroke="color" :stroke-width="stroke"
        stroke-linecap="round"
        :stroke-dasharray="circumference"
        :stroke-dashoffset="offset"
        :transform="`rotate(-90 ${size / 2} ${size / 2})`"
        class="ring-fill"
      />
    </svg>
    <div class="ring-center">
      <div class="ring-value">{{ display }}</div>
      <div v-if="label" class="ring-label">{{ label }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  value: { type: Number, default: 0 },
  size: { type: Number, default: 104 },
  stroke: { type: Number, default: 8 },
  color: { type: String, default: 'var(--c-blue)' },
  label: { type: String, default: '' },
  display: { type: String, default: '' }
})

const radius = computed(() => (props.size - props.stroke) / 2)
const circumference = computed(() => 2 * Math.PI * radius.value)
const offset = computed(() => {
  const v = Math.min(100, Math.max(0, props.value))
  return circumference.value * (1 - v / 100)
})
</script>

<style scoped>
.ring { position: relative; display: grid; place-items: center; }
.ring svg { position: absolute; inset: 0; }
.ring-fill { transition: stroke-dashoffset .7s cubic-bezier(.2, 0, 0, 1); }
.ring-center { position: relative; text-align: center; }
.ring-value { font-size: 27px; font-weight: 500; letter-spacing: -1px; line-height: 1; }
.ring-label { margin-top: 4px; font-size: 12.5px; color: var(--c-text-3); }
</style>
