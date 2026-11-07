<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/endpoints'
import { saveLogin, setMe } from '../stores/session'

// 与 V2__seed_demo_data.sql 的 6 个账号逐一对应（V2 注释明确要求此处一致）。
// 弱口令仅供本地演示，生产环境必须删除 V2 或改用 profile 隔离。
const DEMO_PASSWORD = '123456'
const demoAccounts = [
  { username: '2023010101', name: '张三', role: 'STUDENT', label: '学生' },
  { username: '2023010102', name: '李四', role: 'STUDENT', label: '学生' },
  { username: '2023010103', name: '王五', role: 'STUDENT', label: '学生' },
  { username: 'T2019008', name: '李文博', role: 'TEACHER', label: '教师' },
  { username: 'T2019009', name: '周敏', role: 'TEACHER', label: '教师' },
  { username: 'admin', name: '系统管理员', role: 'ADMIN', label: '管理员' },
]

const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '', role: 'STUDENT' })
const loading = ref(false)
const error = ref('')
const backend = ref('checking')
const health = ref(null)

onMounted(async () => {
  try {
    health.value = await api.health()
    backend.value = 'up'
  } catch {
    backend.value = 'down'
  }
})

function fillDemo(account) {
  form.username = account.username
  form.password = DEMO_PASSWORD
  form.role = account.role
  error.value = ''
}

function describeError(e) {
  const trace = e?.traceId ? `（traceId: ${e.traceId}）` : ''
  return `${e?.message || '登录失败'}${trace}`
}

async function submit() {
  error.value = ''
  loading.value = true
  try {
    // role 只是前端入口提示，服务端一律以 sys_user 的真实角色为准
    const data = await api.login({
      username: form.username,
      password: form.password,
      role: form.role,
    })
    saveLogin(data)
    try {
      setMe(await api.me())
    } catch {
      // /me 失败不阻断登录，概览页会再试一次
    }
    await router.push(String(route.query.redirect || '/'))
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-card">
    <div class="login-head">
      <div class="brand-mark">S</div>
      <h1>课程设计智能答辩与评分系统</h1>
      <p class="muted">选题 · 提交 · 评审 · 答辩 · 评分</p>
    </div>

    <div v-if="backend === 'up'" class="alert alert--success">
      后端已连通：{{ health?.app }} · {{ health?.status }}
    </div>
    <div v-else-if="backend === 'down'" class="alert alert--error">
      后端未连通。请先启动 Spring Boot（默认 8081，8080 被本机 nginx 占用），并确认 vue/.env 的
      VITE_API_TARGET 指向正确端口。
    </div>
    <div v-else class="alert alert--info">正在探测后端…</div>

    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <form @submit.prevent="submit">
      <div class="field">
        <label for="username">学号 / 工号</label>
        <input id="username" v-model="form.username" autocomplete="username" placeholder="如 2023010101" required />
      </div>
      <div class="field">
        <label for="password">密码</label>
        <input id="password" v-model="form.password" type="password" autocomplete="current-password" placeholder="演示密码见下方" required />
      </div>
      <div class="field">
        <label for="role">入口角色（仅作提示，以账号真实角色为准）</label>
        <select id="role" v-model="form.role">
          <option value="STUDENT">学生</option>
          <option value="TEACHER">教师</option>
          <option value="ADMIN">管理员</option>
        </select>
      </div>
      <button class="btn" type="submit" style="width: 100%" :disabled="loading">
        {{ loading ? '登录中…' : '登录' }}
      </button>
    </form>

    <div style="margin-top: 22px">
      <h3>演示账号</h3>
      <p class="muted">统一密码 {{ DEMO_PASSWORD }}，点击即可填充。由 Flyway 的 V2 种子脚本写入。</p>
      <div class="demo-grid">
        <button
          v-for="account in demoAccounts"
          :key="account.username"
          class="demo-item"
          type="button"
          @click="fillDemo(account)"
        >
          <strong>{{ account.name }}</strong>
          <span>{{ account.label }} · {{ account.username }}</span>
        </button>
      </div>
    </div>
  </div>
</template>
