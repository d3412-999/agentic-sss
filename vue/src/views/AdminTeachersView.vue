<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import Icon from '../components/Icon.vue'
import StatusPill from '../components/StatusPill.vue'
import EmptyState from '../components/EmptyState.vue'
import PageHeader from '../components/layout/PageHeader.vue'
import Modal from '../components/Modal.vue'
import Pager from '../components/Pager.vue'
import { api } from '../api/endpoints'
import { confirm } from '../stores/confirm'
import { toast, toastError } from '../stores/toast'

/**
 * 管理员平台唯一页面：教师账号与权限。
 *
 * 权限采用「角色即权限包」：教师账号创建后 role=TEACHER，
 * 即拥有下方权限目录里的全部教师能力，等同学习通里的教师角色。
 */
const loading = ref(true)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')
const statusFilter = ref('')
const permissions = ref([])

const formOpen = ref(false)
const editing = ref(null)
const busy = ref(false)
const formError = ref('')
const form = reactive({ username: '', name: '', department: '', phone: '', email: '' })

const batchOpen = ref(false)
const batchText = ref('')
const batchBusy = ref(false)
const batchResult = ref(null)

const credential = ref(null)

const isEditing = computed(() => Boolean(editing.value))
const parsedBatchRows = computed(() => parseBatch(batchText.value))

async function load() {
  loading.value = true
  try {
    const data = await api.adminTeachers({
      page: page.value,
      size,
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
    })
    rows.value = data?.list || []
    total.value = data?.total || 0
  } catch (err) {
    toastError(err, '加载教师列表失败')
  } finally {
    loading.value = false
  }
}

async function loadPermissions() {
  try {
    permissions.value = (await api.adminTeacherPermissions()) || []
  } catch {
    // 权限目录只用于展示；接口失败不影响账号管理
    permissions.value = []
  }
}

function resetForm() {
  Object.assign(form, { username: '', name: '', department: '', phone: '', email: '' })
  formError.value = ''
}

function openCreate() {
  editing.value = null
  resetForm()
  formOpen.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    username: row.username || '',
    name: row.name || '',
    department: row.department || '',
    phone: row.phone || '',
    email: row.email || '',
  })
  formError.value = ''
  formOpen.value = true
}

function payload() {
  return {
    department: form.department.trim() || undefined,
    phone: form.phone.trim() || undefined,
    email: form.email.trim() || undefined,
  }
}

async function submitForm() {
  formError.value = ''
  if (!isEditing.value && !form.username.trim()) return (formError.value = '工号不能为空')
  if (!form.name.trim()) return (formError.value = '姓名不能为空')

  busy.value = true
  try {
    if (isEditing.value) {
      await api.updateTeacher(editing.value.id, { name: form.name.trim(), ...payload() })
      toast.ok('资料已更新', form.name.trim())
    } else {
      const created = await api.createTeacher({ username: form.username.trim(), name: form.name.trim(), ...payload() })
      credential.value = {
        title: '教师账号已创建',
        account: created?.username || form.username.trim(),
        password: created?.initialPassword || '统一初始密码',
        hint: '账号使用统一初始密码，请通知教师首次登录后立即修改。',
      }
    }
    formOpen.value = false
    await load()
  } catch (err) {
    const fields = err?.data?.errors
    formError.value = Array.isArray(fields) && fields.length
      ? fields.map((f) => f.message).join('；')
      : err.message || '提交失败'
  } finally {
    busy.value = false
  }
}

function parseBatch(text) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const parts = line.replace(/，/g, ',').split(',').map((p) => p.trim())
      return {
        username: parts[0] || '',
        name: parts[1] || '',
        department: parts[2] || '',
        phone: parts[3] || '',
        email: parts[4] || '',
      }
    })
}

function openBatch() {
  batchText.value = ''
  batchResult.value = null
  batchOpen.value = true
}

