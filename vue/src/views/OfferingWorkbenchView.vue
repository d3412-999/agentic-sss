<script setup>
/**
 * 课程详情页。
 *
 * 结构：顶部课程上下文条（课程名 / 学期 / 班级 / 状态 / 关键操作）
 *      + 左侧二级目录（8 个分区）+ 右侧内容区。
 *
 * `?section=` 与目录选中态双向同步：刷新、前进后退、分享链接都能回到同一分区；
 * 非法值静默回退 `overview`，不报错也不 404。默认 `overview` 时不带 query，保持旧 URL 干净。
 *
 * 分区内容仍由原来的 8 个 Tab 组件承载，事件绑定与 props 未做任何改动。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import StatusPill from '../components/StatusPill.vue'
import OverviewTab from '../components/offering/OverviewTab.vue'
import TopicsTab from '../components/offering/TopicsTab.vue'
import StudentsTab from '../components/offering/StudentsTab.vue'
import SubmissionReviewTab from '../components/offering/SubmissionReviewTab.vue'
import GradesTab from '../components/offering/GradesTab.vue'
import DefenseTab from '../components/offering/DefenseTab.vue'
import ExcellentWorksTab from '../components/offering/ExcellentWorksTab.vue'
import SettingsTab from '../components/offering/SettingsTab.vue'
import Modal from '../components/Modal.vue'
import SubNav from '../components/layout/SubNav.vue'
import PageHeader from '../components/layout/PageHeader.vue'
import { api } from '../api/endpoints'
import { confirm } from '../stores/confirm'
import { toast, toastError } from '../stores/toast'

const route = useRoute()
const router = useRouter()
const offeringId = computed(() => String(route.params.offeringId))

const loading = ref(true)
const offering = ref(null)
const dashboard = ref(null)
const blockers = ref(null)

const SECTIONS = [
  { key: 'overview', label: '概览', icon: 'home' },
  { key: 'students', label: '学生', icon: 'users' },
  { key: 'topics', label: '题目', icon: 'file' },
  { key: 'submissions', label: '提交评审', icon: 'layers' },
  { key: 'grades', label: '成绩', icon: 'chart' },
  { key: 'defense', label: '答辩', icon: 'shield' },
  { key: 'excellent', label: '优秀作品', icon: 'check' },
  { key: 'settings', label: '设置', icon: 'gear' },
]

const SECTION_GROUPS = [
  { label: '教学准备', keys: ['overview', 'students', 'topics'] },
  { label: '教学执行', keys: ['submissions', 'grades', 'defense'] },
  { label: '课程沉淀', keys: ['excellent', 'settings'] },
]

const VALID_SECTIONS = new Set(SECTIONS.map((s) => s.key))
const DEFAULT_SECTION = 'overview'

const sectionGroups = computed(() =>
  SECTION_GROUPS.map((group) => ({
    label: group.label,
    items: group.keys.map((key) => SECTIONS.find((s) => s.key === key)).filter(Boolean),
  })),
)

/** 分区受控读写：默认值不留 query，切换用 replace 以免污染浏览器历史。 */
const section = computed({
  get() {
    const raw = String(route.query.section || '')
    return VALID_SECTIONS.has(raw) ? raw : DEFAULT_SECTION
  },
  set(value) {
    const next = { ...route.query }
    if (value === DEFAULT_SECTION) delete next.section
    else next.section = value
    router.replace({ query: next })
  },
})

const sectionLabel = computed(
  () => SECTIONS.find((s) => s.key === section.value)?.label || '概览',
)

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
    toastError(err, '加载课程失败')
    router.push('/offerings')
  } finally {
    loading.value = false
  }
}

async function reloadDashboard() {
  try {
    dashboard.value = await api.offeringDashboard(offeringId.value)
  } catch {
    // 统计失败不影响其它分区
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
    <!-- 课程上下文条：长表格滚动时仍能看到「我在哪个班的哪个分区」 -->
    <div class="course-bar">
      <PageHeader compact>
        <template #breadcrumb>
          <RouterLink to="/offerings">我的教学班</RouterLink>
          <Icon name="chevron-right" :size="12" />
          <span class="truncate">{{ offering?.courseName || '课程详情' }}</span>
          <Icon name="chevron-right" :size="12" />
          <span class="truncate">{{ sectionLabel }}</span>
        </template>
        <template #title>
          <span class="truncate">{{ offering?.courseName || '加载中…' }}</span>
          <StatusPill v-if="offering" :status="offering.status" />
        </template>
        <template v-if="offering" #meta>
          <span class="small muted truncate">
            {{ offering.semester }} · {{ offering.className }} ·
            {{ offering.stats?.studentCount ?? 0 }} 名学生 · {{ offering.stats?.topicCount ?? 0 }} 道题 ·
            已选 {{ offering.stats?.selectionCount ?? 0 }}
          </span>
        </template>
        <template #actions>
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
        </template>
      </PageHeader>
    </div>

    <!-- 左目录 + 右内容 -->
    <div class="page-split">
      <SubNav v-model="section" :groups="sectionGroups" />

      <div class="page-split__main">
        <OverviewTab v-if="section === 'overview'" :data="dashboard" :offering="offering" :loading="loading" />
        <TopicsTab
          v-else-if="section === 'topics'"
          :offering-id="offeringId"
          :offering="offering"
          @changed="reloadDashboard(); load()"
        />
        <StudentsTab v-else-if="section === 'students'" :offering-id="offeringId" @changed="reloadDashboard()" />
        <SubmissionReviewTab v-else-if="section === 'submissions'" :offering-id="offeringId" />
        <GradesTab v-else-if="section === 'grades'" :offering-id="offeringId" :offering="offering" />
        <DefenseTab v-else-if="section === 'defense'" :offering-id="offeringId" />
        <ExcellentWorksTab v-else-if="section === 'excellent'" :offering-id="offeringId" />
        <SettingsTab v-else-if="section === 'settings' && offering" :offering="offering" @saved="load" />
      </div>
    </div>

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

<style scoped>
/**
 * 课程上下文条吸顶。顶部偏移用 --topbar-h（与全局 topbar 同源），
 * z-index 低于 topbar(30)，保证浮层 / 下拉菜单仍然盖在它上面。
 */
.course-bar {
  position: sticky;
  top: var(--topbar-h);
  z-index: 20;
  margin: -12px 0 16px;
  padding: 10px 0 8px;
  background: var(--bg);
  border-bottom: 1px solid var(--line);
}

@media (max-width: 1023px) {
  .course-bar {
    position: static;
    margin-top: 0;
  }
}
</style>
