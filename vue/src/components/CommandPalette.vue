<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from './Icon.vue'
import { navItemsForRole } from '../config/nav'
import { session, currentRole, clearSession } from '../stores/session'
import { nextThemeMode, theme, themeName, toggleTheme } from '../stores/theme'
import { api } from '../api/endpoints'

const emit = defineEmits(['close'])
const router = useRouter()
const route = useRoute()

const open = ref(true)
const query = ref('')
const active = ref(0)
const inputRef = ref(null)

const actions = computed(() => {
  const list = navItemsForRole(currentRole.value).map((n) => ({
    id: `go:${n.to}`,
    label: `前往 ${n.paletteLabel || n.label}`,
    hint: n.to,
    icon: n.icon,
    run: () => router.push(n.to),
  }))
  list.push(
    {
      id: 'theme',
      label: `切换到${themeName(nextThemeMode(theme.mode))}主题`,
      hint: '外观',
      icon: nextThemeMode(theme.mode) === 'dark' ? 'moon' : 'sun',
      run: toggleTheme,
    },
    {
      id: 'swagger',
      label: '打开 Swagger API 文档',
      hint: '新窗口',
      icon: 'external',
      run: () => window.open('/swagger-ui.html', '_blank'),
    },
    {
      id: 'logout',
      label: '退出登录',
      hint: '会话',
      icon: 'logout',
      run: async () => {
        try {
          await api.logout()
        } catch {
          // 后端不可达也允许本地退出
        }
        clearSession()
        router.push({ name: 'login' })
      },
    },
  )
  return list
})

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return actions.value
  return actions.value.filter((a) => `${a.label} ${a.hint}`.toLowerCase().includes(q))
})

watch(filtered, () => {
  active.value = 0
})

function close() {
  open.value = false
  emit('close')
}

function run(item) {
  close()
  item.run()
}

function onKey(e) {
  if (e.key === 'Escape') return close()
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    active.value = (active.value + 1) % Math.max(1, filtered.value.length)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    active.value = (active.value - 1 + filtered.value.length) % Math.max(1, filtered.value.length)
  } else if (e.key === 'Enter') {
    const item = filtered.value[active.value]
    if (item) run(item)
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  await nextTick()
  inputRef.value?.focus()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

const name = computed(() => session.user?.name || session.me?.name || '')
</script>

<template>
  <div class="cmd" @click.self="close">
    <div class="cmd__box" :class="{ 'cmd__box--fade': !open }">
      <input
        ref="inputRef"
        v-model="query"
        class="cmd__input"
        placeholder="搜索页面或操作…（Esc 关闭）"
        @keydown.stop="onKey"
      />
      <div class="cmd__list">
        <button
          v-for="(item, i) in filtered"
          :key="item.id"
          class="cmd__item"
          :class="{ 'is-active': i === active }"
          @mouseenter="active = i"
          @click="run(item)"
        >
          <Icon :name="item.icon" :size="15" />
          <span>{{ item.label }}</span>
          <span class="cmd__hint">{{ item.hint }}</span>
        </button>
        <div v-if="!filtered.length" class="empty" style="padding: 24px">
          <span class="small">没有匹配的命令</span>
        </div>
      </div>
      <div class="row small dim" style="padding: 8px 14px; border-top: 1px solid var(--line)">
        <span>{{ name }} · {{ route.name }}</span>
        <span class="spacer" />
        <span class="kbd">↑</span><span class="kbd">↓</span><span class="kbd">↵</span>
      </div>
    </div>
  </div>
</template>
