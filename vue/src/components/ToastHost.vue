<script setup>
import { toasts, dismiss } from '../stores/toast'
import Icon from './Icon.vue'

const iconOf = { ok: 'check', error: 'x', info: 'flag' }
</script>

<template>
  <div class="toasts" role="status" aria-live="polite">
    <div v-for="t in toasts" :key="t.id" class="toast" :class="`toast--${t.type}`">
      <span class="toast__icon">
        <Icon :name="iconOf[t.type] || 'flag'" :size="12" :stroke="2.6" />
      </span>
      <div style="min-width: 0">
        <strong>{{ t.title }}</strong>
        <p v-if="t.message">{{ t.message }}</p>
      </div>
      <button class="btn btn--icon btn--sm" style="width: 22px; height: 22px" @click="dismiss(t.id)">
        <Icon name="x" :size="13" />
      </button>
    </div>
  </div>
</template>
