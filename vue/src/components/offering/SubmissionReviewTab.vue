<script setup>
/**
 * 教师端「提交评审」页签 —— 严格只读。
 *
 * <p>左边学生列表，右边选中学生的提交物：按源码 / 报告分组，可切版本、看文件、
 * 预览、下载，命中的相似对会标红并显示对方学号与文件名。</p>
 *
 * <p><b>为什么这里没有删除 / 重评 / 评分按钮：</b>这个页面只回答「学生交了什么、像不像」。
 * 一旦开了写操作，就会和成绩、答辩状态机搅在一起，出问题时很难判断是哪一步改的。</p>
 */
import { computed, onMounted, ref } from 'vue'
import Icon from '../Icon.vue'
import Drawer from '../Drawer.vue'
import StatusPill from '../StatusPill.vue'
import EmptyState from '../EmptyState.vue'
import TabHead from '../layout/TabHead.vue'
import Pager from '../Pager.vue'
import FilePreviewModal from '../submission/FilePreviewModal.vue'
import { api } from '../../api/endpoints'
import { toastError } from '../../stores/toast'

const props = defineProps({
  offeringId: { type: String, required: true },
})

const loading = ref(true)
const students = ref([])
const total = ref(0)
const page = ref(1)
const size = 15
const keyword = ref('')

const selectedStudent = ref(null)
const selections = ref([])
const selectionLoading = ref(false)
const selectionError = ref('')

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const activeSelection = ref(null)
/** { SOURCE: detailVO, REPORT: detailVO } */
const details = ref({ SOURCE: null, REPORT: null })
/** { SOURCE: [versionVO], REPORT: [versionVO] } */
const versions = ref({ SOURCE: [], REPORT: [] })
/** 当前查看的版本：{ SOURCE: versionId|null, REPORT: versionId|null }，null = 最新 */
const activeVersion = ref({ SOURCE: null, REPORT: null })
const similarityMap = ref({})
const highlightThreshold = ref(0.85)

const previewOpen = ref(false)
const previewSubmissionId = ref('')
const previewFile = ref(null)

const groups = computed(() => [
  { type: 'SOURCE', label: '源码', icon: 'code', empty: '未提交源码' },
  { type: 'REPORT', label: '报告', icon: 'file', empty: '未提交报告' },
])

