<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-title">在线答辩</div>
        <div class="page-sub">面向对象程序设计课程设计(Java) · 答辩窗口 2026-10-20 ~ 10-30</div>
      </div>
      <div class="page-actions">
        <span class="chip" :class="state === 'done' ? 'green' : state === 'live' ? 'amber' : 'blue'">
          <span class="dot" />
          {{ state === 'done' ? '已完成' : state === 'live' ? '答辩进行中' : '待开始' }}
        </span>
      </div>
    </div>

    <!-- 准备阶段 -->
    <div v-if="state === 'ready'" class="ready-wrap">
      <section class="card ready-card">
        <div class="ready-icon"><AppIcon name="mic" :size="30" /></div>
        <h2 class="ready-title">准备开始答辩</h2>
        <p class="ready-sub">
          开始后系统会根据你的题目、源码与报告生成答辩题目，开始前不显示题目内容。
        </p>

        <div class="device-box">
          <div class="row gap-12">
            <span class="device-dot" :class="{ ok: micTested }" />
            <div class="grow">
              <div class="small strong">{{ micTested ? '麦克风已就绪' : '麦克风未检测' }}</div>
              <div class="tiny dim mt-2">
                {{ micTested ? '输入音量正常 · 信噪比良好' : '请先检测麦克风，确保答辩录音正常' }}
              </div>
            </div>
            <button class="btn btn-outline" :disabled="testing" @click="testDevice">
              {{ testing ? '检测中…' : micTested ? '重新检测' : '检测设备' }}
            </button>
          </div>
        </div>

        <button
          class="btn btn-lg btn-block start-btn"
          :class="canStart ? 'btn-primary' : 'btn-disabled'"
          :disabled="!canStart"
          @click="start"
        >
          <AppIcon name="mic" :size="20" />
          开始答辩
        </button>

        <div class="ready-hint" :class="{ warn: !hasWork }">
          <template v-if="!hasWork">尚未提交源码与课程设计报告，暂不能开始答辩</template>
          <template v-else-if="!micTested">请先完成设备检测</template>
          <template v-else>开始后计时不可暂停，请保持页面在前台</template>
        </div>

        <div class="ready-meta">
          <span>6 道题</span>
          <i>·</i>
          <span>每题 3 分钟</span>
          <i>·</i>
          <span>全程录音</span>
        </div>
      </section>
    </div>

    <!-- 进行中 -->
    <div v-else-if="state === 'live'" class="live-grid">
      <section class="card live-main">
        <div class="card-pad">
          <div class="between">
            <div class="row gap-8">
              <span class="live-dot" />
              <span class="small strong">正在作答</span>
              <span class="chip outline">{{ currentIndex + 1 }} / {{ questions.length }}</span>
            </div>
            <div class="timer" :class="{ warn: left <= 30 }">{{ mmss }}</div>
          </div>

          <div class="question-box">
            <div class="row gap-6 mb-12">
              <span class="chip blue">{{ typeLabel(currentQuestion.type) }}</span>
              <span class="chip">{{ diffLabel(currentQuestion.difficulty) }}</span>
              <span class="tiny dim">{{ currentQuestion.ref }}</span>
            </div>
            <p class="question-text">{{ currentQuestion.question }}</p>
          </div>

          <div class="mic-zone">
            <div class="wave">
              <span v-for="i in 28" :key="i" :style="{ animationDelay: (i * 0.07) + 's', height: (8 + (i * 13) % 26) + 'px' }" />
            </div>
            <div class="mic-hint">正在聆听… 请用语音回答</div>
          </div>

          <div class="transcript">
            <div class="between mb-8">
              <span class="tiny dim">实时转写</span>
              <span class="tiny dim">置信度 0.93</span>
            </div>
            <p class="transcript-text">
              当时考虑到借阅是一个整体流程，放在同一个服务里可以保证事务边界清晰<span class="cursor">|</span>
            </p>
          </div>

          <div class="between mt-20">
            <button class="btn btn-text" @click="finish">结束答辩</button>
            <button class="btn btn-primary" @click="next">
              {{ currentIndex === questions.length - 1 ? '完成答辩' : '结束本题，下一题' }}
              <AppIcon name="arrowRight" :size="17" />
            </button>
          </div>
        </div>
      </section>

      <aside class="col gap-16">
        <section class="card card-pad">
          <div class="section-title mb-12">题目进度</div>
          <div class="q-progress">
            <div
              v-for="(q, i) in questions"
              :key="q.id"
              class="q-dot"
              :class="{ done: i < currentIndex, active: i === currentIndex }"
            >
              {{ i + 1 }}
            </div>
          </div>
          <div class="hint mt-12">已完成 {{ currentIndex }} 题，剩余 {{ questions.length - currentIndex }} 题</div>
        </section>

        <section class="card card-pad">
          <div class="row gap-8 strong"><AppIcon name="shield" :size="16" /> 作答提示</div>
          <p class="small muted mt-8" style="line-height: 1.75">
            回答时尽量结合你实际写过的代码与设计取舍，系统会对照源码与报告判断内容一致性。
          </p>
        </section>
      </aside>
    </div>

    <!-- 完成 -->
    <div v-else class="grid g2">
      <section class="card">
        <div class="card-head">
          <div>
            <div class="card-title">答辩已完成</div>
            <div class="card-desc">评分由系统自动生成，教师复核后发布</div>
          </div>
          <StatusChip status="EVALUATED" />
        </div>
        <div class="card-pad">
          <div class="result-head">
            <ProgressRing :value="result.score" :size="120" :stroke="9" />
            <div>
              <div class="row gap-8">
                <span class="page-title" style="font-size: 23px">{{ result.level }}</span>
                <span class="chip green">自动评分</span>
              </div>
              <p class="small muted mt-8" style="max-width: 420px; line-height: 1.7">{{ result.summary }}</p>
              <div class="row gap-16 mt-12 tiny dim">
                <span>用时 13:02</span><span>6 道题</span><span>转写置信度 0.92</span>
              </div>
            </div>
          </div>

          <hr class="divider" />

          <div class="col gap-12">
            <div v-for="d in result.dimensions" :key="d.key" class="dim-row">
              <span class="dim-name">{{ d.name }}</span>
              <div class="grow bar thin"><span :style="{ width: (d.score / d.weight) * 100 + '%' }" /></div>
              <span class="dim-val num">{{ d.score }}<i>/{{ d.weight }}</i></span>
            </div>
          </div>
        </div>
      </section>

      <div class="col gap-16">
        <section class="card">
          <div class="card-head"><div class="card-title">逐题得分</div></div>
          <div class="card-pad col gap-4">
            <div v-for="(t, i) in transcript" :key="t.index" class="qa-row">
              <span class="qp-index">{{ i + 1 }}</span>
              <div class="grow">
                <div class="small strong ellipsis">{{ t.question }}</div>
                <div class="tiny dim mt-4">{{ t.start }} - {{ t.end }} · 置信度 {{ t.confidence }}</div>
              </div>
              <span class="num strong">{{ t.score }}</span>
            </div>
          </div>
        </section>

        <section class="card card-pad">
          <div class="between">
            <span class="section-title">接下来</span>
            <span class="chip amber">待教师复核</span>
          </div>
          <p class="small muted mt-8" style="line-height: 1.7">
            教师复核后可查看最终成绩。若对评分有疑问，可在成绩发布后向教师发起申诉。
          </p>
          <div class="row gap-8 mt-12">
            <RouterLink class="btn btn-sm btn-tonal" to="/student/grades">查看成绩</RouterLink>
            <RouterLink class="btn btn-sm btn-outline" to="/student/selections">返回我的选题</RouterLink>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import ProgressRing from '@/components/ProgressRing.vue'
