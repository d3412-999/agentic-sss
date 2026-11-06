<template>
  <div>
    <div class="crumb">
      <RouterLink to="/teacher/offerings">我的开课</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <RouterLink :to="`/teacher/offerings/${offering.id}/defenses`">答辩复核</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <span>张三</span>
    </div>

    <div class="page-head">
      <div>
        <div class="row gap-10 wrap">
          <div class="page-title">张三 · 答辩详情</div>
          <StatusChip status="EVALUATED" />
          <span v-if="flags.length" class="chip red"><AppIcon name="alert" :size="12" /> 异常 {{ flags.length }}</span>
        </div>
        <div class="page-sub">2023010101 · 计科2301 · 答辩于 2026-10-21 19:15 · 用时 13:02</div>
      </div>
      <div class="page-actions">
        <button class="btn btn-outline" @click="markFlag">
          <AppIcon name="alert" :size="18" />
          标记异常
        </button>
        <button class="btn btn-primary" @click="approve">
          <AppIcon name="check" :size="18" />
          {{ reviewed ? '已完成复核' : '通过复核' }}
        </button>
      </div>
    </div>

    <div class="review-grid">
      <div class="col gap-16">
        <section class="card">
          <div class="card-pad">
            <div class="between mb-16">
              <div>
                <div class="card-title">答辩录音</div>
                <div class="card-desc">音轨已绑定学生身份，禁止外传</div>
              </div>
              <div class="row gap-6">
                <button class="chip outline">音频</button>
                <button class="chip">视频</button>
              </div>
            </div>

            <div class="player">
              <button class="play" @click="togglePlay">
                <AppIcon :name="playing ? 'pause' : 'play'" :size="22" />
              </button>
              <div class="grow">
                <div class="wave-line" @click="seek">
                  <span v-for="i in 64" :key="i" class="wave-bar" :class="{ played: i / 64 <= progress }" :style="{ height: (6 + ((i * 7) % 22)) + 'px' }" />
                </div>
                <div class="between mt-8 tiny dim">
                  <span class="mono">{{ currentTime }}</span>
                  <span class="mono">13:02</span>
                </div>
              </div>
              <div class="row gap-6">
                <button class="btn btn-sm btn-outline">1.0×</button>
                <button class="icon-btn" title="下载录音"><AppIcon name="download" :size="18" /></button>
              </div>
            </div>
          </div>
        </section>

        <section class="card">
          <div class="tabs" style="padding: 0 8px">
            <button v-for="t in tabs" :key="t.key" class="tab" :class="{ active: tab === t.key }" @click="tab = t.key">
              {{ t.label }}
            </button>
          </div>

          <div v-if="tab === 'transcript'" class="card-pad">
            <div v-for="seg in transcript" :key="seg.index" class="seg">
              <div class="between">
                <div class="row gap-8">
                  <span class="qp-index">{{ seg.index }}</span>
                  <span class="small strong">{{ seg.question }}</span>
                </div>
                <span class="tiny dim mono">{{ seg.start }} - {{ seg.end }}</span>
              </div>
              <p class="seg-answer">{{ seg.answer }}</p>
              <div class="row gap-8">
                <span class="chip">置信度 {{ seg.confidence }}</span>
                <span class="chip blue">单题得分 {{ seg.score }}</span>
              </div>
            </div>
          </div>

          <div v-else-if="tab === 'questions'" class="card-pad">
            <div v-for="q in questions" :key="q.id" class="q-block">
              <div class="row gap-8">
                <span class="qp-index">{{ q.order }}</span>
                <span class="chip outline">{{ q.type }}</span>
                <span class="tiny dim">{{ q.ref }}</span>
              </div>
              <p class="q-text">{{ q.question }}</p>
              <div class="q-points">
                <div class="tiny dim mb-6">评分要点</div>
                <div v-for="(p, i) in pointsOf(q)" :key="i" class="row gap-6 tiny muted">
                  <span class="bullet" />{{ p }}
                </div>
              </div>
            </div>
          </div>

          <div v-else class="card-pad">
            <div class="between mb-16">
              <span class="section-title">评分维度</span>
              <span class="chip green">自动评分 85.0</span>
            </div>
            <div class="col gap-14">
              <div v-for="d in result.dimensions" :key="d.key" class="dim-row">
                <span class="dim-name">{{ d.name }}</span>
                <div class="grow bar thin"><span :style="{ width: (d.score / d.weight) * 100 + '%' }" /></div>
                <span class="dim-val num">{{ d.score }}<i>/{{ d.weight }}</i></span>
              </div>
            </div>
            <hr class="divider" />
            <div class="small muted" style="line-height: 1.75">{{ result.summary }}</div>
          </div>
        </section>

        <section class="card">
          <div class="card-head">
            <div class="card-title">复核意见</div>
            <span class="chip" :class="reviewed ? 'green' : 'amber'">{{ reviewed ? '已复核' : '待复核' }}</span>
          </div>
          <div class="card-pad">
            <div class="row gap-6 wrap mb-12">
              <button v-for="t in quickTags" :key="t" class="chip outline" @click="appendTag(t)">{{ t }}</button>
            </div>
            <textarea
              v-model="reviewComment"
              class="textarea"
              placeholder="记录复核结论，例如：录音清晰，评分与作答一致；建议补充并发处理的追问。"
            />
            <div class="between mt-12">
              <span class="tiny dim">复核意见仅教师可见，写入审计日志</span>
              <div class="row gap-8">
                <button class="btn btn-sm btn-outline" @click="saveComment">保存意见</button>
                <button class="btn btn-sm btn-primary" @click="approve">{{ reviewed ? '已完成复核' : '通过复核' }}</button>
              </div>
            </div>
          </div>
        </section>
      </div>

      <div class="col gap-16">
        <section class="card card-pad">
          <div class="row gap-12">
            <span class="avatar lg">{{ student.name.slice(0, 1) }}</span>
            <div>
              <div class="strong">{{ student.name }}</div>
              <div class="tiny dim mono mt-2">{{ student.no }}</div>
            </div>
            <span class="chip green" style="margin-left: auto">已提交</span>
          </div>

          <hr class="divider" />

          <div class="col gap-10 small">
            <div class="between"><span class="muted">选题</span><span>基于 JavaFX 的图书管理系统</span></div>
            <div class="between"><span class="muted">源码</span><a class="link">bookstore-v2.zip · v2</a></div>
            <div class="between"><span class="muted">报告</span><a class="link">课程设计报告.pdf · 24 页</a></div>
            <div class="between"><span class="muted">源码得分</span><span class="num">81.5</span></div>
            <div class="between"><span class="muted">报告得分</span><span class="num">84.5</span></div>
          </div>

          <div class="row gap-8 mt-16">
            <RouterLink class="btn btn-sm btn-outline" to="/teacher/offerings/1839201930199990001/grades">查看成绩表</RouterLink>
            <button class="btn btn-sm btn-text">下载材料</button>
          </div>
        </section>

        <section class="card card-pad">
          <div class="between">
            <span class="section-title">答辩成绩</span>
            <span class="chip" :class="adjusted ? 'violet' : 'green'">{{ adjusted ? '教师调整' : '自动评分' }}</span>
          </div>

          <div class="score-big">
            <span class="num">{{ finalScore }}</span>
            <span class="dim small">/ 100</span>
          </div>

          <div class="col gap-10 mt-12">
            <div v-for="d in result.dimensions" :key="d.key" class="between small">
              <span class="muted">{{ d.name }}</span>
              <span class="num">{{ d.score }}</span>
            </div>
          </div>

          <hr class="divider" />

          <div class="field">
            <label class="label">调整答辩分数</label>
            <input v-model.number="manual" class="input" type="number" min="0" max="100" step="0.5" />
          </div>
          <div class="field mt-12">
            <label class="label">调整原因<span class="req">*</span></label>
            <textarea v-model="reason" class="textarea" style="min-height: 72px" placeholder="如：补充说明了并发方案，酌情加分" />
          </div>

          <button class="btn btn-primary btn-block mt-12" :disabled="!reason.trim()" @click="saveScore">
            保存调整
          </button>
          <div class="hint mt-8">调整会写入审计日志，发布成绩后不可再修改</div>
        </section>

        <section class="card card-pad">
          <div class="between">
            <span class="section-title">异常标记</span>
            <span class="chip" :class="flags.length ? 'red' : 'green'">{{ flags.length ? '待处理' : '无异常' }}</span>
          </div>
          <div v-if="flags.length" class="col gap-10 mt-12">
            <div v-for="f in flags" :key="f.type" class="flag">
              <div class="row gap-8">
                <AppIcon name="alert" :size="15" style="color: var(--c-red)" />
                <span class="small strong">{{ f.label }}</span>
                <span class="tiny dim mono">置信度 {{ f.confidence }}</span>
              </div>
              <p class="tiny muted mt-6">{{ f.desc }}</p>
              <div class="row gap-6 mt-10">
                <button class="btn btn-sm btn-outline" @click="dismissFlag(f)">忽略</button>
                <button class="btn btn-sm btn-danger" @click="acceptFlag(f)">采纳并标记</button>
              </div>
            </div>
          </div>
          <div v-else class="empty" style="padding: 24px 0">
            <span class="empty-icon" style="width: 40px; height: 40px"><AppIcon name="shield" :size="18" /></span>
            <div class="small">该答辩未发现异常</div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import StatusChip from '@/components/StatusChip.vue'
