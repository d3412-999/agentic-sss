<template>
  <div>
    <div class="crumb">
      <RouterLink to="/student/selections">我的选题</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <span class="ellipsis">{{ sel.topicTitle }}</span>
    </div>

    <div class="page-head">
      <div>
        <div class="row gap-10 wrap">
          <div class="page-title">{{ sel.topicTitle }}</div>
          <StatusChip :status="sel.status" />
        </div>
        <div class="page-sub">
          选题于 2026-09-12 · 提交截止 {{ offering.submitDeadline }} 23:59 · 指导：{{ offering.teacherName }}
        </div>
      </div>
      <div class="page-actions">
        <button class="btn btn-outline" @click="downloadAll">
          <AppIcon name="download" :size="18" />
          下载全部材料
        </button>
        <RouterLink class="btn btn-primary" :class="{ disabled: !canDefend }" :to="canDefend ? '/student/defense' : '#'">
          <AppIcon name="mic" :size="18" />
          进入答辩
        </RouterLink>
      </div>
    </div>

    <div class="detail-grid">
      <div class="col gap-16">
        <div class="card">
          <div class="tabs" style="padding: 0 8px">
            <button v-for="t in tabs" :key="t.key" class="tab" :class="{ active: tab === t.key }" @click="tab = t.key">
              {{ t.label }}
            </button>
            <div class="grow" />
          </div>

          <!-- 提交材料 -->
          <div v-if="tab === 'files'" class="card-pad col gap-20">
            <section>
              <div class="between mb-12">
                <div>
                  <div class="section-title">源代码</div>
                  <div class="section-sub">zip 压缩包，需包含完整工程（pom.xml / build.gradle 可选）</div>
                </div>
                <StatusChip v-if="source.uploaded" :status="source.status" />
                <span v-else class="chip amber">待提交</span>
              </div>

              <div v-if="source.uploaded" class="file-card">
                <span class="file-icon"><AppIcon name="file" :size="20" /></span>
                <div class="grow">
                  <div class="row gap-8">
                    <span class="strong">{{ source.name }}</span>
                    <span class="chip">v{{ source.version }}</span>
                  </div>
                  <div class="tiny dim mt-4">{{ source.size }} · 上传于 {{ source.time }} · SHA-256 已校验</div>
                  <div class="row gap-6 mt-8 wrap">
                    <span class="chip green">解析通过</span>
                    <span class="chip">42 个 Java 文件</span>
                    <span class="chip">5,120 行代码</span>
                    <span class="chip">Maven</span>
                  </div>
                </div>
                <div class="row gap-4">
                  <button class="btn btn-sm btn-text" @click="pick('source')">重新上传</button>
                  <button class="icon-btn" title="下载"><AppIcon name="download" :size="18" /></button>
                </div>
              </div>

              <div v-else class="dropzone" @click="pick('source')">
                <span class="file-icon"><AppIcon name="upload" :size="22" /></span>
                <div class="dz-title">点击或拖拽上传源码压缩包</div>
                <div class="dz-sub">支持 .zip，≤ 100 MB；上传后自动解析并触发静态分析</div>
              </div>
            </section>

            <hr class="divider" style="margin: 0" />

            <section>
              <div class="between mb-12">
                <div>
                  <div class="section-title">课程设计报告</div>
                  <div class="section-sub">PDF 或 DOCX，≤ 50 MB；系统自动提取章节结构用于评审</div>
                </div>
                <StatusChip v-if="report.uploaded" status="VALID" label="已提交" />
                <span v-else class="chip amber">待提交</span>
              </div>

              <div v-if="report.uploaded" class="file-card">
                <span class="file-icon report"><AppIcon name="file" :size="20" /></span>
                <div class="grow">
                  <div class="row gap-8">
                    <span class="strong">{{ report.name }}</span>
                    <span class="chip">v{{ report.version }}</span>
                  </div>
                  <div class="tiny dim mt-4">{{ report.size }} · 上传于 {{ report.time }} · 24 页</div>
                  <div class="row gap-6 mt-8 wrap">
                    <span class="chip green">结构识别完成</span>
                    <span class="chip">6 个一级章节</span>
                    <span class="chip">查重 8%</span>
                  </div>
                </div>
                <div class="row gap-4">
                  <button class="btn btn-sm btn-text" @click="pick('report')">重新上传</button>
                  <button class="icon-btn" title="下载"><AppIcon name="download" :size="18" /></button>
                </div>
              </div>

              <div v-else class="dropzone" @click="pick('report')">
                <span class="file-icon report"><AppIcon name="upload" :size="22" /></span>
                <div class="dz-title">点击或拖拽上传课程设计报告</div>
                <div class="dz-sub">支持 .pdf / .docx，≤ 50 MB</div>
              </div>
            </section>

            <input ref="fileInput" type="file" hidden @change="onFile" />
          </div>

          <!-- 源码分析 -->
          <div v-else-if="tab === 'code'" class="card-pad">
            <div v-if="!source.uploaded" class="empty">
              <span class="empty-icon"><AppIcon name="file" :size="24" /></span>
              <div class="empty-title">还没有可分析的源码</div>
              <div class="small">先上传源码压缩包，系统会自动运行 Checkstyle / PMD / SpotBugs</div>
            </div>
            <template v-else>
              <div class="score-head">
                <ProgressRing :value="code.score" :size="112" :stroke="9" />
                <div class="grow">
                  <div class="row gap-8">
                    <span class="section-title">源码质量分析</span>
                    <span class="chip green">{{ code.level }}</span>
                    <span class="chip">分析完成</span>
                  </div>
                  <p class="feedback mt-8">{{ code.summary }}</p>
                  <div class="row gap-16 mt-12 tiny dim">
                    <span>42 个文件</span>
                    <span>5,120 行</span>
                    <span>平均圈复杂度 {{ code.metrics.complexity }}</span>
                    <span>重复率 {{ code.metrics.duplication }}%</span>
                  </div>
                </div>
              </div>

              <hr class="divider" />

              <div class="col gap-12">
                <div v-for="d in code.dimensions" :key="d.key" class="dim-row">
                  <span class="dim-name">{{ d.name }}</span>
                  <div class="grow bar thin">
                    <span :style="{ width: (d.score / d.weight) * 100 + '%' }" />
                  </div>
                  <span class="dim-val num">{{ d.score }}<i>/{{ d.weight }}</i></span>
                </div>
              </div>

              <hr class="divider" />

              <div class="between mb-12">
                <span class="section-title">问题明细</span>
                <div class="row gap-6">
                  <span class="chip red">高 1</span>
                  <span class="chip amber">中 1</span>
                  <span class="chip">低 2</span>
                </div>
              </div>
              <div class="table-wrap">
                <table class="tbl">
                  <thead>
                    <tr><th>工具</th><th>问题</th><th>位置</th><th class="right">严重度</th></tr>
                  </thead>
                  <tbody>
                    <tr v-for="i in codeIssues" :key="i.id">
                      <td><span class="chip outline">{{ i.tool }}</span></td>
                      <td>
                        <div>{{ i.message }}</div>
                        <div class="tiny dim mt-4 mono">{{ i.rule }}</div>
                      </td>
                      <td class="mono tiny">{{ i.file }}:{{ i.line }}</td>
                      <td class="right"><StatusChip :status="i.severity" /></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
          </div>

          <!-- 报告评审 -->
          <div v-else class="card-pad">
            <div v-if="!report.uploaded" class="empty">
              <span class="empty-icon"><AppIcon name="file" :size="24" /></span>
              <div class="empty-title">还没有可评审的报告</div>
              <div class="small">上传 PDF / DOCX 后，系统会进行结构与内容评审</div>
            </div>
            <template v-else>
              <div class="score-head">
                <ProgressRing :value="reportEval.score" :size="112" :stroke="9" color="var(--c-green)" />
                <div class="grow">
                  <div class="row gap-8">
                    <span class="section-title">课程设计报告评审</span>
                    <span class="chip green">{{ reportEval.level }}</span>
                  </div>
                  <p class="feedback mt-8">{{ reportEval.summary }}</p>
                  <div class="row gap-16 mt-12 tiny dim">
                    <span>24 页</span>
                    <span>6 个章节</span>
                    <span>报告-源码一致性 78</span>
                    <span>查重 8%</span>
                  </div>
                </div>
              </div>

              <hr class="divider" />

              <div class="col gap-12">
                <div v-for="d in reportEval.dimensions" :key="d.key" class="dim-row">
                  <span class="dim-name">{{ d.name }}</span>
                  <div class="grow bar thin">
                    <span :style="{ width: (d.score / d.weight) * 100 + '%' }" />
                  </div>
                  <span class="dim-val num">{{ d.score }}<i>/{{ d.weight }}</i></span>
                </div>
              </div>

              <hr class="divider" />

              <div class="section-title mb-12">改进建议</div>
              <div class="col gap-10">
                <div v-for="(p, i) in problems" :key="i" class="problem">
                  <span class="problem-dot" />
                  <div>
                    <div class="small strong">{{ p.section }}</div>
                    <div class="small muted mt-4">{{ p.message }}</div>
                    <div class="tiny dim mt-4">建议：{{ p.suggestion }}</div>
                  </div>
                </div>
              </div>
            </template>
          </div>
        </div>

        <section class="card">
          <div class="card-head">
            <div class="card-title">提交历史</div>
            <span class="tiny dim">保留最近 5 个版本 · 评分取最新有效版本</span>
          </div>
          <div class="table-wrap">
            <table class="tbl">
              <thead>
                <tr>
                  <th>类型</th>
                  <th>版本</th>
                  <th>文件</th>
                  <th>大小</th>
                  <th>上传时间</th>
                  <th class="right">状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="h in history" :key="h.id">
                  <td><span class="chip" :class="h.type === '源码' ? 'blue' : 'green'">{{ h.type }}</span></td>
                  <td class="num">v{{ h.version }}</td>
                  <td class="mono small">{{ h.file }}</td>
                  <td class="dim small">{{ h.size }}</td>
                  <td class="dim small">{{ h.time }}</td>
                  <td class="right"><span class="chip" :class="h.tone">{{ h.label }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <div class="col gap-16">
        <section class="card card-pad">
          <div class="between">
            <span class="section-title">最终成绩</span>
            <StatusChip status="AUTO" label="待发布" />
          </div>

          <div class="final-row">
            <div class="final-score">{{ sel.finalScore ?? '—' }}</div>
            <div class="final-meta">
              <div class="chip green">{{ sel.finalScore ? '良好' : '未出分' }}</div>
              <div class="tiny dim mt-6">答辩 40% · 源码 35% · 报告 25%</div>
            </div>
          </div>

          <div class="score-line">
            <span class="muted">在线答辩</span>
            <span class="num strong">{{ sel.defenseScore ?? '—' }}</span>
          </div>
          <div class="score-line">
            <span class="muted">源码质量</span>
            <span class="num strong">{{ sel.codeScore ?? '—' }}</span>
          </div>
          <div class="score-line">
            <span class="muted">报告质量</span>
            <span class="num strong">{{ sel.reportScore ?? '—' }}</span>
          </div>
        </section>

        <section class="card card-pad">
          <div class="section-title mb-16">进度</div>
          <div class="progress-list">
            <div v-for="p in progress" :key="p.label" class="pl-item" :class="{ done: p.done, active: p.active }">
              <span class="pl-dot">
                <AppIcon v-if="p.done" name="check" :size="12" />
              </span>
              <div class="grow">
                <div class="pl-label">{{ p.label }}</div>
                <div class="tiny dim mt-2">{{ p.meta }}</div>
              </div>
            </div>
          </div>
        </section>

        <section class="card card-pad">
          <div class="row gap-8 strong">
            <AppIcon name="sparkle" :size="16" />
            答辩提示
          </div>
          <p class="small muted mt-8" style="line-height: 1.75">
            系统会根据你的题目、源码与报告生成 6 道答辩题，涵盖设计决策、代码细节与报告内容。答辩为语音形式，建议在安静环境提前测试麦克风。
          </p>
          <RouterLink class="btn btn-sm btn-tonal mt-12" to="/student/defense">查看答辩准备</RouterLink>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import StatusChip from '@/components/StatusChip.vue'
import ProgressRing from '@/components/ProgressRing.vue'
import { codeAnalysis, codeIssues, offering, reportEvaluation, selections } from '@/data/mock'

const route = useRoute()
const base = selections.find((s) => s.id === route.params.selectionId) || selections[0]

const tabs = [
  { key: 'files', label: '提交材料' },
  { key: 'code', label: '源码分析' },
  { key: 'report', label: '报告评审' }
]
const tab = ref('files')

const sel = reactive({ ...base })
const source = reactive({
  uploaded: base.sourceUploaded,
  status: 'ANALYZED',
  name: 'bookstore-v2.zip',
  version: base.sourceVersion || 1,
  size: '1.8 MB',
  time: '2026-10-10 21:02'
})
const report = reactive({
  uploaded: base.reportUploaded,
  name: '课程设计报告_张三.pdf',
  version: base.reportVersion || 1,
  size: '2.4 MB',
  time: '2026-10-10 21:10'
})

const code = codeAnalysis
const reportEval = reportEvaluation
const problems = [
  { section: '5.2 测试用例', message: '仅列出 3 条用例，未覆盖异常分支', suggestion: '补充边界值与异常场景，至少覆盖借阅失败与库存不足' },
  { section: '4.3 类图', message: '类图与最终代码不一致，缺少 BorrowService', suggestion: '按提交的源码重新生成类图' },
  { section: '6.1 总结', message: '缺少对设计取舍的反思', suggestion: '补充 2~3 条可改进点及原因' }
]

const history = [
  { id: 1, type: '源码', version: 2, file: 'bookstore-v2.zip', size: '1.8 MB', time: '2026-10-10 21:02', label: '已分析', tone: 'green' },
  { id: 2, type: '报告', version: 1, file: '课程设计报告_张三.pdf', size: '2.4 MB', time: '2026-10-10 21:10', label: '已评审', tone: 'green' },
  { id: 3, type: '源码', version: 1, file: 'bookstore-v1.zip', size: '1.6 MB', time: '2026-10-05 18:20', label: '已替换', tone: '' }
]

const fileInput = ref(null)
const picking = ref('source')

function pick(type) {
  picking.value = type
  fileInput.value?.click()
}

function onFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (picking.value === 'source') {
    source.uploaded = true
    source.name = file.name
    source.version += 1
    source.size = (file.size / 1024 / 1024).toFixed(1) + ' MB'
    source.time = new Date().toLocaleString('zh-CN', { hour12: false })
    source.status = 'ANALYZING'
    setTimeout(() => (source.status = 'ANALYZED'), 1600)
  } else {
    report.uploaded = true
    report.name = file.name
    report.version += 1
    report.size = (file.size / 1024 / 1024).toFixed(1) + ' MB'
    report.time = new Date().toLocaleString('zh-CN', { hour12: false })
  }
  e.target.value = ''
}

