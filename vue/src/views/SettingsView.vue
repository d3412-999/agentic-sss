<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import { api, ROLE_LABEL } from '../api/endpoints'
import { session, currentRole, clearSession, setMe } from '../stores/session'
import { THEMES, setTheme, theme } from '../stores/theme'
import { toast, toastError } from '../stores/toast'

const router = useRouter()
const me = ref(session.me)
const loading = ref(true)
const busy = ref(false)
const error = ref('')

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const user = computed(() => me.value || session.user || {})
const roleText = computed(() => ROLE_LABEL[currentRole.value] || currentRole.value || '—')
const themeHint = computed(() => THEMES.find((item) => item.key === theme.mode)?.hint || '')
const passwordValid = computed(() => form.newPassword.length >= 8 && form.newPassword.length <= 64 && /[A-Za-z]/.test(form.newPassword) && /\d/.test(form.newPassword))

async function load() {
  loading.value = true
  try {
    me.value = await api.me()
    setMe(me.value)
  } catch (err) {
    toastError(err, '读取账号信息失败')
  } finally {
    loading.value = false
  }
}

async function changePassword() {
  error.value = ''
  if (!form.oldPassword) return (error.value = '请输入原密码')
  if (!passwordValid.value) return (error.value = '新密码需 8~64 位，且同时包含字母和数字')
  if (form.newPassword !== form.confirmPassword) return (error.value = '两次输入的新密码不一致')

  busy.value = true
  try {
    await api.changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    toast.ok('密码已修改', '所有已签发的令牌已失效，请使用新密码重新登录')
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    setTimeout(() => {
      clearSession()
      router.push({ name: 'login' })
    }, 1200)
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function logout() {
  try {
    await api.logout()
  } catch {
    // 后端不可达也允许本地退出
  }
  clearSession()
  router.push({ name: 'login' })
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>账号设置</h1>
        <p>个人信息来自后端 <span class="mono">GET /auth/me</span>；修改密码后所有旧令牌立即失效。</p>
      </div>
    </div>

    <div class="grid grid--sidebar">
      <div style="display: grid; gap: 16px">
        <section class="card">
          <div class="card__head"><h3>基本信息</h3></div>
          <div class="card__body">
            <div v-if="loading" class="skeleton" style="height: 90px" />
            <div v-else class="row" style="align-items: flex-start; gap: 16px">
              <div class="avatar avatar--lg">{{ (user.name || 'U').slice(0, 1) }}</div>
              <div style="flex: 1">
                <div class="row">
                  <h3 style="font-size: 17px; font-weight: 680">{{ user.name || '—' }}</h3>
                  <span class="pill pill--brand">{{ roleText }}</span>
                </div>
                <dl class="dl" style="margin-top: 12px">
                  <dt>账号</dt><dd class="mono">{{ user.username || '—' }}</dd>
                  <dt>邮箱</dt><dd>{{ user.email || '—' }}</dd>
                  <dt>班级</dt><dd>{{ user.className || '—' }}</dd>
                </dl>
              </div>
            </div>

            <hr class="divider" />

            <div class="section-title"><Icon name="book" :size="14" />关联课程</div>
            <div v-if="me?.offerings?.length" class="row row--wrap" style="gap: 6px">
              <span v-for="o in me.offerings" :key="o.offeringId" class="tag">
                {{ o.courseName }} · {{ o.semester }}
              </span>
            </div>
            <div v-else class="small dim">暂无关联课程（学生需教师加入教学班后可见）</div>
          </div>
        </section>

        <section class="card">
          <div class="card__head"><h3>修改密码</h3></div>
          <div class="card__body">
            <div class="form-grid">
              <div class="field span-2">
                <label>原密码</label>
                <input v-model="form.oldPassword" type="password" class="input" autocomplete="current-password" />
              </div>
              <div class="field">
                <label>新密码</label>
                <input v-model="form.newPassword" type="password" class="input" autocomplete="new-password" />
                <span class="field__hint">8~64 位，需同时包含字母和数字</span>
              </div>
              <div class="field">
                <label>确认新密码</label>
                <input v-model="form.confirmPassword" type="password" class="input" autocomplete="new-password" />
                <span v-if="form.confirmPassword && form.newPassword !== form.confirmPassword" class="field__error">
                  两次输入不一致
                </span>
              </div>
            </div>

            <div v-if="error" class="alert alert--danger" style="margin-top: 14px">
              <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ error }}
            </div>

            <div class="row" style="margin-top: 16px">
              <button class="btn btn--primary" :disabled="busy || !passwordValid" @click="changePassword">
                <span v-if="busy" class="spinner" />保存新密码
              </button>
              <span class="small dim">演示账号初始密码为 123456，不符合新密码规则</span>
            </div>
          </div>
        </section>
      </div>

      <div style="display: grid; gap: 16px; align-content: start">
        <section class="card card--pad">
          <div class="section-title"><Icon name="sun" :size="14" />外观</div>
          <div class="tabs" style="margin-bottom: 10px">
            <button
              v-for="item in THEMES"
              :key="item.key"
              class="tabs__item"
              :class="{ 'is-active': theme.mode === item.key }"
              @click="setTheme(item.key)"
            >
              <Icon :name="item.icon" :size="14" style="margin-right: 5px; vertical-align: -2px" />{{ item.label }}
            </button>
          </div>
          <p class="small dim" style="margin: 0">{{ themeHint }} · 选择会保存在本地</p>
        </section>

        <section class="card card--pad">
          <div class="section-title"><Icon name="shield" :size="14" />会话与安全</div>
          <dl class="dl" style="grid-template-columns: 84px 1fr">
            <dt>令牌类型</dt><dd class="mono">Bearer JWT</dd>
            <dt>刷新策略</dt><dd>401 自动刷新一次</dd>
            <dt>角色</dt><dd>{{ roleText }}</dd>
          </dl>
          <button class="btn btn--danger btn--block" style="margin-top: 14px" @click="logout">
            <Icon name="logout" :size="15" />退出登录
          </button>
        </section>

        <section class="card card--pad">
          <div class="section-title"><Icon name="external" :size="14" />开发者入口</div>
          <a class="menu__item" href="/swagger-ui.html" target="_blank" rel="noopener" style="padding: 8px 0">
            <Icon name="external" :size="15" />Swagger API 文档
          </a>
          <a class="menu__item" href="/v3/api-docs" target="_blank" rel="noopener" style="padding: 8px 0">
            <Icon name="code" :size="15" />OpenAPI JSON
          </a>
          <a class="menu__item" href="/api/v1/health" target="_blank" rel="noopener" style="padding: 8px 0">
            <Icon name="shield" :size="15" />后端健康检查
          </a>
        </section>
      </div>
    </div>
  </div>
</template>
