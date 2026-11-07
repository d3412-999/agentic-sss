<script setup>
import { computed } from 'vue'
import Icon from './Icon.vue'

const props = defineProps({
  page: { type: Number, default: 1 },
  size: { type: Number, default: 20 },
  total: { type: Number, default: 0 },
})
const emit = defineEmits(['update:page'])

const pages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.size))))
const from = computed(() => (props.total === 0 ? 0 : (props.page - 1) * props.size + 1))
const to = computed(() => Math.min(props.total, props.page * props.size))

const canPrev = computed(() => props.page > 1)
const canNext = computed(() => props.page < pages.value)

function go(p) {
  if (p < 1 || p > pages.value || p === props.page) return
  emit('update:page', p)
}

const items = computed(() => {
  const last = pages.value
  const cur = props.page
  const set = new Set([1, last, cur, cur - 1, cur + 1])
  return [...set].filter((n) => n >= 1 && n <= last).sort((a, b) => a - b)
})
</script>

<template>
  <div class="row small dim" style="padding: 12px 16px; border-top: 1px solid var(--line)">
    <span class="num">{{ from }}–{{ to }} / {{ total }}</span>
    <span class="spacer" />
    <button class="btn btn--ghost btn--sm" :disabled="!canPrev" @click="go(page - 1)">
      <Icon name="chevron-left" :size="14" />
    </button>
    <template v-for="(n, i) in items" :key="n">
      <span v-if="i > 0 && n - items[i - 1] > 1" class="dim" style="padding: 0 2px">…</span>
      <button
        class="btn btn--sm"
        :class="n === page ? 'btn--soft' : 'btn--ghost'"
        style="min-width: 30px"
        @click="go(n)"
      >
        {{ n }}
      </button>
    </template>
    <button class="btn btn--ghost btn--sm" :disabled="!canNext" @click="go(page + 1)">
      <Icon name="chevron-right" :size="14" />
    </button>
  </div>
</template>
