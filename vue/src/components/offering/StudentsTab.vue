<script setup>
import { onMounted, reactive, ref } from 'vue'
import Icon from '../Icon.vue'
import StatusPill from '../StatusPill.vue'
import EmptyState from '../EmptyState.vue'
import Modal from '../Modal.vue'
import Pager from '../Pager.vue'
import { api } from '../../api/endpoints'
import { confirm } from '../../stores/confirm'
import { toast, toastError } from '../../stores/toast'

const props = defineProps({
  offeringId: { type: String, required: true },
})

const loading = ref(true)
const rows = ref([])
const page = ref(1)
const size = 20
const total = ref(0)
const keyword = ref('')
const statusFilter = ref('')

const addOpen = ref(false)
const busy = ref(false)
const formError = ref('')
const form = reactive({ studentNo: '', name: '', className: '', email: '' })

const credential = ref(null) // { title, account, password, hint }

async function load() {
  loading.value = true
  try {
    const data = await api.students(props.offeringId, {
      page: page.value,
      size,
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
    })
    rows.value = data?.list || []
    total.value = data?.total || 0
  } catch (err) {
    toastError(err, '加载学生名单失败')
  } finally {
    loading.value = false
  }
}

function openAdd() {
  formError.value = ''
  Object.assign(form, { studentNo: '', name: '', className: '', email: '' })
  addOpen.value = true
}

async function submitAdd() {
  formError.value = ''
  if (!form.studentNo.trim() || !form.name.trim()) return (formError.value = '学号与姓名必填')
  busy.value = true
  try {
    const created = await api.addStudent(props.offeringId, {
      studentNo: form.studentNo.trim(),
      name: form.name.trim(),
      className: form.className || undefined,
      email: form.email || undefined,
    })
    addOpen.value = false
    if (created?.initialPassword) {
      credential.value = {
        title: '学生已加入',
        account: created.studentNo,
        password: created.initialPassword,
        hint: '首次创建的账号会给一次初始密码，请转告学生并及时修改。',
      }
    } else {
      toast.ok('已加入教学班', `${created?.name || form.name}`)
    }
    await load()
  } catch (err) {
    const fields = err?.data?.errors
    formError.value = Array.isArray(fields) && fields.length ? fields.map((f) => f.message).join('；') : err.message
  } finally {
    busy.value = false
  }
}

async function toggleStatus(row) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  try {
    await api.patchStudent(props.offeringId, row.studentId, { status: next })
    toast.ok(next === 'ACTIVE' ? '已启用' : '已停用', row.name)
    await load()
  } catch (err) {
    toastError(err, '状态更新失败')
  }
}

async function resetPassword(row) {
  if (!(await confirm({ title: '重置密码', message: `为「${row.name}」生成新的初始密码？该账号的旧令牌会立即失效。` }))) return
  try {
    const data = await api.resetStudentPassword(props.offeringId, row.studentId)
    credential.value = {
      title: '密码已重置',
      account: row.studentNo,
      password: data?.initialPassword,
      hint: data?.mustChangePassword ? '该账号下次登录需要修改密码。' : '',
    }
  } catch (err) {
    toastError(err, '重置失败')
  }
}

async function remove(row) {
  if (!(await confirm({ title: '移出教学班', message: `将「${row.name}」移出本教学班？已有选题记录可能阻止该操作。`, danger: true, confirmText: '移出' }))) return
  try {
    await api.deleteStudent(props.offeringId, row.studentId)
    toast.ok('已移出', row.name)
    await load()
  } catch (err) {
    toastError(err, '操作失败')
  }
}

function copy(text) {
  navigator.clipboard?.writeText(text).then(
    () => toast.info('已复制', text),
    () => toast.error('复制失败', '请手动选中复制'),
  )
}

onMounted(load)
</script>