import StatusChip from '@/components/StatusChip.vue'
import { defenseQuestions, defenseResult, selections, transcript } from '@/data/mock'

const state = ref('ready')
const micTested = ref(false)
const testing = ref(false)
const currentIndex = ref(0)
const left = ref(180)
const questions = defenseQuestions
const result = defenseResult

// 至少有一道题同时提交了源码与报告，才允许开始答辩
const hasWork = computed(() =>
  selections.some((s) => s.status !== 'CANCELED' && s.sourceUploaded && s.reportUploaded)
)
const canStart = computed(() => hasWork.value && micTested.value)

let timer = null

function testDevice() {
  testing.value = true
  setTimeout(() => {
    testing.value = false
    micTested.value = true
  }, 900)
}

const currentQuestion = computed(() => questions[currentIndex.value] || questions[0])

const mmss = computed(() => {
  const m = String(Math.floor(left.value / 60)).padStart(2, '0')
  const s = String(left.value % 60).padStart(2, '0')
  return `${m}:${s}`
})

function typeLabel(t) {
  return { DESIGN: '设计决策', CODE: '代码细节', REPORT: '报告内容', CONCEPT: '课程知识', EXTENSION: '扩展思考' }[t] || t
}
function diffLabel(d) {
  return { EASY: '简单', MEDIUM: '中等', HARD: '较难' }[d] || d
}

