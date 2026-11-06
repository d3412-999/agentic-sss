<template>
  <div>
    <div class="crumb">
      <RouterLink to="/teacher/offerings">我的开课</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <RouterLink :to="`/teacher/offerings/${offering.id}`">面向对象程序设计课程设计(Java)</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <span>成绩管理</span>
    </div>

    <div class="page-head">
      <div>
        <div class="page-title">成绩管理</div>
        <div class="page-sub">权重 答辩 40% · 源码 35% · 报告 25% · 已发布 {{ publishedCount }} / {{ rows.length }} 人</div>
      </div>
      <div class="page-actions">
        <button class="btn btn-outline" @click="recalc">
          <AppIcon name="refresh" :size="18" />
          重算成绩
        </button>
        <button class="btn btn-outline" @click="openExport">
          <AppIcon name="download" :size="18" />
          导出成绩
        </button>
        <button class="btn btn-primary" @click="publishOpen = true">
          <AppIcon name="send" :size="18" />
          发布成绩
        </button>
      </div>
    </div>


    <div class="grid g4">
      <div class="card card-pad mini"><span class="tiny dim">平均分</span><b>80.6</b><span class="tiny dim">较上次 +1.2</span></div>
      <div class="card card-pad mini"><span class="tiny dim">最高 / 最低</span><b>91.2 / 46.8</b><span class="tiny dim">2 人不及格</span></div>
      <div class="card card-pad mini"><span class="tiny dim">及格率</span><b>93%</b><div class="bar thin mt-8"><span style="width: 93%" /></div></div>
      <div class="card card-pad mini"><span class="tiny dim">待处理</span><b>12</b><span class="tiny dim">答辩未完成</span></div>
    </div>

    <div class="card mt-16">
      <div class="toolbar">
        <label class="search" style="width: 260px">
          <AppIcon name="search" :size="17" />
          <input v-model="keyword" placeholder="搜索姓名或学号" />
        </label>
        <div class="row gap-6">
          <button
            v-for="f in filters"
            :key="f.value"
            class="btn btn-sm"
            :class="status === f.value ? 'btn-tonal' : 'btn-text'"
            @click="status = f.value"
          >
            {{ f.label }}
          </button>
        </div>
      </div>

      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th style="min-width: 200px">学生</th>
              <th>选题</th>
              <th>答辩</th>
              <th>源码</th>
              <th>报告</th>
              <th>总评</th>
              <th>等级</th>
              <th>状态</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="g in visible" :key="g.id">
              <td>
                <div class="row gap-10">
                  <span class="avatar">{{ g.name.slice(0, 1) }}</span>
                  <div>
                    <div class="strong">{{ g.name }}</div>
                    <div class="tiny dim mono mt-2">{{ g.no }}</div>
                  </div>
                </div>
              </td>
              <td class="num">{{ g.topicCount }}</td>
              <td class="num">{{ g.defense || '—' }}</td>
              <td class="num">{{ g.code }}</td>
              <td class="num">{{ g.report || '—' }}</td>
              <td class="num strong">{{ g.final }}</td>
              <td>
                <span class="chip" :class="levelTone(g.level)">{{ g.level }}</span>
              </td>
              <td><StatusChip :status="g.status" /></td>
              <td class="right">
                <div class="row gap-4" style="justify-content: flex-end">
                  <button class="btn btn-sm btn-text" :disabled="g.status === 'LOCKED'" @click="openAdjust(g)">调整</button>
                  <button class="icon-btn" title="查看明细" @click="openAdjust(g)">
                    <AppIcon name="chevronRight" :size="17" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <ModalDialog v-model="adjustOpen" title="调整成绩" :subtitle="current ? `${current.name} · ${current.no}` : ''">
      <div v-if="current" class="col gap-16">
        <div class="grid g3">
          <div class="field">
            <label class="label">答辩</label>
            <input v-model.number="draft.defense" class="input" type="number" min="0" max="100" />
          </div>
          <div class="field">
            <label class="label">源码</label>
            <input v-model.number="draft.code" class="input" type="number" min="0" max="100" />
          </div>
          <div class="field">
            <label class="label">报告</label>
            <input v-model.number="draft.report" class="input" type="number" min="0" max="100" />
          </div>
        </div>

        <div class="preview">
          <div class="between">
            <span class="muted small">调整后总评</span>
            <span class="preview-score num">{{ previewFinal }}</span>
          </div>
          <div class="hint mt-4">按当前权重 40 / 35 / 25 计算，四舍五入保留 1 位小数</div>
        </div>

        <div class="field">
          <label class="label">调整原因<span class="req">*</span></label>
          <textarea v-model="draft.reason" class="textarea" placeholder="请填写调整依据，将写入审计日志" />
        </div>
      </div>
      <template #footer>
        <button class="btn btn-outline" @click="adjustOpen = false">取消</button>
        <button class="btn btn-primary" :disabled="!draft.reason.trim()" @click="saveAdjust">保存调整</button>
      </template>
    </ModalDialog>

    <ModalDialog v-model="publishOpen" title="发布成绩" subtitle="发布后学生即可查看，未完成答辩的学生可选择跳过">
      <div class="col gap-12">
        <div class="publish-row">
          <AppIcon name="users" :size="18" />
          <span class="grow">本次将发布 <b>{{ rows.length }}</b> 名学生成绩</span>
          <span class="chip amber">12 人未完成答辩</span>
        </div>
        <label class="check-row">
          <input v-model="allowIncomplete" type="checkbox" />
          <span>允许未完成答辩的学生按 0 分计入（否则仅发布已完成的学生）</span>
        </label>
        <div class="hint">发布后 24 小时内可撤销；再次修改分数需先撤销发布。</div>
      </div>
      <template #footer>
        <button class="btn btn-outline" @click="publishOpen = false">取消</button>
        <button class="btn btn-primary" @click="doPublish">确认发布</button>
      </template>
    </ModalDialog>

    <ModalDialog v-model="exportOpen" title="导出成绩" :subtitle="exporting ? '正在生成 Excel…' : '导出完成'">
      <div class="export">
        <div v-if="exporting" class="row gap-12">
          <span class="spinner" />
          <span class="small muted">正在汇总评分与评语，请稍候…</span>
        </div>
        <div v-else class="col gap-10">
          <div class="row gap-10">
            <AppIcon name="checkCircle" :size="20" style="color: var(--c-green)" />
            <span class="small">面向对象程序设计课程设计_计科2301_成绩单.xlsx</span>
          </div>
          <div class="hint">包含学号、姓名、答辩/源码/报告分、总评、等级、教师评语与发布时间。</div>
        </div>
      </div>
      <template #footer>
        <button class="btn btn-outline" @click="exportOpen = false">关闭</button>
        <button class="btn btn-primary" :disabled="exporting">下载</button>
      </template>
    </ModalDialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import StatusChip from '@/components/StatusChip.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import { grades, offering } from '@/data/mock'

