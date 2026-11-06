<template>
  <div>
    <div class="crumb">
      <RouterLink to="/teacher/offerings">我的开课</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <span>新建开课</span>
    </div>

    <div class="page-head">
      <div>
        <div class="page-title">新建开课</div>
        <div class="page-sub">创建后可先保存草稿，导入学生并上传题目后再发布</div>
      </div>
      <div class="page-actions">
        <button class="btn btn-outline" @click="saveDraft">保存草稿</button>
        <button class="btn btn-primary" @click="publish">发布开课</button>
      </div>
    </div>

    <div class="create-grid">
      <div class="col gap-16">
        <section class="card">
          <div class="card-head"><div class="card-title">基本信息</div></div>
          <div class="card-pad grid g2">
            <div class="field">
              <label class="label">课程<span class="req">*</span></label>
              <select v-model="form.course" class="select">
                <option>面向对象程序设计课程设计(Java)</option>
                <option>数据结构课程设计</option>
                <option>软件工程综合实践</option>
              </select>
            </div>
            <div class="field">
              <label class="label">学期<span class="req">*</span></label>
              <select v-model="form.semester" class="select">
                <option>2026-2027-1</option>
                <option>2025-2026-2</option>
              </select>
            </div>
            <div class="field">
              <label class="label">教学班<span class="req">*</span></label>
              <input v-model="form.className" class="input" placeholder="如 计科2301" />
            </div>
            <div class="field">
              <label class="label">课程代码</label>
              <input v-model="form.code" class="input" placeholder="如 CS3001" />
            </div>
            <div class="field" style="grid-column: 1 / -1">
              <label class="label">课程说明</label>
              <textarea v-model="form.description" class="textarea" placeholder="对学生可见的课程设计说明、组织形式与提交要求" />
            </div>
          </div>
        </section>

        <section class="card">
          <div class="card-head">
            <div class="card-title">选题与提交</div>
            <span class="chip">学生可多选</span>
          </div>
          <div class="card-pad grid g2">
            <div class="field">
              <label class="label">每人限选题目数</label>
              <div class="stepper">
                <button class="icon-btn" @click="form.topicLimit = Math.max(1, form.topicLimit - 1)">
                  <AppIcon name="x" :size="14" style="display: none" />
                  <span style="font-size: 19px; line-height: 1">−</span>
                </button>
                <span class="stepper-val">{{ form.topicLimit }}</span>
                <button class="icon-btn" @click="form.topicLimit = Math.min(10, form.topicLimit + 1)">
                  <AppIcon name="plus" :size="16" />
                </button>
              </div>
            </div>
            <div class="field">
              <label class="label">选题窗口</label>
              <div class="row gap-8">
                <input v-model="form.selectStart" class="input" type="date" />
                <span class="dim">~</span>
                <input v-model="form.selectEnd" class="input" type="date" />
              </div>
            </div>
            <div class="field">
              <label class="label">材料提交截止</label>
              <input v-model="form.submitDeadline" class="input" type="date" />
            </div>
            <div class="field">
              <label class="label">答辩窗口</label>
              <div class="row gap-8">
                <input v-model="form.defenseStart" class="input" type="date" />
                <span class="dim">~</span>
                <input v-model="form.defenseEnd" class="input" type="date" />
              </div>
            </div>
          </div>
        </section>

        <section class="card">
          <div class="card-head"><div class="card-title">答辩设置</div></div>
          <div class="card-pad grid g2">
            <div class="field">
              <label class="label">答辩组织方式</label>
              <select v-model="form.mode" class="select">
                <option value="PER_STUDENT">每人一场（覆盖全部选题）</option>
                <option value="PER_TOPIC">每题一场</option>
              </select>
            </div>
            <div class="field">
              <label class="label">答辩题目数</label>
              <select v-model.number="form.questionCount" class="select">
                <option :value="4">4 道</option>
                <option :value="6">6 道（推荐）</option>
                <option :value="8">8 道</option>
              </select>
            </div>
            <div class="field">
              <label class="label">单题作答时限</label>
              <select v-model.number="form.maxSeconds" class="select">
                <option :value="120">2 分钟</option>
                <option :value="180">3 分钟</option>
                <option :value="300">5 分钟</option>
              </select>
            </div>
            <div class="field">
              <label class="label">录制视频</label>
              <div class="switch-row">
                <button class="switch" :class="{ on: form.video }" @click="form.video = !form.video">
                  <span />
                </button>
                <span class="small muted">{{ form.video ? '音频 + 视频' : '仅音频（推荐）' }}</span>
              </div>
            </div>
          </div>
        </section>

        <section class="card">
          <div class="card-head">
            <div class="card-title">成绩权重</div>
            <span class="chip" :class="weightSum === 100 ? 'green' : 'red'">合计 {{ weightSum }}%</span>
          </div>
          <div class="card-pad">
            <div class="grid g3">
              <div class="field">
                <label class="label">在线答辩</label>
                <input v-model.number="form.wDefense" class="input" type="number" min="0" max="100" />
              </div>
              <div class="field">
                <label class="label">源码质量</label>
                <input v-model.number="form.wCode" class="input" type="number" min="0" max="100" />
              </div>
              <div class="field">
                <label class="label">报告质量</label>
                <input v-model.number="form.wReport" class="input" type="number" min="0" max="100" />
              </div>
            </div>
            <div class="weight-bar mt-16">
              <span class="w1" :style="{ width: form.wDefense + '%' }" />
              <span class="w2" :style="{ width: form.wCode + '%' }" />
              <span class="w3" :style="{ width: form.wReport + '%' }" />
            </div>
            <div class="hint" :class="{ 'hint-error': weightSum !== 100 }">
              {{ weightSum === 100 ? '权重合计 100%，符合要求' : '三项权重之和必须为 100' }}
            </div>
          </div>
        </section>
      </div>

      <aside class="col gap-16">
        <section class="card card-pad sticky">
          <div class="section-title">开课摘要</div>
          <div class="summary mt-16">
            <div class="s-row"><span>课程</span><b>{{ form.course }}</b></div>
            <div class="s-row"><span>学期 / 班级</span><b>{{ form.semester }} · {{ form.className || '—' }}</b></div>
            <div class="s-row"><span>限选题目</span><b>{{ form.topicLimit }} 道 / 人</b></div>
            <div class="s-row"><span>答辩形式</span><b>{{ form.mode === 'PER_STUDENT' ? '每人一场' : '每题一场' }}</b></div>
            <div class="s-row"><span>答辩题量</span><b>{{ form.questionCount }} 道 · 每题 {{ form.maxSeconds / 60 }} 分钟</b></div>
            <div class="s-row"><span>权重</span><b>{{ form.wDefense }} / {{ form.wCode }} / {{ form.wReport }}</b></div>
          </div>

          <hr class="divider" />

          <div class="hint">发布前需完成：导入学生名单、至少发布 1 道题目。发布后学生即可看到本开课。</div>

          <button class="btn btn-primary btn-block mt-16" @click="publish">发布开课</button>
          <button class="btn btn-outline btn-block mt-8" @click="saveDraft">保存草稿</button>
        </section>

        <section class="card card-pad">
          <div class="row gap-8 strong"><AppIcon name="alert" :size="16" /> 发布校验</div>
          <div class="col gap-10 mt-12">
            <div v-for="c in checks" :key="c.label" class="mini-check">
              <AppIcon :name="c.ok ? 'checkCircle' : 'clock'" :size="16" :style="{ color: c.ok ? 'var(--c-green)' : 'var(--c-text-3)' }" />
              <span class="small" :class="{ dim: !c.ok }">{{ c.label }}</span>
            </div>
          </div>
        </section>
      </aside>
    </div>

    <ModalDialog v-model="published" title="开课已创建" subtitle="下一步：导入学生名单、上传题目">
      <div class="col gap-10">
        <div class="mini-check"><AppIcon name="checkCircle" :size="18" style="color: var(--c-green)" /><span>开课「{{ form.course }}」已保存</span></div>
        <div class="hint">这是界面演示，发布后不会真正写入后端。</div>
      </div>
      <template #footer>
        <button class="btn btn-outline" @click="published = false">留在此页</button>
        <RouterLink class="btn btn-primary" to="/teacher/offerings/1839201930199990001">进入开课</RouterLink>
      </template>
    </ModalDialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import ModalDialog from '@/components/ModalDialog.vue'