import { defenseQuestions, defenseResult, offering, transcript } from '@/data/mock'

const student = { name: '张三', no: '2023010101' }
const questions = defenseQuestions
const result = defenseResult

const tabs = [
  { key: 'transcript', label: '转写稿' },
  { key: 'questions', label: '题目与要点' },
  { key: 'scores', label: '评分明细' }
]
const tab = ref('transcript')

const playing = ref(false)
const progress = ref(0.18)
const durationSec = 13 * 60 + 2
let timer = null

const currentTime = computed(() => {
  const sec = Math.floor(progress.value * durationSec)
  return `${String(Math.floor(sec / 60)).padStart(2, '0')}:${String(sec % 60).padStart(2, '0')}`
})

function togglePlay() {
  playing.value = !playing.value
  if (playing.value) {
    timer = setInterval(() => {
      progress.value = Math.min(1, progress.value + 0.002)
      if (progress.value >= 1) {
        clearInterval(timer)
        playing.value = false
      }
    }, 250)
  } else {
    clearInterval(timer)
  }
}

function seek(e) {
  const rect = e.currentTarget.getBoundingClientRect()
  progress.value = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width))
}

onBeforeUnmount(() => clearInterval(timer))

const manual = ref(85)
const reason = ref('')
const adjusted = ref(false)
const reviewed = ref(false)
const reviewComment = ref('')
const quickTags = ['录音清晰', '评分与作答一致', '建议补充追问', '需人工复核']
const finalScore = computed(() => (adjusted.value ? manual.value : 85))

