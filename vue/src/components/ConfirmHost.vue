<script setup>
import { onBeforeUnmount, onMounted } from 'vue'
import { dialog, settle } from '../stores/confirm'
import Modal from './Modal.vue'

function onKey(e) {
  if (!dialog.open) return
  if (e.key === 'Escape') settle(false)
  if (e.key === 'Enter') settle(true)
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Modal
    v-if="dialog.open"
    size="sm"
    :title="dialog.title"
    @close="settle(false)"
  >
    <p class="muted" style="white-space: pre-line">{{ dialog.message }}</p>
    <template #foot>
      <button class="btn btn--ghost" type="button" @click="settle(false)">
        {{ dialog.cancelText }}
      </button>
      <button
        class="btn"
        :class="dialog.danger ? 'btn--danger' : 'btn--primary'"
        type="button"
        @click="settle(true)"
      >
        {{ dialog.confirmText }}
      </button>
    </template>
  </Modal>
</template>
