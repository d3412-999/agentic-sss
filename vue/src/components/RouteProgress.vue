<template>
  <div class="route-progress" :class="{ active: visible }" aria-hidden="true">
    <span :style="{ width: pct + '%' }" />
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const visible = ref(false)
const pct = ref(0)

let ticker = null
let hideTimer = null
let resetTimer = null

function start() {
  clearTimeout(hideTimer)
  clearTimeout(resetTimer)
  visible.value = true
  pct.value = 8
  clearInterval(ticker)
  ticker = setInterval(() => {
    if (pct.value < 86) {
      pct.value += Math.max(1.2, (88 - pct.value) * 0.18)
    }
  }, 150)
}

function done() {
  clearInterval(ticker)
  pct.value = 100
  hideTimer = setTimeout(() => {
    visible.value = false
    resetTimer = setTimeout(() => (pct.value = 0), 260)
  }, 160)
}

let offBefore = null
let offAfter = null
let offError = null

onMounted(() => {
  offBefore = router.beforeEach(() => {
    start()
    return true
  })
  offAfter = router.afterEach(() => done())
  offError = router.onError(() => done())
})

onBeforeUnmount(() => {
  clearInterval(ticker)
  clearTimeout(hideTimer)
  clearTimeout(resetTimer)
  offBefore?.()
  offAfter?.()
  offError?.()
})
</script>

<style scoped>
.route-progress {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  z-index: 300;
  pointer-events: none;
  opacity: 0;
  transition: opacity .24s var(--ease);
}
.route-progress.active { opacity: 1; }
.route-progress > span {
  display: block;
  height: 100%;
  width: 0;
  border-radius: 0 2px 2px 0;
  background: linear-gradient(90deg, #6aa9f4, var(--c-blue));
  box-shadow: 0 0 10px rgba(26, 115, 232, .45);
  transition: width .2s ease-out;
}
</style>