<template>
  <div>
    <div class="row row--wrap" style="margin-bottom: 14px">
      <input v-model="keyword" class="input" style="width: 200px" placeholder="搜索学号 / 姓名" @keyup.enter="page = 1; load()" />
      <select v-model="statusFilter" class="select" style="width: 130px" @change="page = 1; load()">
        <option value="">全部状态</option>
        <option value="ACTIVE">在用</option>
        <option value="DISABLED">已停用</option>
      </select>
      <button class="btn btn--ghost btn--sm" @click="page = 1; load()"><Icon name="search" :size="14" />查询</button>
      <div class="spacer" />
      <span class="small dim">共 {{ total }} 人</span>
      <button class="btn btn--primary btn--sm" @click="openAdd"><Icon name="plus" :size="14" />添加学生</button>
    </div>

    <div class="card">
      <div v-if="loading" class="card__body"><div class="skeleton" style="height: 200px" /></div>
      <EmptyState v-else-if="!rows.length" icon="users" title="名单还是空的" hint="添加学生后才允许发布教学班">
        <button class="btn btn--primary btn--sm" style="margin-top: 10px" @click="openAdd">添加学生</button>
      </EmptyState>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>学生</th>
              <th>班级</th>
              <th>状态</th>
              <th class="ta-c">选题</th>
              <th class="ta-c">材料</th>
              <th class="ta-c">综合分</th>
              <th>最近登录</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.studentId">
              <td>
                <div class="row" style="gap: 8px">
                  <span class="avatar" style="width: 26px; height: 26px; font-size: 11px">{{ r.name?.slice(0, 1) }}</span>
                  <div>
                    <div class="table__primary">{{ r.name }}</div>
                    <div class="table__sub mono">{{ r.studentNo }}</div>
                  </div>
                </div>
              </td>
              <td class="small">{{ r.className || '—' }}</td>
              <td><StatusPill :status="r.status" /></td>
              <td class="ta-c num">{{ r.selectionCount }}</td>
              <td class="ta-c">
                <span class="pill" :class="r.submitted ? 'pill--ok' : ''">{{ r.submitted ? '已提交' : '未提交' }}</span>
              </td>
              <td class="ta-c num mono">{{ r.finalScore ?? '—' }}</td>
              <td class="small dim">{{ r.lastLoginAt ? new Date(r.lastLoginAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '从未登录' }}</td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 4px">
                  <button class="btn btn--icon btn--sm" title="重置密码" @click="resetPassword(r)"><Icon name="lock" :size="14" /></button>
                  <button class="btn btn--icon btn--sm" :title="r.status === 'ACTIVE' ? '停用' : '启用'" @click="toggleStatus(r)">
                    <Icon :name="r.status === 'ACTIVE' ? 'eye' : 'check'" :size="14" />
                  </button>
                  <button class="btn btn--icon btn--sm" title="移出教学班" @click="remove(r)"><Icon name="trash" :size="14" /></button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager v-if="total > size" v-model:page="page" :size="size" :total="total" @update:page="load" />
    </div>

    <Modal v-if="addOpen" title="添加学生" size="sm" @close="addOpen = false">
      <div style="display: grid; gap: 13px">
        <div class="field">
          <label>学号</label>
          <input v-model="form.studentNo" class="input" placeholder="2023010104" />
        </div>
        <div class="field">
          <label>姓名</label>
          <input v-model="form.name" class="input" placeholder="赵六" />
        </div>
        <div class="field">
          <label>班级（选填）</label>
          <input v-model="form.className" class="input" placeholder="计科2301" />
        </div>
        <div class="field">
          <label>邮箱（选填）</label>
          <input v-model="form.email" class="input" placeholder="student@sss.local" />
        </div>
        <div v-if="formError" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ formError }}
        </div>
        <div class="alert alert--info">
          <Icon name="lock" :size="15" style="flex: none; margin-top: 2px" />
          新账号会得到一次性初始密码，仅在创建成功时显示一次。
        </div>
      </div>
      <template #foot>
        <button class="btn btn--ghost" @click="addOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy" @click="submitAdd"><span v-if="busy" class="spinner" />加入教学班</button>
      </template>
    </Modal>

    <Modal v-if="credential" :title="credential.title" size="sm" @close="credential = null">
      <div class="alert alert--warn" style="margin-bottom: 14px">
        <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
        初始密码只显示这一次，关闭后无法再次查看（只能重置）。
      </div>
      <dl class="dl" style="grid-template-columns: 64px 1fr">
        <dt>账号</dt>
        <dd class="mono">{{ credential.account }}</dd>
        <dt>初始密码</dt>
        <dd class="mono" style="font-size: 15px">{{ credential.password }}</dd>
      </dl>
      <p v-if="credential.hint" class="small dim" style="margin-top: 10px">{{ credential.hint }}</p>
      <template #foot>
        <button class="btn btn--ghost" @click="copy(credential.password)">复制密码</button>
        <button class="btn btn--primary" @click="credential = null">我已记录</button>
      </template>
    </Modal>
  </div>
</template>
