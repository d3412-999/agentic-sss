<script setup>
import { useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'

const router = useRouter()
</script>

<template>
  <div style="display: grid; place-items: center; min-height: 60vh; text-align: center">
    <div>
      <div class="num" style="font-size: 72px; font-weight: 800; letter-spacing: -4px; line-height: 1; opacity: 0.16">
        404
      </div>
      <h1 style="font-size: 20px; font-weight: 680; margin-top: 10px">页面不存在</h1>
      <p class="muted small" style="margin-top: 8px">链接可能已失效，或者你的角色没有访问权限。</p>
      <div class="row" style="justify-content: center; margin-top: 20px">
        <button class="btn btn--ghost" @click="router.back()">返回上一页</button>
        <button class="btn btn--primary" @click="router.push('/')">
          <Icon name="home" :size="15" />回到概览
        </button>
      </div>
    </div>
  </div>
</template>