const rows = ref(grades.map((g) => ({ ...g })))
const keyword = ref('')
const status = ref('all')
const adjustOpen = ref(false)
const publishOpen = ref(false)
const exportOpen = ref(false)
const exporting = ref(true)
const allowIncomplete = ref(false)
const current = ref(null)
const draft = reactive({ defense: 0, code: 0, report: 0, reason: '' })

const filters = [
  { value: 'all', label: '全部' },
  { value: 'AUTO', label: '自动评分' },
  { value: 'ADJUSTED', label: '已调整' },
  { value: 'LOCKED', label: '已发布' }
]

const publishedCount = computed(() => rows.value.filter((g) => g.status === 'LOCKED').length)

const visible = computed(() =>
  rows.value.filter((g) => {
    const kw = keyword.value.trim()
    const matchKw = !kw || g.name.includes(kw) || g.no.includes(kw)
    return matchKw && (status.value === 'all' || g.status === status.value)
  })
)

const previewFinal = computed(() =>
  (Number(draft.defense || 0) * 0.4 + Number(draft.code || 0) * 0.35 + Number(draft.report || 0) * 0.25).toFixed(1)
)

function levelTone(level) {
  return level === '优秀' ? 'green' : level === '良好' ? 'blue' : level === '中等' ? 'amber' : level === '不及格' ? 'red' : 'outline'
}

function openAdjust(g) {
  current.value = g
  Object.assign(draft, { defense: g.defense, code: g.code, report: g.report, reason: '' })
  adjustOpen.value = true
}

function saveAdjust() {
  const g = current.value
  g.defense = Number(draft.defense)
  g.code = Number(draft.code)
  g.report = Number(draft.report)
  g.final = Number(previewFinal.value)
  g.level = levelOfNumber(g.final)
  g.status = 'ADJUSTED'
  adjustOpen.value = false
}

function levelOfNumber(v) {
  if (v >= 90) return '优秀'
  if (v >= 80) return '良好'
  if (v >= 70) return '中等'
  if (v >= 60) return '及格'
  return '不及格'
}

function recalc() {}

function openExport() {
  exporting.value = true
  exportOpen.value = true
  setTimeout(() => (exporting.value = false), 1400)
}

function doPublish() {
  rows.value.forEach((g) => {
    if (g.status !== 'LOCKED' && (allowIncomplete.value || g.final > 0)) {
      g.status = 'LOCKED'
      g.published = true
    }
  })
  publishOpen.value = false
}
</script>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 16px; flex-wrap: wrap; }
.mini { display: flex; flex-direction: column; gap: 4px; }
.mini b { font-size: 25px; font-weight: 500; letter-spacing: -.8px; }

.preview { padding: 14px 16px; border-radius: 12px; background: var(--c-grey-50); }
.preview-score { font-size: 31px; font-weight: 500; letter-spacing: -1px; }
.publish-row { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-radius: 12px; background: var(--c-grey-50); font-size: 14.5px; }
.check-row { display: flex; gap: 10px; font-size: 14px; color: var(--c-text-2); line-height: 1.6; }
.check-row input { margin-top: 3px; accent-color: var(--c-blue); }

.spinner {
  width: 18px; height: 18px; border-radius: 50%;
  border: 2px solid var(--c-grey-200);
  border-top-color: var(--c-blue);
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
