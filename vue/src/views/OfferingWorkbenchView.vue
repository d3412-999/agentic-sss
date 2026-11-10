<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import StatusPill from '../components/StatusPill.vue'
import OverviewTab from '../components/offering/OverviewTab.vue'
import TopicsTab from '../components/offering/TopicsTab.vue'
import StudentsTab from '../components/offering/StudentsTab.vue'
import GradesTab from '../components/offering/GradesTab.vue'
import ExcellentWorksTab from '../components/offering/ExcellentWorksTab.vue'
import SettingsTab from '../components/offering/SettingsTab.vue'
import Modal from '../components/Modal.vue'
import { api } from '../api/endpoints'
import { confirm } from '../stores/confirm'
import { toast, toastError } from '../stores/toast'

const route = useRoute()
const router = useRouter()
const offeringId = computed(() => String(route.params.offeringId))

const loading = ref(true)
const offering = ref(null)
const dashboard = ref(null)
const tab = ref('overview')
const blockers = ref(null)

const TABS = [
  { key: 'overview', label: '概览', icon: 'home' },
  { key: 'topics', label: '题目', icon: 'file' },
  { key: 'students', label: '学生', icon: 'users' },
  { key: 'grades', label: '成绩', icon: 'chart' },
  { key: 'excellent', label: '优秀作品', icon: 'check' },
  { key: 'settings', label: '设置', icon: 'gear' },
]

async function load() {
  loading.value = true
  try {
    const [detail, dash] = await Promise.all([
      api.offering(offeringId.value),
      api.offeringDashboard(offeringId.value).catch(() => null),
    ])
    offering.value = detail
    dashboard.value = dash
  } catch (err) {
    toastError(err, '加载开课失败')
    router.push('/offerings')
  } finally {
    loading.value = false
  }
}

async function reloadDashboard() {
  try {
    dashboard.value = await api.offeringDashboard(offeringId.value)
  } catch {
    // 统计失败不影响其它页签
  }
}

async function act(action) {
  const conf = {
    publish: { fn: () => api.publishOffering(offeringId.value), ok: '已发布', need: true, msg: '发布会检查学生名单、已发布题目、时间线与权重，并给全体学生发送通知。' },
    close: { fn: () => api.closeOffering(offeringId.value), ok: '已关闭', need: true, msg: '关闭后学生不能再选题，已有选题仍可提交材料。' },
    archive: { fn: () => api.archiveOffering(offeringId.value), ok: '已归档', need: true, msg: '归档为最终态，仅用于学期结束后的留档。', danger: true },
  }[action]
  if (!conf) return
  if (conf.need && !(await confirm({ title: `${conf.ok}确认`, message: conf.msg, danger: conf.danger }))) return
  try {
    await conf.fn()
    toast.ok(conf.ok, offering.value?.courseName)
    blockers.value = null
    await load()
  } catch (err) {
    if (Array.isArray(err?.data?.blockers)) blockers.value = err.data.blockers
    else toastError(err, `${conf.ok}失败`)
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div style="min-width: 0">
        <div class="row small dim" style="margin-bottom: 6px">
          <RouterLink to="/offerings">教学班</RouterLink>
          <Icon name="chevron-right" :size="12" />
          <span class="truncate">{{ offering?.courseName || '开课工作台' }}</span>
        </div>
        <h1 class="row" style="gap: 10px">
          <span class="truncate">{{ offering?.courseName || '加载中…' }}</span>
          <StatusPill v-if="offering" :status="offering.status" />
        </h1>
        <p v-if="offering">
          {{ offering.semester }} · {{ offering.className }} ·
          {{ offering.stats?.studentCount ?? 0 }} 名学生 · {{ offering.stats?.topicCount ?? 0 }} 道题 ·
          已选 {{ offering.stats?.selectionCount ?? 0 }}
        </p>
      </div>
      <div class="page-head__actions">
        <button class="btn btn--ghost btn--sm" @click="tab = 'grades'"><Icon name="chart" :size="14" />成绩</button>
        <button v-if="offering?.status === 'DRAFT'" class="btn btn--primary btn--sm" @click="act('publish')">
          <Icon name="check" :size="14" />发布教学班
        </button>
        <button v-if="offering?.status === 'PUBLISHED'" class="btn btn--ghost btn--sm" @click="act('close')">关闭</button>
        <button
          v-if="offering && ['PUBLISHED', 'CLOSED'].includes(offering.status)"
          class="btn btn--ghost btn--sm"
          @click="act('archive')"
        >
          归档
        </button>
      </div>
    </div>

    <div class="tabs" style="margin-bottom: 16px">
      <button
        v-for="t in TABS"
        :key="t.key"
        class="tabs__item"
        :class="{ 'is-active': tab === t.key }"
        @click="tab = t.key"
      >
        <Icon :name="t.icon" :size="14" style="margin-right: 5px; vertical-align: -2px" />{{ t.label }}
      </button>
    </div>

    <OverviewTab v-if="tab === 'overview'" :data="dashboard" :offering="offering" :loading="loading" />
    <TopicsTab
      v-else-if="tab === 'topics'"
      :offering-id="offeringId"
      :offering="offering"
      @changed="reloadDashboard(); load()"
    />
    <StudentsTab v-else-if="tab === 'students'" :offering-id="offeringId" @changed="reloadDashboard()" />
    <GradesTab v-else-if="tab === 'grades'" :offering-id="offeringId" :offering="offering" />
    <ExcellentWorksTab v-else-if="tab === 'excellent'" :offering-id="offeringId" />
    <SettingsTab v-else-if="tab === 'settings' && offering" :offering="offering" @saved="load" />

    <Modal v-if="blockers" title="暂时无法发布" @close="blockers = null">
      <p class="muted small" style="margin-bottom: 12px">发布前还有以下检查项未通过：</p>
      <div style="display: grid; gap: 8px">
        <div v-for="b in blockers" :key="b.code" class="alert alert--warn">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
          <span><strong class="mono">{{ b.code }}</strong> · {{ b.message }}</span>
        </div>
      </div>
      <template #foot>
        <button class="btn btn--primary" @click="blockers = null">知道了</button>
      </template>
    </Modal>
  </div>
</template>
