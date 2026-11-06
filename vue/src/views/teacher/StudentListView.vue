<template>
  <div>
    <div class="crumb">
      <RouterLink to="/teacher/offerings">我的开课</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <RouterLink :to="`/teacher/offerings/${offering.id}`">面向对象程序设计课程设计(Java)</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <span>学生名单</span>
    </div>

    <div class="page-head">
      <div>
        <div class="page-title">学生名单</div>
        <div class="page-sub">共 42 人 · 可按班级筛选，也可直接导入 Excel</div>
      </div>
      <div class="page-actions">
        <button class="btn btn-outline" @click="downloadTemplate">
          <AppIcon name="download" :size="18" />
          下载模板
        </button>
        <button class="btn btn-primary" @click="importOpen = true">
          <AppIcon name="upload" :size="18" />
          导入学生
        </button>
      </div>
    </div>


    <div class="card">
      <div class="toolbar">
        <div class="row gap-10 wrap">
          <label class="search" style="width: 260px">
            <AppIcon name="search" :size="17" />
            <input v-model="keyword" placeholder="搜索姓名、学号" />
          </label>
          <select v-model="classFilter" class="select" style="width: 150px; height: 40px">
            <option value="all">全部班级</option>
            <option v-for="c in classOptions" :key="c" :value="c">{{ c }}</option>
          </select>
          <select v-model="statusFilter" class="select" style="width: 140px; height: 40px">
            <option value="all">全部状态</option>
            <option value="ACTIVE">正常</option>
            <option value="DISABLED">已禁用</option>
          </select>
        </div>
        <div class="row gap-8">
          <span class="chip green">已提交 {{ submittedCount }}</span>
          <span class="chip amber">待提交 {{ list.length - submittedCount }}</span>
        </div>
      </div>

      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th style="min-width: 200px">学生</th>
              <th>班级</th>
              <th>选题</th>
              <th>材料</th>
              <th>答辩</th>
              <th>成绩</th>
              <th>状态</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in visible" :key="s.id">
              <td>
                <div class="row gap-10">
                  <span class="avatar" :class="s.status === 'DISABLED' ? '' : 'green'">{{ s.name.slice(0, 1) }}</span>
                  <div>
                    <div class="strong">{{ s.name }}</div>
                    <div class="tiny dim mono mt-2">{{ s.no }}</div>
                  </div>
                </div>
              </td>
              <td class="small">{{ s.cls }}</td>
              <td class="num">{{ s.selections }}</td>
              <td>
                <span class="chip" :class="s.submitted ? 'green' : 'amber'">{{ s.submitted ? '已提交' : '未提交' }}</span>
              </td>
              <td><StatusChip :status="s.defense" /></td>
              <td class="num">{{ s.score ?? '—' }}</td>
              <td>
                <span class="chip" :class="s.status === 'ACTIVE' ? '' : 'red'">{{ s.status === 'ACTIVE' ? '正常' : '已禁用' }}</span>
              </td>
              <td class="right">
                <div class="row gap-4" style="justify-content: flex-end">
                  <button class="btn btn-sm btn-text" @click="resetPwd(s)">重置密码</button>
                  <button class="btn btn-sm btn-text" @click="toggleStatus(s)">
                    {{ s.status === 'ACTIVE' ? '禁用' : '启用' }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <ModalDialog v-model="pwdOpen" title="重置密码" :subtitle="pwdTarget ? `${pwdTarget.name} · ${pwdTarget.no}` : ''">
      <div class="col gap-12">
        <div class="pwd-box">
          <span class="tiny dim">新的初始密码</span>
          <b class="mono">{{ newPassword }}</b>
        </div>
        <div class="hint">请将新密码告知学生，首次登录后系统会要求修改密码。旧密码立即失效。</div>
      </div>
      <template #footer>
        <button class="btn btn-outline" @click="pwdOpen = false">关闭</button>
        <button class="btn btn-primary" @click="pwdOpen = false">已复制并告知</button>
      </template>
    </ModalDialog>

    <ModalDialog v-model="importOpen" title="导入学生名单" subtitle="支持 .xlsx，单次最多 2000 行">
      <template v-if="!importResult">
        <div class="dropzone" @click="fakePick">
          <span class="file-icon"><AppIcon name="upload" :size="22" /></span>
          <div class="dz-title">{{ fileName || '点击选择 Excel 文件' }}</div>
          <div class="dz-sub">列：学号*、姓名*、班级、邮箱</div>
        </div>

        <div class="col gap-10 mt-16">
          <div class="label">重复学号处理</div>
          <label class="radio"><input v-model="strategy" type="radio" value="SKIP" /> 跳过已存在的学生</label>
          <label class="radio"><input v-model="strategy" type="radio" value="UPDATE" /> 覆盖更新班级与邮箱</label>
        </div>

        <div class="hint mt-16">
          导入后系统会为首次出现的学生创建账号，初始密码默认为学号后 6 位，首次登录需修改。
        </div>
      </template>

      <template v-else>
        <div class="result">
          <div class="between">
            <span class="section-title">导入完成</span>
            <span class="chip green">成功 40</span>
          </div>
          <div class="grid g3 mt-16">
            <div class="res-box"><b>40</b><span>新增</span></div>
            <div class="res-box"><b>1</b><span>跳过</span></div>
            <div class="res-box"><b>1</b><span>失败</span></div>
          </div>
          <div class="err mt-16">
            <div class="small strong">失败明细</div>
            <div class="tiny muted mt-6">第 17 行 · 2023010117 · 学号已存在于其他教学班</div>
          </div>
        </div>
      </template>

      <template #footer>
        <button class="btn btn-outline" @click="importOpen = false">{{ importResult ? '关闭' : '取消' }}</button>
        <button v-if="!importResult" class="btn btn-primary" @click="doImport">开始导入</button>
      </template>
    </ModalDialog>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import StatusChip from '@/components/StatusChip.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import { offering, students } from '@/data/mock'

const keyword = ref('')
const statusFilter = ref('all')
const classFilter = ref('all')
const importOpen = ref(false)
const importResult = ref(false)
const fileName = ref('')
const strategy = ref('SKIP')

const list = ref(students.map((s) => ({ ...s })))
const pwdOpen = ref(false)
const pwdTarget = ref(null)
const newPassword = ref('')

const classOptions = computed(() => [...new Set(list.value.map((s) => s.cls))])
const submittedCount = computed(() => list.value.filter((s) => s.submitted).length)

const visible = computed(() =>
  list.value.filter((s) => {
    const kw = keyword.value.trim()
    const matchKw = !kw || s.name.includes(kw) || s.no.includes(kw)
    const matchClass = classFilter.value === 'all' || s.cls === classFilter.value
    const matchStatus = statusFilter.value === 'all' || s.status === statusFilter.value
    return matchKw && matchClass && matchStatus
  })
)

function downloadTemplate() {}

function fakePick() {
  fileName.value = '计科2301_学生名单.xlsx'
}

function doImport() {
  importResult.value = true
}

function resetPwd(s) {
  pwdTarget.value = s
  newPassword.value = String(Math.floor(100000 + Math.random() * 900000))
  pwdOpen.value = true
}

function toggleStatus(s) {
  s.status = s.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
}
</script>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 16px; flex-wrap: wrap; }
.file-icon { width: 44px; height: 44px; border-radius: 12px; display: grid; place-items: center; background: var(--c-blue-50); color: var(--c-blue-700); }
.radio { display: flex; align-items: center; gap: 8px; font-size: 14.5px; color: var(--c-text-2); cursor: pointer; }
.radio input { accent-color: var(--c-blue); }

.result .res-box {
  display: flex; flex-direction: column; gap: 4px;
  padding: 14px; border-radius: 12px; background: var(--c-grey-50);
}
.result .res-box b { font-size: 23px; font-weight: 500; }
.result .res-box span { font-size: 13px; color: var(--c-text-3); }
.err { padding: 12px 14px; border-radius: 12px; background: var(--c-red-50); color: var(--c-red-700); }

.pwd-box {
  display: flex; flex-direction: column; gap: 6px;
  padding: 16px;
  border-radius: 12px;
  background: var(--c-blue-50);
  text-align: center;
}
.pwd-box b { font-size: 27px; letter-spacing: 4px; color: var(--c-blue-700); }
</style>
