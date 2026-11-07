<script setup>
import { onBeforeUnmount, onMounted } from 'vue'
import Icon from './Icon.vue'

const props = defineProps({
  title: { type: String, default: '' },
  wide: { type: Boolean, default: false },
})
const emit = defineEmits(['close'])

function onKey(e) {
  if (e.key === 'Escape') emit('close')
}

onMounted(() => {
  window.addEventListener('keydown', onKey)
  document.body.style.overflow = 'hidden'
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})
</script>

<template>
  <div class="overlay" style="display: block; padding: 0" @click.self="emit('close')">
    <aside class="drawer" :class="wide ? 'drawer--wide' : ''">
      <header class="drawer__head">
        <slot name="head">
          <h3 style="font-size: 15px; font-weight: 650">{{ title }}</h3>
        </slot>
        <button class="btn btn--icon" style="margin-left: auto" @click="emit('close')">
          <Icon name="x" :size="16" />
        </button>
      </header>
      <div class="drawer__body">
        <slot />
      </div>
      <footer v-if="$slots.foot" class="drawer__foot">
        <slot name="foot" />
      </footer>
    </aside>
  </div>
</template>