function sizeText(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`
}

function fmtDate(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—'
}

async function loadStudents() {
  loading.value = true
  try {
    const data = await api.students(props.offeringId, {
      page: page.value,
      size,
      keyword: keyword.value.trim() || undefined,
    })
    students.value = data?.list || []
    total.value = data?.total || 0
  } catch (err) {
    toastError(err, '加载学生名单失败')
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  loadStudents()
}

async function openStudent(row) {
  selectedStudent.value = row
  detailOpen.value = true
  selectionError.value = ''
  selections.value = []
  selectionLoading.value = true
  details.value = { SOURCE: null, REPORT: null }
  versions.value = { SOURCE: [], REPORT: [] }
  activeVersion.value = { SOURCE: null, REPORT: null }
  similarityMap.value = {}
  try {
    const list = await api.studentSelectionsOfOffering(props.offeringId, row.studentId)
    selections.value = list || []
    if (selections.value.length) {
      await loadSelection(selections.value[0])
    }
  } catch (err) {
    selectionError.value = err?.message || '读取该学生的选题失败'
  } finally {
    selectionLoading.value = false
  }
}

async function loadSelection(selection) {
  activeSelection.value = selection
  detailError.value = ''
  detailLoading.value = true
  try {
    const briefs = await api.teacherSubmissions(selection.id)
    const list = briefs?.list || []
    const nextDetails = { SOURCE: null, REPORT: null }
    const nextVersions = { SOURCE: [], REPORT: [] }
    const similar = {}
    for (const brief of list) {
      // 三个请求并发；相似度结果显式解构后再合并，不依赖 then 的副作用顺序
      const [detail, versionList, hits] = await Promise.all([
        api.submissionDetail(brief.id),
        api.submissionVersions(brief.id).catch(() => []),
        api.submissionSimilarities(brief.id).catch(() => []),
      ])
      mergeSimilarities(similar, hits)
      nextDetails[brief.type] = detail
      nextVersions[brief.type] = versionList || []
      if (brief.type === 'SOURCE' || brief.type === 'REPORT') {
        activeVersion.value[brief.type] = null
      }
    }
    details.value = nextDetails
    versions.value = nextVersions
    similarityMap.value = similar
  } catch (err) {
    detailError.value = err?.message || '读取提交材料失败'
  } finally {
    detailLoading.value = false
  }
}

/**
 * 双向记录合并：后端 A→B 与 B→A 各存一条，老师只需要看到「这两个文件像」。
 * 用 min+max 的 versionFileId 作唯一键，保留分数较高的一条。
 */
function mergeSimilarities(target, hits) {
  for (const hit of hits || []) {
    const a = String(hit.versionFileId)
    const b = String(hit.otherVersionFileId)
    const key = a < b ? `${a}|${b}` : `${b}|${a}`
    const exists = target[key]
    if (!exists || hit.score > exists.score) target[key] = hit
  }
  return target
}

/** 某个文件命中的相似记录（已去重、按分数倒序）。 */
function similaritiesOf(file) {
  const id = String(file.id)
  return Object.values(similarityMap.value)
    .filter((hit) => String(hit.versionFileId) === id || String(hit.otherVersionFileId) === id)
    .sort((a, b) => b.score - a.score)
}

function otherSide(hit, file) {
  const mineIsA = String(hit.versionFileId) === String(file.id)
  return {
    studentNo: hit.otherStudentNo || '—',
    name: hit.otherStudentName || '',
    fileName: mineIsA ? hit.otherFileName : hit.fileName,
  }
}

function kindText(kind) {
  return { IDENTICAL: '内容完全相同', SAME_STRUCTURE: '目录结构相同', CONTENT_OVERLAP: '内容高度重合' }[kind] || kind
}

async function switchVersion(type, versionId) {
  const detail = details.value[type]
  if (!detail) return
  activeVersion.value[type] = versionId || null
  detailLoading.value = true
  try {
    details.value[type] = await api.submissionDetail(detail.id, versionId || undefined)
  } catch (err) {
    toastError(err, '切换版本失败')
  } finally {
    detailLoading.value = false
  }
}

/** 分文件评审：parseResult.files 里每份的 score/summary/problems（可能缺，历史数据没有）。 */
function fileReview(detail, file) {
  const files = detail?.parseResult?.files
  if (!Array.isArray(files)) return null
  return files.find((item) => item.name === file.filename) || null
}

function openPreview(type, file) {
  const detail = details.value[type]
  if (!detail) return
  previewSubmissionId.value = detail.id
  previewFile.value = { id: file.id, filename: file.filename, contentType: file.contentType, size: file.size }
  previewOpen.value = true
}

function download(type, file) {
  const detail = details.value[type]
  if (!detail) return
  api.downloadSubmissionFile(detail.id, file.id, file.filename).catch((err) => toastError(err, '下载失败'))
}

async function loadPolicy() {
  try {
    const policy = await api.submissionPolicy()
    // 高亮阈值来自后端配置，不写死在模板里
    if (policy?.similarityHighlightThreshold != null) highlightThreshold.value = policy.similarityHighlightThreshold
  } catch {
    // 取不到就沿用默认值，不影响只读浏览
  }
}

onMounted(() => {
  loadStudents()
  loadPolicy()
})
</script>

<template>
  <div>
    <TabHead
      title="提交评审"
      description="只读查看全部学生的源码与报告、相似度比对与逐文件 AI 评审结果，不在这里评分。"
      :count="total"
      count-unit=" 人"
    />

    <div class="row row--wrap" style="margin-bottom: 12px">
      <input v-model="keyword" class="input" style="width: 220px" placeholder="搜索学号 / 姓名" @keyup.enter="search" />
      <button class="btn btn--ghost btn--sm" @click="search"><Icon name="search" :size="14" />查询</button>
      <div class="spacer" />
      <span class="small dim">选择左侧学生后查看其提交物</span>
    </div>

    <div class="card">
      <div v-if="loading" class="card__body"><div class="skeleton" style="height: 280px" /></div>
      <EmptyState v-else-if="!students.length" icon="users" title="没有可查看的学生" hint="先在教学班中添加学生名单" />
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>学生</th>
              <th>班级</th>
              <th>状态</th>
              <th class="ta-c">选题</th>
              <th class="ta-c">材料</th>
              <th class="ta-r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in students" :key="row.studentId" :class="{ 'is-active': selectedStudent?.studentId === row.studentId }">
              <td>
                <div class="row" style="gap: 8px">
                  <span class="avatar" style="width: 26px; height: 26px; font-size: 11px">{{ row.name?.slice(0, 1) }}</span>
                  <div>
                    <div class="table__primary">{{ row.name }}</div>
                    <div class="table__sub mono">{{ row.studentNo }}</div>
                  </div>
                </div>
              </td>
              <td class="small">{{ row.className || '—' }}</td>
              <td><StatusPill :status="row.status" /></td>
              <td class="ta-c num">{{ row.selectionCount }}</td>
              <td class="ta-c">
                <span class="pill" :class="row.submitted ? 'pill--ok' : ''">{{ row.submitted ? '已提交' : '未提交' }}</span>
              </td>
              <td class="ta-r">
                <button class="btn btn--ghost btn--sm" @click="openStudent(row)">
                  <Icon name="layers" :size="13" />查看材料
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager v-if="total > size" v-model:page="page" :size="size" :total="total" @update:page="loadStudents" />
    </div>

    <Drawer
      v-if="detailOpen"
      wide
      :title="selectedStudent ? `${selectedStudent.name}（${selectedStudent.studentNo}）的提交材料` : '提交材料'"
      @close="detailOpen = false"
    >
      <div v-if="selectionLoading" class="skeleton" style="height: 200px" />
      <div v-else-if="selectionError" class="alert alert--danger">
        <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
        <span>{{ selectionError }}</span>
      </div>
      <EmptyState v-else-if="!selections.length" icon="file" title="该生还没有选题" hint="没有选题就不会有提交物" />

      <div v-else>
        <div v-if="selections.length > 1" class="row row--wrap" style="margin-bottom: 12px">
          <button
            v-for="item in selections"
            :key="item.id"
            class="btn btn--sm"
            :class="activeSelection?.id === item.id ? 'btn--primary' : 'btn--ghost'"
            @click="loadSelection(item)"
          >
            {{ item.topic?.title || '未命名题目' }}
          </button>
        </div>

        <div v-if="detailError" class="alert alert--danger" style="margin-bottom: 12px">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
          <div style="min-width: 0">
            <div>{{ detailError }}</div>
            <button class="btn btn--ghost btn--sm" style="margin-top: 8px" @click="loadSelection(activeSelection)">重试</button>
          </div>
        </div>

        <div v-if="detailLoading" class="skeleton" style="height: 220px" />

        <div v-for="group in groups" :key="group.type" style="margin-bottom: 18px">
          <div class="row section-title" style="justify-content: space-between">
            <span><Icon :name="group.icon" :size="14" />{{ group.label }}</span>
            <select
              v-if="versions[group.type]?.length"
              class="select"
              style="width: 210px"
              :value="activeVersion[group.type] || ''"
              @change="switchVersion(group.type, $event.target.value)"
            >
              <option value="">最新版本 v{{ details[group.type]?.version }}</option>
              <option v-for="v in versions[group.type]" :key="v.versionId" :value="v.versionId">
                历史 v{{ v.version }} · {{ v.fileCount }} 个文件 · {{ fmtDate(v.createdAt) }}
              </option>
            </select>
          </div>

          <div v-if="!details[group.type]" class="card card--pad">
            <div class="small dim">{{ group.empty }}</div>
          </div>
          <div v-else class="card" style="overflow: hidden">
            <div class="list-row" style="align-items: center">
              <span class="pill" :class="group.type === 'SOURCE' ? 'pill--brand' : 'pill--info'">{{ group.label }}</span>
              <div style="min-width: 0; flex: 1">
                <div class="list-row__title">
                  v{{ details[group.type].version }} · {{ details[group.type].files?.length || 0 }} 个文件
                </div>
                <div class="list-row__meta">{{ fmtDate(details[group.type].createdAt) }}</div>
              </div>
              <StatusPill :status="details[group.type].status" />
            </div>
            <div style="padding: 0 12px 12px">
              <div v-for="file in details[group.type].files || []" :key="file.id" style="border-top: 1px solid var(--line); padding: 8px 0">
                <div class="row" style="gap: 8px; min-width: 0">
                  <span class="num small dim" style="width: 16px; text-align: right">{{ file.sortIndex + 1 }}</span>
                  <div style="min-width: 0; flex: 1">
                    <div class="list-row__title" style="font-size: 13px">{{ file.filename }}</div>
                    <div class="small dim num">{{ sizeText(file.size) }} · 排序值 {{ file.sortIndex }}</div>
                  </div>
                  <button class="btn btn--icon btn--sm" title="预览" @click="openPreview(group.type, file)">
                    <Icon name="eye" :size="13" />
                  </button>
                  <button class="btn btn--icon btn--sm" title="下载" @click="download(group.type, file)">
                    <Icon name="download" :size="13" />
                  </button>
                </div>

                <div
                  v-for="(hit, hi) in similaritiesOf(file)"
                  :key="hi"
                  class="alert"
                  :class="hit.score >= highlightThreshold ? 'alert--danger' : 'alert--warn'"
                  style="margin-top: 6px; padding: 6px 10px"
                >
                  <Icon name="alert" :size="14" style="flex: none; margin-top: 2px" />
                  <span class="small">
                    与 <strong>{{ otherSide(hit, file).studentNo }} {{ otherSide(hit, file).name }}</strong>
                    的《{{ otherSide(hit, file).fileName }}》相似度
                    <strong class="num">{{ (hit.score * 100).toFixed(0) }}%</strong>
                    （{{ kindText(hit.kind) }}）
                  </span>
                </div>

                <div v-if="fileReview(details[group.type], file)" class="small" style="margin-top: 6px; padding: 8px 10px; background: var(--surface-2); border-radius: 8px">
                  <div class="row" style="gap: 8px">
                    <strong>AI 分文件评审</strong>
                    <span v-if="fileReview(details[group.type], file).score != null" class="num">
                      得分 {{ fileReview(details[group.type], file).score }}
                    </span>
                    <span v-if="fileReview(details[group.type], file).degraded" class="pill pill--warn small">该份未完整评审</span>
                  </div>
                  <div class="dim" style="margin-top: 4px">{{ fileReview(details[group.type], file).summary || '（无摘要）' }}</div>
                  <div v-if="fileReview(details[group.type], file).reviewError" class="dim" style="margin-top: 2px">
                    原因：{{ fileReview(details[group.type], file).reviewError }}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Drawer>

    <FilePreviewModal
      v-if="previewOpen && previewFile"
      :submission-id="previewSubmissionId"
      :file="previewFile"
      @close="previewOpen = false"
    />
  </div>
</template>