const canDefend = computed(() => source.uploaded && report.uploaded)

function downloadAll() {
  // 界面阶段仅作占位
}

const progress = computed(() => [
  { label: '选题完成', meta: '2026-09-12', done: true },
  { label: '源码已提交', meta: source.uploaded ? `v${source.version}` : '待提交', done: source.uploaded },
  { label: '报告已提交', meta: report.uploaded ? `v${report.version}` : '待提交', done: report.uploaded },
  { label: '源码分析完成', meta: source.uploaded ? '81.5 分' : '—', done: source.uploaded },
  { label: '报告评审完成', meta: report.uploaded ? '84.5 分' : '—', done: report.uploaded },
  { label: '在线答辩', meta: canDefend.value ? '可开始' : '材料齐全后开放', done: false, active: canDefend.value },
  { label: '成绩发布', meta: '待教师发布', done: false }
])
</script>

<style scoped>
.detail-grid { display: grid; grid-template-columns: minmax(0, 1.9fr) minmax(0, 1fr); gap: 16px; }

.file-card {
  display: flex; align-items: center; gap: 14px;
  padding: 16px;
  border: 1px solid var(--c-line-2);
  border-radius: 14px;
  transition: border-color .15s var(--ease), background .15s var(--ease);
}
.file-card:hover { border-color: var(--c-line); background: var(--c-grey-50); }

