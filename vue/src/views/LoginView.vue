<template>
  <div class="login-page">
    <div class="login-glow" />

    <div class="login-inner">
      <div class="login-brand">
        <div class="brand-mark lg">S</div>
        <div>
          <div class="login-name">课程设计智能答辩与评分系统</div>
          <div class="login-sub">面向对象程序设计课程设计 · Java</div>
        </div>
      </div>

      <div class="login-card">
        <h1 class="login-title">登录</h1>
        <p class="login-desc">使用学号或工号登录，进入课程设计系统</p>

        <div class="segmented">
          <button
            v-for="r in roles"
            :key="r.value"
            class="seg"
            :class="{ active: role === r.value }"
            @click="role = r.value"
          >
            <AppIcon :name="r.icon" :size="17" />
            {{ r.label }}
          </button>
        </div>

        <form class="stack" @submit.prevent="submit">
          <div class="field">
            <label class="label" for="account">{{ role === 'teacher' ? '工号' : '学号' }}</label>
            <input
              id="account"
              v-model="account"
              class="input"
              :placeholder="role === 'teacher' ? '如 T2019008' : '如 2023010101'"
              autocomplete="username"
            />
          </div>

          <div class="field">
            <label class="label" for="password">密码</label>
            <div class="pwd">
              <input
                id="password"
                v-model="password"
                class="input"
                :type="visible ? 'text' : 'password'"
                placeholder="请输入密码"
                autocomplete="current-password"
              />
              <button type="button" class="icon-btn pwd-eye" @click="visible = !visible" aria-label="显示密码">
                <AppIcon :name="visible ? 'eyeOff' : 'eye'" :size="19" />
              </button>
            </div>
          </div>

          <div class="between login-row">
            <label class="check">
              <input v-model="remember" type="checkbox" />
              记住我
            </label>
            <a class="link small" href="#" @click.prevent>忘记密码？</a>
          </div>

          <button class="btn btn-primary btn-lg btn-block" type="submit">
            登录
            <AppIcon name="arrowRight" :size="18" />
          </button>
        </form>

        <div class="demo">
          <div class="demo-title">
            <AppIcon name="sparkle" :size="15" />
            演示账号
          </div>
          <div class="demo-body">
            选择身份后点击登录即可进入：<span class="mono">{{ demo.account }}</span> · {{ demo.name }}
          </div>
        </div>
      </div>

      <div class="login-foot">
        课程设计智能答辩与评分系统 · v0.1 界面预览
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { demoAccounts, signIn } from '@/stores/session'

const router = useRouter()
const route = useRoute()

const role = ref('student')
const visible = ref(false)
const remember = ref(true)
const account = ref(demoAccounts.student.account)
const password = ref('123456')

const roles = [
  { value: 'student', label: '学生', icon: 'book' },
  { value: 'teacher', label: '教师', icon: 'users' }
]

const demo = computed(() => demoAccounts[role.value])

function submit() {
  signIn(role.value)
  const redirect = route.query.redirect
  router.push(typeof redirect === 'string' ? redirect : role.value === 'teacher' ? '/teacher/offerings' : '/student')
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  position: relative;
  overflow: hidden;
  display: grid;
  place-items: center;
  padding: 40px 20px;
  background: var(--c-bg);
}

.login-glow {
  position: absolute;
  inset: -20% -10% auto -10%;
  height: 70vh;
  background:
    radial-gradient(closest-side at 30% 35%, rgba(26, 115, 232, .07), transparent 70%),
    radial-gradient(closest-side at 72% 20%, rgba(190, 156, 96, .12), transparent 70%),
    radial-gradient(closest-side at 55% 60%, rgba(251, 188, 5, .08), transparent 70%);
  pointer-events: none;
}

.login-inner { position: relative; width: min(440px, 100%); }

.login-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 28px;
  animation: login-in .48s var(--ease) both;
}
.brand-mark.lg { width: 46px; height: 46px; border-radius: 14px; font-size: 21px; }
.login-name { font-size: 18px; font-weight: 600; letter-spacing: -.3px; }
.login-sub { font-size: 13.5px; color: var(--c-text-3); margin-top: 3px; }

.login-card {
  background: var(--c-surface);
  border: 1px solid var(--c-line-2);
  border-radius: 24px;
  padding: 30px 28px 24px;
  box-shadow: 0 1px 2px rgba(74, 62, 45, .05), 0 14px 34px rgba(74, 62, 45, .08);
  animation: login-in .56s var(--ease) .06s both;
}

.login-title { font-size: 27px; font-weight: 500; letter-spacing: -.6px; }
.login-desc { color: var(--c-text-2); font-size: 14.5px; margin-top: 6px; }

.segmented {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  padding: 4px;
  margin: 22px 0 20px;
  background: var(--c-grey-100);
  border-radius: 999px;
}

.seg {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  height: 38px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--c-text-2);
  font-size: 14.5px;
  cursor: pointer;
  transition: background .18s var(--ease), color .18s var(--ease), box-shadow .18s var(--ease);
}

.seg.active {
  background: var(--c-surface);
  color: var(--c-blue-700);
  font-weight: 500;
  box-shadow: 0 1px 2px rgba(74, 62, 45, .14);
}

.stack { display: flex; flex-direction: column; gap: 16px; }

.pwd { position: relative; }
.pwd .input { padding-right: 48px; }
.pwd-eye { position: absolute; right: 4px; top: 2px; color: var(--c-text-3); }

.login-row { margin: -2px 0 2px; }
.check { display: inline-flex; align-items: center; gap: 7px; font-size: 14px; color: var(--c-text-2); cursor: pointer; }
.check input { width: 15px; height: 15px; accent-color: var(--c-blue); }

.demo {
  margin-top: 20px;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--c-blue-50);
  color: var(--c-blue-700);
}
.demo-title { display: flex; align-items: center; gap: 6px; font-size: 13.5px; font-weight: 600; }
.demo-body { margin-top: 4px; font-size: 13.5px; line-height: 1.6; color: #3b5b8c; }

.login-foot { margin-top: 22px; text-align: center; color: var(--c-text-3); font-size: 13px; animation: login-in .5s var(--ease) .14s both; }

@keyframes login-in {
  from { opacity: 0; transform: translateY(16px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