function appendTag(tag) {
  reviewComment.value = reviewComment.value ? `${reviewComment.value}；${tag}` : tag
}

function saveComment() {
  reviewed.value = true
}

const flags = ref([
  {
    type: 'SUSPECTED_READING',
    label: '疑似照读报告',
    confidence: 0.71,
    desc: '第 3 题回答与报告 5.2 节原文高度重合，语速平稳、无停顿，建议人工复核。'
  }
])

function saveScore() {
  adjusted.value = true
}

function approve() {
  reviewed.value = true
}

function markFlag() {}

function acceptFlag(f) {
  flags.value = flags.value.filter((x) => x !== f)
}

function dismissFlag(f) {
  flags.value = flags.value.filter((x) => x !== f)
}

function pointsOf(q) {
  return q.expectedPoints || ['概念准确', '结合代码细节', '能说明取舍']
}
</script>

<style scoped>
.review-grid { display: grid; grid-template-columns: minmax(0, 1.75fr) minmax(0, 1fr); gap: 16px; }

.player { display: flex; align-items: center; gap: 16px; }
.play {
  width: 52px; height: 52px; border-radius: 50%;
  border: none; background: var(--c-blue); color: #fff;
  display: grid; place-items: center; cursor: pointer; flex: none;
  transition: background .15s var(--ease), transform .1s var(--ease);
}
.play:hover { background: var(--c-blue-700); }
.play:active { transform: scale(.96); }

.wave-line { display: flex; align-items: center; gap: 2px; height: 40px; cursor: pointer; }
.wave-bar { width: 3px; border-radius: 2px; background: var(--c-grey-200); transition: background .2s linear; }
.wave-bar.played { background: var(--c-blue); }

.seg { padding: 14px 0; border-bottom: 1px dashed var(--c-line-2); }
.seg:last-child { border-bottom: none; }
.seg-answer { margin: 10px 0 10px 34px; font-size: 14px; line-height: 1.75; color: var(--c-text-2); }
.qp-index { width: 22px; height: 22px; border-radius: 50%; background: var(--c-blue-50); color: var(--c-blue-700); display: grid; place-items: center; font-size: 12.5px; font-weight: 600; flex: none; }

.q-block { padding: 14px 0; border-bottom: 1px dashed var(--c-line-2); }
.q-block:last-child { border-bottom: none; }
.q-text { margin: 10px 0; font-size: 14.5px; line-height: 1.7; }
.q-points { padding: 10px 12px; border-radius: 10px; background: var(--c-grey-50); }
.bullet { width: 5px; height: 5px; border-radius: 50%; background: var(--c-green); flex: none; }

.dim-row { display: flex; align-items: center; gap: 12px; }
.dim-name { width: 96px; font-size: 14px; color: var(--c-text-2); flex: none; }
.dim-val { width: 62px; text-align: right; font-size: 14px; }
.dim-val i { font-style: normal; font-size: 12px; color: var(--c-text-3); }

.score-big { display: flex; align-items: baseline; gap: 6px; margin-top: 12px; }
.score-big .num { font-size: 45px; font-weight: 500; letter-spacing: -1.5px; line-height: 1; }

.flag { padding: 12px; border-radius: 12px; background: var(--c-red-50); }

@media (max-width: 1080px) {
  .review-grid { grid-template-columns: 1fr; }
}
</style>