.file-icon {
  width: 44px; height: 44px; border-radius: 12px;
  display: grid; place-items: center;
  background: var(--c-blue-50); color: var(--c-blue-700);
  flex: none;
}
.file-icon.report { background: var(--c-green-50); color: var(--c-green-700); }

.score-head { display: flex; align-items: center; gap: 22px; }
.feedback { font-size: 14px; line-height: 1.7; color: var(--c-text-2); }

.dim-row { display: flex; align-items: center; gap: 14px; }
.dim-name { width: 104px; font-size: 14px; color: var(--c-text-2); flex: none; }
.dim-val { width: 64px; text-align: right; font-size: 14px; }
.dim-val i { font-style: normal; color: var(--c-text-3); font-size: 12px; }

.problem { display: flex; gap: 10px; padding: 12px 14px; border-radius: 12px; background: var(--c-grey-50); }
.problem-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--c-amber-700); margin-top: 6px; flex: none; }

.final-row { display: flex; align-items: flex-end; gap: 14px; margin: 18px 0 16px; }
.final-score { font-size: 53px; line-height: 1; font-weight: 500; letter-spacing: -2px; }
.final-meta { padding-bottom: 6px; }

.score-line { display: flex; align-items: center; justify-content: space-between; padding: 10px 0; border-top: 1px dashed var(--c-line-2); font-size: 14.5px; }

.progress-list { display: flex; flex-direction: column; gap: 14px; }
.pl-item { display: flex; gap: 10px; }
.pl-dot {
  width: 18px; height: 18px; border-radius: 50%;
  border: 1.5px solid var(--c-line);
  display: grid; place-items: center;
  color: #fff; flex: none; margin-top: 2px;
}
.pl-item.done .pl-dot { background: var(--c-green); border-color: var(--c-green); }
.pl-item.active .pl-dot { border-color: var(--c-blue); }
.pl-label { font-size: 14px; }
.pl-item:not(.done) .pl-label { color: var(--c-text-2); }

.disabled { opacity: .5; pointer-events: none; }

@media (max-width: 1080px) {
  .detail-grid { grid-template-columns: 1fr; }
  .score-head { flex-direction: column; align-items: flex-start; }
}
</style>
