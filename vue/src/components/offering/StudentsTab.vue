<script setup>
import { computed, onMounted, ref } from 'vue'
import Icon from '../Icon.vue'
import StatusPill from '../StatusPill.vue'
import EmptyState from '../EmptyState.vue'
import TabHead from '../layout/TabHead.vue'
import Pager from '../Pager.vue'
import StudentImportModal from './StudentImportModal.vue'
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

const importOpen = ref(false)
const exporting = ref(false)
const selected = ref([])
const removing = ref(false)

const allChecked = computed(() => rows.value.length > 0 && selected.value.length === rows.value.length)
const selectedCount = computed(() => selected.value.length)

async function load() {
  loading.value = true
  try {
    const data = await api.students(props.offeringId, {
      page: page.value,
      size,
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value || undefined,
    })
    rows.value = data?.list || []
    total.value = data?.total || 0
    // 换页 / 重新查询后清空勾选，避免「选了上一页的学生却在新一页点移除」
    selected.value = []
  } catch (err) {
    toastError(err, '加载学生名单失败')
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function openImport() {
  importOpen.value = true
}

function onImported() {
  page.value = 1
  load()
}

function toggle(row) {
  const id = row.studentId
  const index = selected.value.indexOf(id)
  if (index >= 0) selected.value.splice(index, 1)
  else selected.value.push(id)
}

function toggleAll() {
  selected.value = allChecked.value ? [] : rows.value.map((r) => r.studentId)
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
  const ok = await confirm({
    title: '重置密码',
    message: `把「${row.name}」的密码重置为初始密码（学号）？该账号已登录的会话会立即失效，下次登录需修改密码。`,
    confirmText: '重置',
  })
  if (!ok) return
  try {
    await api.resetStudentPassword(props.offeringId, row.studentId)
    toast.ok('已重置为初始密码', `请告知 ${row.name} 用学号作为密码登录并修改`)
  } catch (err) {
    toastError(err, '重置失败')
  }
}

async function removeOne(row) {
  const ok = await confirm({
    title: '移出教学班',
    message: `将「${row.name}」移出本教学班？该生的账号与其他教学班不受影响；已有提交物或成绩时无法移除。`,
    danger: true,
    confirmText: '移出',
  })
  if (!ok) return
  try {
    await api.deleteStudent(props.offeringId, row.studentId)
    toast.ok('已移出', row.name)
    await load()
  } catch (err) {
    toastError(err, '操作失败')
  }
}

async function removeSelected() {
  if (!selectedCount.value) return
  const ok = await confirm({
    title: '批量移出教学班',
    message: `将选中的 ${selectedCount.value} 位学生移出本教学班？只解除本班关联，不删除账号、不影响其他教学班；`
      + '已有提交物或成绩的学生会被单独跳过。',
    danger: true,
    confirmText: `移出 ${selectedCount.value} 人`,
  })
  if (!ok) return
  removing.value = true
  try {
    const data = await api.batchRemoveStudents(props.offeringId, selected.value)
    const failed = (data?.rows || []).filter((r) => !r.removed)
    if (failed.length) {
      toast.info(`已移出 ${data?.removed ?? 0} 人，${failed.length} 人未移出`, failed[0]?.message || '')
    } else {
      toast.ok(`已移出 ${data?.removed ?? 0} 人`)
    }
    await load()
  } catch (err) {
    toastError(err, '批量移除失败')
  } finally {
    removing.value = false
  }
}

async function exportRoster() {
  if (exporting.value) return
  exporting.value = true
  try {
    await api.exportStudents(props.offeringId, `学生名单-${props.offeringId}.csv`)
  } catch (err) {
    toastError(err, '下载名单失败')
  } finally {
    exporting.value = false
  }
}

function fmtLogin(value) {
  return value
    ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    : '从未登录'
}

onMounted(load)
</script>

<template>
  <div>
    <TabHead
      title="学生名单"
      description="先添加学生才能发布教学班；支持 Excel 批量导入与名单导出。"
      :count="total"
      count-unit=" 人"
    >
      <template #actions>
        <button class="btn btn--ghost btn--sm" :disabled="exporting" @click="exportRoster">
          <Icon name="download" :size="14" />下载名单
        </button>
        <button class="btn btn--primary btn--sm" @click="openImport"><Icon name="plus" :size="14" />添加学生</button>
      </template>
    </TabHead>

    <div class="row row--wrap" style="margin-bottom: 12px">
      <input
        v-model="keyword"
        class="input"
        style="width: 210px"
        placeholder="搜索学号 / 姓名"
        @keyup.enter="search"
      />
      <select v-model="statusFilter" class="select" style="width: 130px" @change="search">
        <option value="">全部状态</option>
        <option value="ACTIVE">在用</option>
        <option value="DISABLED">已停用</option>
      </select>
      <button class="btn btn--ghost btn--sm" @click="search"><Icon name="search" :size="14" />查询</button>
      <div class="spacer" />
      <!-- 批量操作只在有选中时出现，未选中不占位 -->
      <template v-if="selectedCount">
        <span class="small dim">已选 {{ selectedCount }} 人</span>
        <button class="btn btn--ghost btn--sm" :disabled="removing" @click="removeSelected">
          <Icon name="trash" :size="14" />移除学生
        </button>
      </template>
    </div>

    <div class="card">
      <div v-if="loading" class="card__body"><div class="skeleton" style="height: 200px" /></div>
      <EmptyState v-else-if="!rows.length" icon="users" title="名单还是空的" hint="添加学生后才允许发布教学班">
        <button class="btn btn--primary btn--sm" style="margin-top: 10px" @click="openImport">添加学生</button>
      </EmptyState>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 38px">
                <input type="checkbox" :checked="allChecked" :aria-label="allChecked ? '取消全选' : '全选'" @change="toggleAll" />
              </th>
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
            <tr v-for="r in rows" :key="r.studentId" :class="{ 'is-selected': selected.includes(r.studentId) }">
              <td>
                <input
                  type="checkbox"
                  :checked="selected.includes(r.studentId)"
                  :aria-label="`选择 ${r.name}`"
                  @change="toggle(r)"
                />
              </td>
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
              <td class="small dim">{{ fmtLogin(r.lastLoginAt) }}</td>
              <td class="ta-r">
                <div class="row" style="justify-content: flex-end; gap: 4px">
                  <button class="btn btn--icon btn--sm" title="重置密码" @click="resetPassword(r)"><Icon name="lock" :size="14" /></button>
                  <button class="btn btn--icon btn--sm" :title="r.status === 'ACTIVE' ? '停用' : '启用'" @click="toggleStatus(r)">
                    <Icon :name="r.status === 'ACTIVE' ? 'eye' : 'check'" :size="14" />
                  </button>
                  <button class="btn btn--icon btn--sm" title="移出教学班" @click="removeOne(r)"><Icon name="trash" :size="14" /></button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager v-if="total > size" v-model:page="page" :size="size" :total="total" @update:page="load" />
    </div>

    <StudentImportModal
      v-if="importOpen"
      :offering-id="offeringId"
      @close="importOpen = false"
      @imported="onImported"
    />
  </div>
</template>

<style scoped>
.table tbody tr.is-selected {
  background: var(--brand-ring, rgba(0, 0, 0, 0.04));
}
</style>