async function submitBatch() {
  batchResult.value = null
  const list = parsedBatchRows.value
  if (!list.length) return toast.info('请先填写教师', '每行一位教师，至少填写工号与姓名')
  batchBusy.value = true
  try {
    const result = await api.createTeachersBatch(list)
    batchResult.value = result
    if (!result?.failCount) {
      toast.ok('批量创建完成', `成功 ${result?.successCount || list.length} 位教师`)
      batchOpen.value = false
      await load()
    } else {
      toast.info('批量创建完成', `成功 ${result?.successCount} 位，失败 ${result?.failCount} 位`)
      await load()
    }
  } catch (err) {
    toastError(err, '批量创建失败')
  } finally {
    batchBusy.value = false
  }
}

async function toggleStatus(row) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const ok = await confirm({
    title: next === 'ACTIVE' ? '启用账号' : '停用账号',
    message: next === 'ACTIVE'
      ? `恢复「${row.name}」的登录权限？`
      : `停用后「${row.name}」将立即退出登录且无法再访问系统。`,
    danger: next === 'DISABLED',
    confirmText: next === 'ACTIVE' ? '启用' : '停用',
  })
  if (!ok) return
  try {
    await api.updateTeacherStatus(row.id, next)
    toast.ok(next === 'ACTIVE' ? '已启用' : '已停用', row.name)
    await load()
  } catch (err) {
    toastError(err, '状态更新失败')
  }
}

async function resetPassword(row) {
  const ok = await confirm({
    title: '重置密码',
    message: `把「${row.name}」的密码重置为统一初始密码？该账号的旧令牌会立即失效。`,
    confirmText: '重置',
  })
  if (!ok) return
  try {
    const data = await api.resetTeacherPassword(row.id)
    credential.value = {
      title: '密码已重置',
      account: row.username,
      password: data?.initialPassword || '统一初始密码',
      hint: data?.mustChangePassword ? '该账号下次登录需要修改密码。' : '',
    }
  } catch (err) {
    toastError(err, '重置失败')
  }
}

async function remove(row) {
  const ok = await confirm({
    title: '删除教师账号',
    message: `删除「${row.name}（${row.username}）」？删除后该工号可重新创建，账号将无法登录。`,
    danger: true,
    confirmText: '删除',
  })
  if (!ok) return
  try {
    await api.deleteTeacher(row.id)
    toast.ok('已删除', row.name)
    if (rows.value.length === 1 && page.value > 1) page.value -= 1
    await load()
  } catch (err) {
    toastError(err, '删除失败')
  }
}

function copy(text) {
  navigator.clipboard?.writeText(text).then(
    () => toast.info('已复制', text),
    () => toast.error('复制失败', '请手动选中复制'),
  )
}