function start() {
  state.value = 'live'
  currentIndex.value = 0
  left.value = 180
  timer = setInterval(() => {
    if (left.value > 0) left.value -= 1
  }, 1000)
}

function stopTimer() {
  if (timer) clearInterval(timer)
  timer = null
}

function next() {
  if (currentIndex.value >= questions.length - 1) {
    finish()
    return
  }
  currentIndex.value += 1
  left.value = questions[currentIndex.value].seconds
}

function finish() {
  stopTimer()
  state.value = 'done'
}

onBeforeUnmount(stopTimer)
</script>

<style scoped>
.check-list { display: flex; flex-direction: column; gap: 10px; }
.check-item { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border-radius: 12px; background: var(--c-grey-50); }
.check-icon { width: 24px; height: 24px; border-radius: 50%; display: grid; place-items: center; background: var(--c-amber-50); color: var(--c-amber-700); flex: none; }
.check-icon.ok { background: var(--c-green-50); color: var(--c-green-700); }

.question-preview { display: flex; flex-direction: column; gap: 2px; }
.qp-item { display: flex; gap: 12px; padding: 12px 4px; border-bottom: 1px dashed var(--c-line-2); }
.qp-item:last-child { border-bottom: none; }
.qp-index {
  width: 22px; height: 22px; border-radius: 50%;
  background: var(--c-blue-50); color: var(--c-blue-700);
  display: grid; place-items: center; font-size: 12.5px; font-weight: 600; flex: none;
}
.qp-text { margin-top: 6px; font-size: 14px; line-height: 1.65; color: var(--c-text-2); }

.rules { margin: 10px 0 0; padding-left: 18px; color: var(--c-text-2); font-size: 14px; line-height: 1.9; }
.rules b { color: var(--c-text); }

.mic-test { margin-top: 18px; padding: 14px; border: 1px solid var(--c-line-2); border-radius: 12px; }
.mic-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--c-grey-200); }
.mic-dot.live { background: var(--c-green); }

.live-grid { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 1fr); gap: 16px; }
.live-main { border-color: var(--c-blue-100); box-shadow: 0 0 0 3px rgba(26, 115, 232, .06); }

.live-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-red); animation: pulse 1.6s infinite; }

.timer { font-size: 23px; font-weight: 500; font-variant-numeric: tabular-nums; letter-spacing: .5px; }
.timer.warn { color: var(--c-red); }