const form = reactive({
  course: '面向对象程序设计课程设计(Java)',
  semester: '2026-2027-1',
  className: '计科2301',
  code: 'CS3001',
  description: '以 Java 为载体完成一个完整的小型系统，要求使用分层架构，并在报告中说明关键设计决策。',
  topicLimit: 3,
  selectStart: '2026-09-10',
  selectEnd: '2026-09-20',
  submitDeadline: '2026-10-15',
  defenseStart: '2026-10-20',
  defenseEnd: '2026-10-30',
  mode: 'PER_STUDENT',
  questionCount: 6,
  maxSeconds: 180,
  video: false,
  wDefense: 40,
  wCode: 35,
  wReport: 25
})

const published = ref(false)
const weightSum = computed(() => Number(form.wDefense) + Number(form.wCode) + Number(form.wReport))

const checks = computed(() => [
  { label: '已导入学生名单', ok: false },
  { label: '已发布至少 1 道题目', ok: false },
  { label: '时间节点设置完整', ok: true },
  { label: '权重合计 100%', ok: weightSum.value === 100 }
])

function saveDraft() {
  published.value = true
}

function publish() {
  published.value = true
}
</script>

<style scoped>
.create-grid { display: grid; grid-template-columns: minmax(0, 1.75fr) minmax(0, 1fr); gap: 16px; }
.sticky { position: sticky; top: 84px; }

.stepper { display: inline-flex; align-items: center; gap: 6px; height: 44px; padding: 0 6px; border: 1px solid var(--c-line); border-radius: 10px; width: fit-content; }
.stepper-val { min-width: 32px; text-align: center; font-size: 16px; font-weight: 500; }

.switch-row { display: flex; align-items: center; gap: 10px; height: 44px; }
.switch {
  width: 42px; height: 24px; border-radius: 999px;
  border: none; background: var(--c-grey-200);
  padding: 3px; cursor: pointer;
  transition: background .18s var(--ease);
}
.switch span { display: block; width: 18px; height: 18px; border-radius: 50%; background: var(--c-surface); box-shadow: 0 1px 2px rgba(74, 62, 45, .22); transition: transform .18s var(--ease); }
.switch.on { background: var(--c-blue); }
.switch.on span { transform: translateX(18px); }

.weight-bar { display: flex; height: 10px; border-radius: 999px; overflow: hidden; background: var(--c-grey-100); }
.weight-bar span { height: 100%; display: block; }
.w1 { background: #1a73e8; } .w2 { background: #34a853; } .w3 { background: #a142f4; }

.summary { display: flex; flex-direction: column; gap: 10px; }
.s-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; font-size: 13.5px; color: var(--c-text-3); }
.s-row b { color: var(--c-text); font-weight: 500; text-align: right; }

.mini-check { display: flex; align-items: center; gap: 8px; font-size: 14px; }

@media (max-width: 1080px) {
  .create-grid { grid-template-columns: 1fr; }
  .sticky { position: static; }
}
</style>
