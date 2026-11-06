<template>
  <RouteProgress />
  <router-view />
</template>

<script setup>
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import RouteProgress from '@/components/RouteProgress.vue'

const route = useRoute()

// 统一维护页面标题；切换路由时立即回到顶部，滚动交给页面入场动画处理
watch(
  () => route.fullPath,
  () => {
    document.title = route.meta.title
      ? `${route.meta.title} · 课程设计智能答辩系统`
      : '课程设计智能答辩与评分系统'
    window.scrollTo({ top: 0 })
  },
  { immediate: true }
)
</script>