.question-box { margin: 18px 0; padding: 18px; border-radius: 14px; background: var(--c-grey-50); }
.question-text { font-size: 17px; line-height: 1.75; letter-spacing: -.1px; }

.mic-zone { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 26px 0 18px; }

.wave { display: flex; align-items: center; gap: 3px; height: 44px; }
.wave span {
  width: 3px; border-radius: 3px; background: var(--c-blue);
  animation: wave 1.1s ease-in-out infinite alternate;
  opacity: .75;
}

.mic-hint { font-size: 13.5px; color: var(--c-text-3); }

.transcript { padding: 14px 16px; border: 1px solid var(--c-line-2); border-radius: 12px; }
.transcript-text { font-size: 14.5px; line-height: 1.8; color: var(--c-text-2); }
.cursor { color: var(--c-blue); animation: blink 1s steps(1) infinite; }

.q-progress { display: flex; flex-wrap: wrap; gap: 8px; }
.q-dot {
  width: 32px; height: 32px; border-radius: 50%;
  display: grid; place-items: center;
  background: var(--c-grey-100); color: var(--c-text-3); font-size: 13.5px;
}
.q-dot.done { background: var(--c-green-50); color: var(--c-green-700); }
.q-dot.active { background: var(--c-blue); color: #fff; box-shadow: 0 0 0 3px rgba(26, 115, 232, .18); }

.result-head { display: flex; align-items: center; gap: 24px; }
.dim-row { display: flex; align-items: center; gap: 14px; }
.dim-name { width: 110px; font-size: 14px; color: var(--c-text-2); flex: none; }
.dim-val { width: 64px; text-align: right; font-size: 14px; }
.dim-val i { font-style: normal; font-size: 12px; color: var(--c-text-3); }

.qa-row { display: flex; align-items: center; gap: 12px; padding: 10px 4px; border-bottom: 1px dashed var(--c-line-2); }
.qa-row:last-child { border-bottom: none; }

@keyframes wave { from { transform: scaleY(.35); } to { transform: scaleY(1); } }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .35; } }
@keyframes blink { 0%, 49% { opacity: 1; } 50%, 100% { opacity: 0; } }

.ready-wrap { display: flex; justify-content: center; padding-top: 4px; }
.ready-card { width: min(720px, 100%); padding: 40px 40px 30px; text-align: center; }
.ready-icon {
  width: 64px; height: 64px;
  margin: 0 auto 18px;
  border-radius: 20px;
  display: grid; place-items: center;
  background: var(--c-blue-50);
  color: var(--c-blue-700);
}
.ready-title { font-size: 25px; font-weight: 500; letter-spacing: -.5px; }
.ready-sub { max-width: 520px; margin: 8px auto 24px; color: var(--c-text-2); font-size: 15px; line-height: 1.7; }

.device-box {
  text-align: left;
  padding: 16px 18px;
  border: 1px solid var(--c-line-2);
  border-radius: 14px;
  background: var(--c-grey-50);
}
.device-dot {
  width: 10px; height: 10px;
  border-radius: 50%;
  background: var(--c-grey-200);
  box-shadow: 0 0 0 4px rgba(74, 62, 45, .05);
  flex: none;
}
.device-dot.ok { background: var(--c-green); box-shadow: 0 0 0 4px var(--c-green-50); }

.start-btn { margin-top: 20px; }
.ready-hint { margin-top: 12px; font-size: 13.5px; color: var(--c-text-3); }
.ready-hint.warn { color: var(--c-amber-700); }
.ready-meta {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  margin-top: 22px;
  color: var(--c-text-3);
  font-size: 13.5px;
}
.ready-meta i { font-style: normal; color: var(--c-line); }

@media (max-width: 1080px) {
  .ready-card { padding: 32px 22px 26px; }
  .device-box .row { flex-wrap: wrap; }
  .live-grid { grid-template-columns: 1fr; }
  .result-head { flex-direction: column; align-items: flex-start; }
}
</style>
