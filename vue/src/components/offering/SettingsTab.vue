<script setup>
import { computed, ref, watch } from 'vue'
import Icon from '../Icon.vue'
import { api } from '../../api/endpoints'
import { toast, toastError } from '../../stores/toast'

const props = defineProps({
  offering: { type: Object, required: true },
})
const emit = defineEmits(['saved'])

const busy = ref(false)
const error = ref('')
const form = ref(blank())

function blank() {
  const o = props.offering || {}
  return {
    className: o.className || '',
    description: o.description || '',
    topicSelectLimit: o.topicSelectLimit ?? 3,
    defense: o.weights?.defense ?? 40,
    code: o.weights?.code ?? 35,
    report: o.weights?.report ?? 25,
    selectStart: o.timeline?.selectStart || '',
    selectEnd: o.timeline?.selectEnd || '',
    submitDeadline: o.timeline?.submitDeadline || '',
    defenseStart: o.timeline?.defenseStart || '',
    defenseEnd: o.timeline?.defenseEnd || '',
    version: o.version,
  }
}

watch(() => props.offering, () => {
  form.value = blank()
})

const locked = computed(() => props.offering?.status === 'PUBLISHED' || props.offering?.status === 'CLOSED')
const weightSum = computed(() => Number(form.value.defense) + Number(form.value.code) + Number(form.value.report))

async function save() {
  error.value = ''
  if (weightSum.value !== 100) return (error.value = `三项权重之和必须为 100，当前 ${weightSum.value}`)
  busy.value = true
  try {
    const payload = {
      version: form.value.version,
      description: form.value.description || undefined,
      timeline: {
        selectStart: form.value.selectStart || undefined,
        selectEnd: form.value.selectEnd || undefined,
        submitDeadline: form.value.submitDeadline || undefined,
        defenseStart: form.value.defenseStart || undefined,
        defenseEnd: form.value.defenseEnd || undefined,
      },
    }
    if (!locked.value) {
      payload.className = form.value.className || undefined
      payload.topicSelectLimit = Number(form.value.topicSelectLimit)
      payload.weights = {
        defense: Number(form.value.defense),
        code: Number(form.value.code),
        report: Number(form.value.report),
      }
    }
    await api.patchOffering(props.offering.id, payload)
    toast.ok('已保存', '开课配置更新成功')
    emit('saved')
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="grid grid--sidebar">
    <section class="card">
      <div class="card__head">
        <h3>开课配置</h3>
        <span class="pill" :class="locked ? 'pill--warn' : 'pill--ok'">
          {{ locked ? '已发布：班级/限额/权重不可改，时间只能延后' : '草稿：全部可改' }}
        </span>
      </div>
      <div class="card__body" style="display: grid; gap: 16px">
        <div class="form-grid">
          <div class="field">
            <label>教学班</label>
            <input v-model="form.className" class="input" :disabled="locked" />
          </div>
          <div class="field">
            <label>每人最多选几题</label>
            <input v-model.number="form.topicSelectLimit" type="number" min="1" max="10" class="input" :disabled="locked" />
          </div>
          <div class="field span-2">
            <label>课程说明</label>
            <textarea v-model="form.description" class="textarea" placeholder="对学生的整体要求" />
          </div>
        </div>

        <div>
          <div class="section-title"><Icon name="chart" :size="14" />成绩权重</div>
          <div class="form-grid form-grid--3">
            <div class="field">
              <label>答辩 %</label>
              <input v-model.number="form.defense" type="number" min="0" max="100" class="input" :disabled="locked" />
            </div>
            <div class="field">
              <label>源码 %</label>
              <input v-model.number="form.code" type="number" min="0" max="100" class="input" :disabled="locked" />
            </div>
            <div class="field">
              <label>报告 %</label>
              <input v-model.number="form.report" type="number" min="0" max="100" class="input" :disabled="locked" />
            </div>
          </div>
          <div class="row small" style="margin-top: 8px">
            <span :class="weightSum === 100 ? 'pill pill--ok' : 'pill pill--danger'">合计 {{ weightSum }}%</span>
          </div>
        </div>

        <div>
          <div class="section-title"><Icon name="clock" :size="14" />时间线（只能往后延）</div>
          <div class="form-grid">
            <div class="field">
              <label>选题开始</label>
              <input v-model="form.selectStart" type="date" class="input" />
            </div>
            <div class="field">
              <label>选题结束</label>
              <input v-model="form.selectEnd" type="date" class="input" />
            </div>
            <div class="field">
              <label>提交截止</label>
              <input v-model="form.submitDeadline" type="date" class="input" />
            </div>
            <div class="field">
              <label>答辩开始</label>
              <input v-model="form.defenseStart" type="date" class="input" />
            </div>
            <div class="field">
              <label>答辩结束</label>
              <input v-model="form.defenseEnd" type="date" class="input" />
            </div>
          </div>
        </div>

        <div v-if="error" class="alert alert--danger">
          <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />{{ error }}
        </div>

        <div class="row">
          <button class="btn btn--primary" :disabled="busy" @click="save">
            <span v-if="busy" class="spinner" />保存修改
          </button>
          <span class="small dim">乐观锁 version = {{ form.version }}</span>
        </div>
      </div>
    </section>

    <div style="display: grid; gap: 16px; align-content: start">
      <section class="card card--pad">
        <div class="section-title"><Icon name="flag" :size="14" />答辩设置</div>
        <dl class="dl" style="grid-template-columns: 76px 1fr">
          <dt>模式</dt><dd>{{ offering.defenseConfig?.defenseMode || 'AI_ASYNC' }}</dd>
          <dt>题目数</dt><dd>{{ offering.defenseConfig?.questionCount ?? 5 }}</dd>
          <dt>单题时长</dt><dd>{{ offering.defenseConfig?.maxAnswerSeconds ?? 180 }} 秒</dd>
          <dt>允许重答</dt><dd>{{ offering.defenseConfig?.allowRetry ? '是' : '否' }}</dd>
        </dl>
      </section>

      <section class="card card--pad">
        <div class="section-title"><Icon name="shield" :size="14" />发布检查清单</div>
        <div class="timeline">
          <div class="timeline__item" :class="{ 'is-done': (offering.stats?.studentCount ?? 0) > 0 }">
            <div class="timeline__title">至少一名学生</div>
            <div class="timeline__meta">当前 {{ offering.stats?.studentCount ?? 0 }} 人</div>
          </div>
          <div class="timeline__item" :class="{ 'is-done': (offering.stats?.topicCount ?? 0) > 0 }">
            <div class="timeline__title">至少一道已发布题目</div>
            <div class="timeline__meta">当前 {{ offering.stats?.topicCount ?? 0 }} 道</div>
          </div>
          <div class="timeline__item" :class="{ 'is-done': weightSum === 100 }">
            <div class="timeline__title">权重合计 100%</div>
            <div class="timeline__meta">当前 {{ weightSum }}%</div>
          </div>
          <div class="timeline__item" :class="{ 'is-done': Boolean(form.selectEnd) }">
            <div class="timeline__title">选题窗口合法</div>
            <div class="timeline__meta">{{ form.selectStart || '—' }} → {{ form.selectEnd || '—' }}</div>
          </div>
        </div>
      </section>

      <section class="card card--pad">
        <div class="section-title"><Icon name="lock" :size="14" />状态机</div>
        <p class="small muted">
          草稿 → 已发布 → 已关闭 → 已归档。发布后权重、限选数与班级锁定，时间线只能向后延；
          关闭后学生不能再选题，归档为最终态。
        </p>
      </section>
    </div>
  </div>
</template>
