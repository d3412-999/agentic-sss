<script setup>
import { computed } from 'vue'

const props = defineProps({
  value: { type: Number, default: 0 }, // 0..100
  size: { type: Number, default: 56 },
  stroke: { type: Number, default: 5 },
  label: { type: String, default: '' },
  tone: { type: String, default: 'brand' }, // brand | ok | warn | danger
})

const r = computed(() => (props.size - props.stroke) / 2)
const c = computed(() => 2 * Math.PI * r.value)
const pct = computed(() => Math.max(0, Math.min(100, Number(props.value) || 0)))
const offset = computed(() => c.value * (1 - pct.value / 100))

const color = computed(() => ({
  brand: 'var(--brand)',
  ok: 'var(--ok)',
  warn: 'var(--warn)',
  danger: 'var(--danger)',
}[props.tone] || 'var(--brand)'))
</script>

<template>
  <div class="ring" :style="{ width: size + 'px', height: size + 'px' }">
    <svg :width="size" :height="size">
      <circle
        :cx="size / 2"
        :cy="size / 2"
        :r="r"
        fill="none"
        stroke="var(--surface-3)"
        :stroke-width="stroke"
      />
      <circle
        :cx="size / 2"
        :cy="size / 2"
        :r="r"
        fill="none"
        :stroke="color"
        :stroke-width="stroke"
        stroke-linecap="round"
        :stroke-dasharray="c"
        :stroke-dashoffset="offset"
        style="transition: stroke-dashoffset 0.5s cubic-bezier(0.22, 0.61, 0.36, 1)"
      />
    </svg>
    <div class="ring__label">
      <div class="ring__value" :style="{ fontSize: size > 72 ? '18px' : '14px' }">{{ Math.round(pct) }}%</div>
      <div v-if="label" class="dim" style="font-size: 10px">{{ label }}</div>
    </div>
  </div>
</template>
