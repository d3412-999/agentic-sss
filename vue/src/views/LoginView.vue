<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import { api } from '../api/endpoints'
import { saveLogin, setMe } from '../stores/session'
import { theme, toggleTheme } from '../stores/theme'
import { toast } from '../stores/toast'

const router = useRouter()
const route = useRoute()

const role = ref('STUDENT')
const form = reactive({ username: '', password: '' })
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')

const backend = ref('checking')
const health = ref(null)

const ROLES = [
  { key: 'STUDENT', label: '学生', hint: '选题 · 提交 · 查看成绩' },
  { key: 'TEACHER', label: '教师', hint: '开课 · 出题 · 评分发布' },
  { key: 'ADMIN', label: '管理员', hint: '账号与系统运维' },
]

const DEMO = [
  { role: 'STUDENT', username: '2023010101', name: '张三' },
  { role: 'STUDENT', username: '2023010102', name: '李四' },
  { role: 'TEACHER', username: 'T2019008', name: '李文博' },
  { role: 'TEACHER', username: 'T2019009', name: '周敏' },
  { role: 'ADMIN', username: 'admin', name: '系统管理员' },
]

const demoList = computed(() => DEMO.filter((d) => d.role === role.value))
const activeRole = computed(() => ROLES.find((r) => r.key === role.value))

const FEATURES = [
  { icon: 'target', title: '并发安全的选题', desc: '原子 SQL 预占名额 + 唯一索引兜底，不会超选' },
  { icon: 'wand', title: 'AI 源码与报告评审', desc: '异步任务队列，评审结果直接参与算分' },
  { icon: 'chart', title: '可解释的成绩构成', desc: '答辩 / 源码 / 报告三维加权，调整留痕可审计' },
  { icon: 'shield', title: 'JWT + 幂等写操作', desc: '令牌版本号即时失效，关键写接口防重复提交' },
]

onMounted(async () => {
  try {
    health.value = await api.health()
    backend.value = 'up'
  } catch {
    backend.value = 'down'
  }
})

function useDemo(account) {
  form.username = account.username
  form.password = '123456'
  error.value = ''
}

async function submit() {
  error.value = ''
  if (!form.username.trim() || !form.password) {
    error.value = '请输入学号 / 工号与密码'
    return
  }
  loading.value = true
  try {
    const data = await api.login({
      username: form.username.trim(),
      password: form.password,
      role: role.value,
    })
    saveLogin(data)
    try {
      setMe(await api.me())
    } catch {
      // me 失败不影响登录流程
    }
    toast.ok('登录成功', `欢迎回来，${data?.user?.name || data?.user?.username || ''}`)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.push(redirect)
  } catch (err) {
    const fields = err?.data?.errors
    error.value = Array.isArray(fields) && fields.length
      ? fields.map((f) => f.message).join('；')
      : err.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth">
    <aside class="auth__aside">
      <div class="row">
        <span class="brand-mark">S</span>
        <div class="sidebar__brand-text">
          <strong style="color: #fff">智能答辩与评分</strong>
          <span style="color: #6b7794">agentic-sss</span>
        </div>
        <button class="btn btn--icon" style="margin-left: auto; color: #97a3c4" @click="toggleTheme">
          <Icon :name="theme.mode === 'dark' ? 'sun' : 'moon'" :size="17" />
        </button>
      </div>

      <h1 class="auth__headline">
        把课程设计<br />
        从<em>收作业</em>变成一次<br />
        可解释的评审
      </h1>
      <p class="auth__sub">
        《面向对象程序设计课程设计》全流程支撑：教师开课出题，学生选题提交源码与报告，
        AI 完成静态与语义评审，成绩按答辩 / 源码 / 报告三维加权发布，每一步都可追溯。
      </p>

      <div class="auth__features">
        <div v-for="f in FEATURES" :key="f.title" class="auth__feature">
          <span class="auth__feature-icon"><Icon :name="f.icon" :size="15" /></span>
          <div>
            <strong>{{ f.title }}</strong>
            <span>{{ f.desc }}</span>
          </div>
        </div>
      </div>

      <div class="auth__foot">
        Spring Boot 3 · Vue 3 · FastAPI · MySQL 8 · Redis 7
      </div>
    </aside>

    <section class="auth__panel">
      <div class="auth-box">
        <h2>登录工作台</h2>
        <p class="auth-box__sub">{{ activeRole.hint }}</p>

        <div class="auth-status" :class="backend === 'up' ? 'auth-status--ok' : backend === 'down' ? 'auth-status--bad' : ''">
          <span v-if="backend === 'up'" class="dot-live" />
          <span v-else-if="backend === 'down'" class="pill__dot" />
          <span v-else class="spinner" />
          <span v-if="backend === 'up'">后端已连接 · {{ health?.app }} · {{ health?.status }}</span>
          <span v-else-if="backend === 'down'">
            后端未连通。请先启动 Spring Boot（默认 8081），并确认 vue/.env 的 VITE_API_TARGET 指向正确端口。
          </span>
          <span v-else>正在探测后端…</span>
        </div>

        <div class="role-tabs">
          <button
            v-for="r in ROLES"
            :key="r.key"
            type="button"
            :class="{ 'is-active': role === r.key }"
            @click="role = r.key"
          >
            {{ r.label }}
          </button>
        </div>

        <form class="auth-form" @submit.prevent="submit">
          <div class="field">
            <label for="username">学号 / 工号</label>
            <input
              id="username"
              v-model="form.username"
              class="input"
              autocomplete="username"
              :placeholder="role === 'STUDENT' ? '例如 2023010101' : role === 'TEACHER' ? '例如 T2019008' : '例如 admin'"
            />
          </div>

          <div class="field">
            <label for="password">密码</label>
            <div style="position: relative">
              <input
                id="password"
                v-model="form.password"
                class="input"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="演示账号统一密码 123456"
                style="padding-right: 42px"
              />
              <button
                type="button"
                class="btn btn--icon"
                style="position: absolute; right: 2px; top: 1px; height: 34px"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <Icon name="eye" :size="16" />
              </button>
            </div>
          </div>

          <div v-if="error" class="alert alert--danger">
            <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
            <span>{{ error }}</span>
          </div>

          <button class="btn btn--primary btn--block" type="submit" :disabled="loading" style="height: 40px">
            <span v-if="loading" class="spinner" />
            {{ loading ? '正在登录…' : `以${activeRole.label}身份登录` }}
          </button>
        </form>

        <div style="margin-top: 22px">
          <div class="row small dim">
            <span>演示账号（密码 123456）</span>
            <span class="spacer" />
            <span>点击自动填充</span>
          </div>
          <div class="demo-grid">
            <button v-for="d in demoList" :key="d.username" class="demo-chip" type="button" @click="useDemo(d)">
              <Icon name="users" :size="13" />
              <span><strong>{{ d.username }}</strong> · {{ d.name }}</span>
            </button>
          </div>
        </div>

        <p class="small dim" style="margin-top: 22px">
          登录即表示你了解：演示数据与默认口令仅用于本地开发环境。
        </p>
      </div>
    </section>
  </div>
</template>