function formatTime(value) {
  if (!value) return '从未登录'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

onMounted(() => {
  load()
  loadPermissions()
})
</script>

<template>
  <div>
    <PageHeader
      title="教师账号"
      description="管理员平台的唯一功能：创建教师账号、查看与维护教师权限。教师账号默认获得教师端全部能力。"
    >
      <template #actions>
        <button class="btn btn--ghost btn--sm" @click="openBatch">
          <Icon name="layers" :size="14" />批量添加
        </button>
        <button class="btn btn--primary btn--sm" @click="openCreate">
          <Icon name="plus" :size="14" />添加教师
        </button>
      </template>
    </PageHeader>

    <div class="row row--wrap" style="margin-bottom: 14px">
      <input
        v-model="keyword"
        class="input"
        style="width: 220px"
        placeholder="搜索工号 / 姓名 / 院系 / 手机号"
        @keyup.enter="page = 1; load()"
      />
      <select v-model="statusFilter" class="select" style="width: 130px" @change="page = 1; load()">
        <option value="">全部状态</option>
        <option value="ACTIVE">在用</option>
        <option value="DISABLED">已停用</option>
      </select>
      <button class="btn btn--ghost btn--sm" @click="page = 1; load()">
        <Icon name="search" :size="14" />查询
      </button>
      <div class="spacer" />
      <span class="small dim">共 {{ total }} 位教师</span>
    </div>

    <div class="card">
      <div v-if="loading" class="card__body"><div class="skeleton" style="height: 220px" /></div>
      <EmptyState
        v-else-if="!rows.length"
        icon="users"
        title="还没有教师账号"
        hint="创建教师账号后，教师即可登录教师端创建教学班、管理学生与成绩"
      >
        <button class="btn btn--primary btn--sm" style="margin-top: 10px" @click="openCreate">添加教师</button>
      </EmptyState>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>教师</th>
              <th>所属院系</th>
              <th>联系方式</th>
              <th>权限</th>
              <th>状态</th>
              <th>最近登录</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id">
              <td>
                <div class="row" style="gap: 8px">
                  <span class="avatar" style="width: 26px; height: 26px; font-size: 11px">{{ r.name?.slice(0, 1) }}</span>
                  <div>
                    <div class="table__primary">{{ r.name }}</div>
                    <div class="table__sub mono">{{ r.username }}</div>
                  </div>
                </div>
              </td>
              <td class="small">{{ r.department || '—' }}</td>
              <td class="small">
                <div>{{ r.phone || '—' }}</div>
                <div class="dim">{{ r.email || '' }}</div>
              </td>
              <td>
                <span class="pill pill--brand">
                  <Icon name="shield" :size="12" />教师权限包 · {{ r.permissions?.length || 0 }} 项
                </span>
              </td>
              <td><StatusPill :status="r.status" /></td>
              <td class="small dim">{{ formatTime(r.lastLoginAt) }}</td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 4px">
                  <button class="btn btn--icon btn--sm" title="编辑资料" @click="openEdit(r)">
                    <Icon name="edit" :size="14" />
                  </button>
                  <button class="btn btn--icon btn--sm" title="重置密码" @click="resetPassword(r)">
                    <Icon name="lock" :size="14" />
                  </button>
                  <button
                    class="btn btn--icon btn--sm"
                    :title="r.status === 'ACTIVE' ? '停用' : '启用'"
                    @click="toggleStatus(r)"
                  >
                    <Icon :name="r.status === 'ACTIVE' ? 'eye' : 'check'" :size="14" />
                  </button>
                  <button class="btn btn--icon btn--sm" title="删除" @click="remove(r)">
                    <Icon name="trash" :size="14" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager v-if="total > size" v-model:page="page" :size="size" :total="total" @update:page="load" />
    </div>

    <!-- 新增 / 编辑 -->
    <Modal v-if="formOpen" :title="isEditing ? '编辑教师资料' : '添加教师'" size="sm" @close="formOpen = false">
      <div style="display: grid; gap: 13px">
        <div class="field">
          <label>工号（登录账号）</label>
          <input
            v-model="form.username"
            class="input"
            :disabled="isEditing"
            placeholder="例如 T2026001"
          />
          <span v-if="isEditing" class="small dim">工号是登录账号，创建后不可修改</span>
        </div>
        <div class="field">
          <label>姓名</label>
          <input v-model="form.name" class="input" placeholder="例如 王老师" />
        </div>
        <div class="field">
          <label>所属院系 / 部门（选填）</label>
          <input v-model="form.department" class="input" placeholder="例如 计算机学院" />
        </div>
        <div class="field">
          <label>手机号（选填）</label>
          <input v-model="form.phone" class="input" placeholder="例如 13800000000" />
        </div>
        <div class="field">
          <label>邮箱（选填）</label>
          <input v-model="form.email" class="input" placeholder="teacher@sss.local" />
        </div>

        <div class="field">
          <label>权限配置</label>
          <div class="card" style="padding: 12px 14px">
            <div class="row" style="gap: 6px; font-weight: 600">
              <Icon name="shield" :size="15" />教师标准权限包
            </div>
            <div style="display: grid; gap: 7px; margin-top: 10px">
              <div v-for="p in permissions" :key="p.code" class="row" style="gap: 8px; align-items: flex-start">
                <Icon name="check" :size="13" style="color: var(--ok); flex: none; margin-top: 3px" />
                <div>
                  <div class="small" style="font-weight: 600">{{ p.name }}</div>
                  <div class="small dim">{{ p.description }}</div>
                </div>
              </div>
            </div>
            <p class="small dim" style="margin: 10px 0 0">
              系统按角色授权：教师账号创建后即获得以上全部教师端权限（等同学习通教师角色），无需逐项勾选。
            </p>
          </div>
        </div>

        <div v-if="!isEditing" class="alert alert--info">
          <Icon name="lock" :size="15" style="flex: none; margin-top: 2px" />
          新账号使用统一初始密码，教师在首次登录后应立即修改。
        </div>
        <div v-if="formError" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ formError }}
        </div>
      </div>
      <template #foot>
        <button class="btn btn--ghost" @click="formOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy" @click="submitForm">
          <span v-if="busy" class="spinner" />{{ isEditing ? '保存修改' : '创建教师账号' }}
        </button>
      </template>
    </Modal>

    <!-- 批量添加 -->
    <Modal v-if="batchOpen" title="批量添加教师" @close="batchOpen = false">
      <div style="display: grid; gap: 13px">
        <div class="alert alert--info">
          <Icon name="file" :size="15" style="flex: none; margin-top: 2px" />
          <div>
            每行一位教师，格式：<span class="mono">工号,姓名,院系,手机号,邮箱</span><br />
            后三项可留空，例如：<span class="mono">T2026001,王老师,计算机学院,13800000000,wang@sss.local</span>
          </div>
        </div>
        <textarea
          v-model="batchText"
          class="input"
          rows="8"
          style="resize: vertical; font-family: var(--mono); font-size: 12.5px"
          placeholder="T2026001,王老师,计算机学院,13800000000,wang@sss.local&#10;T2026002,李老师,软件学院"
        />

        <div v-if="batchResult" class="card" style="padding: 12px 14px">
          <div class="row small">
            <span>共 {{ batchResult.total }} 行</span>
            <span class="spacer" />
            <span class="pill pill--ok">成功 {{ batchResult.successCount }}</span>
            <span class="pill pill--danger">失败 {{ batchResult.failCount }}</span>
          </div>
          <div v-if="batchResult.failCount" style="display: grid; gap: 6px; margin-top: 10px; max-height: 180px; overflow: auto">
            <div v-for="item in batchResult.items.filter((i) => !i.success)" :key="item.row" class="small">
              <span class="mono dim">第 {{ item.row }} 行</span>
              <span style="margin-left: 8px">{{ item.username || '—' }}</span>
              <span class="pill pill--danger" style="margin-left: 8px">{{ item.message }}</span>
            </div>
          </div>
        </div>
      </div>
      <template #foot>
        <span class="small dim" style="margin-right: auto">已识别 {{ parsedBatchRows.length }} 行</span>
        <button class="btn btn--ghost" @click="batchOpen = false">关闭</button>
        <button class="btn btn--primary" :disabled="batchBusy || !parsedBatchRows.length" @click="submitBatch">
          <span v-if="batchBusy" class="spinner" />批量创建
        </button>
      </template>
    </Modal>

    <!-- 创建成功 / 重置密码结果 -->
    <Modal v-if="credential" :title="credential.title" size="sm" @close="credential = null">
      <dl class="dl" style="grid-template-columns: 76px 1fr">
        <dt>工号</dt>
        <dd class="mono">{{ credential.account }}</dd>
        <dt>初始密码</dt>
        <dd class="mono" style="font-size: 15px">{{ credential.password }}</dd>
      </dl>
      <p v-if="credential.hint" class="small dim" style="margin-top: 10px">{{ credential.hint }}</p>
      <template #foot>
        <button class="btn btn--ghost" @click="copy(credential.password)">复制密码</button>
        <button class="btn btn--primary" @click="credential = null">完成</button>
      </template>
    </Modal>
  </div>
</template>
