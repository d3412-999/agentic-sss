<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import { api } from '../api/endpoints'
import { clearSession, session } from '../stores/session'
import { toast } from '../stores/toast'

/**
 * 首次登录强制改密。
 *
 * 学生账号由教师批量导入，初始密码默认就是学号（must_change_password=1）；
 * 管理员新建的教师账号同样带这个标记。标记为真时用不可关闭的弹窗挡住界面，
 * 改完密码后令牌版本已递增，必须重新登录。
 */
const router = useRouter()
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const busy = ref(false)
const error = ref('')

const visible = computed(() => Boolean(session.user?.mustChangePassword))
const passwordValid = computed(
  () =>
    form.newPassword.length >= 8 &&
    form.newPassword.length <= 64 &&
    /[A-Za-z]/.test(form.newPassword) &&
    /\d/.test(form.newPassword),
)

async function submit() {
  error.value = ''
  if (!form.oldPassword) return (error.value = '请输入当前密码（学生初始密码是学号，教师由管理员告知）')
  if (!passwordValid.value) return (error.value = '新密码需 8~64 位，且同时包含字母和数字')
  if (form.newPassword !== form.confirmPassword) return (error.value = '两次输入的新密码不一致')

  busy.value = true
  try {
    await api.changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    toast.ok('密码已修改', '请使用新密码重新登录')
    clearSession()
    router.push({ name: 'login' })
  } catch (err) {
    error.value = err?.message || '修改失败'
  } finally {
    busy.value = false
  }
}

function logoutNow() {
  clearSession()
  router.push({ name: 'login' })
}
</script>

<template>
  <Modal v-if="visible" title="首次登录，请修改密码" size="sm" :closable="false">
    <div style="display: grid; gap: 13px">
      <div class="alert alert--info">
        <Icon name="lock" :size="15" style="flex: none; margin-top: 2px" />
        当前使用的是系统初始密码，修改后才能继续使用系统。
      </div>
      <div class="field">
        <label>当前密码</label>
        <input v-model="form.oldPassword" class="input" type="password" autocomplete="current-password" />
      </div>
      <div class="field">
        <label>新密码</label>
        <input v-model="form.newPassword" class="input" type="password" autocomplete="new-password" placeholder="8~64 位，含字母和数字" />
      </div>
      <div class="field">
        <label>确认新密码</label>
        <input v-model="form.confirmPassword" class="input" type="password" autocomplete="new-password" />
      </div>
      <div v-if="error" class="alert alert--danger">
        <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ error }}
      </div>
    </div>
    <template #foot>
      <button class="btn btn--ghost btn--sm" style="margin-right: auto" @click="logoutNow">退出登录</button>
      <button class="btn btn--primary" :disabled="busy" @click="submit">
        <span v-if="busy" class="spinner" />修改并重新登录
      </button>
    </template>
  </Modal>
</template>
