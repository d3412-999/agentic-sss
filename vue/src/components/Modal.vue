<script setup>
import { onBeforeUnmount, onMounted } from 'vue'
import Icon from './Icon.vue'

const props = defineProps({
  title: { type: String, default: '' },
  size: { type: String, default: '' }, // sm | lg | xl
  closable: { type: Boolean, default: true },
})
const emit = defineEmits(['close'])

function onKey(e) {
  if (e.key === 'Escape' && props.closable) emit('close')
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
  <div class="overlay" @click.self="closable && emit('close')">
    <div class="modal" :class="size ? `modal--${size}` : ''" role="dialog" aria-modal="true">
      <div v-if="title || $slots.head" class="modal__head">
        <slot name="head">
          <h3>{{ title }}</h3>
        </slot>
        <button v-if="closable" class="btn btn--icon" style="margin-left: auto" @click="emit('close')">
          <Icon name="x" :size="16" />
        </button>
      </div>
      <div class="modal__body">
        <slot />
      </div>
      <div v-if="$slots.foot" class="modal__foot">
        <slot name="foot" />
      </div>
    </div>
  </div>
</template>
