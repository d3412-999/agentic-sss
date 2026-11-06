<template>
  <div>
    <div class="crumb">
      <RouterLink to="/teacher/offerings">我的开课</RouterLink>
      <AppIcon name="chevronRight" :size="13" />
      <span>计科2301 · 2026-2027-1</span>
    </div>

    <div class="page-head">
      <div>
        <div class="row gap-10 wrap">
          <div class="page-title">{{ offering.courseName }}</div>
          <StatusChip :status="offering.status" />
        </div>
        <div class="page-sub">
          {{ offering.code }} · {{ offering.semester }} · {{ offering.className }} · 指导教师 {{ offering.teacherName }}
        </div>
      </div>
      <div class="page-actions">
        <button class="btn btn-outline" @click="closeOffering">
          <AppIcon name="x" :size="18" />
          关闭选题
        </button>
        <button class="btn btn-primary" @click="publishGrades">
          <AppIcon name="send" :size="18" />
          发布成绩
        </button>
      </div>
    </div>


    <div class="grid g4">
      <StatCard label="学生" value="42" suffix="人" hint="名单已导入" icon="users" />
      <StatCard label="题目" value="8" suffix="道" hint="7 道已发布" icon="file" tone="violet" />
      <StatCard label="答辩完成" value="30" suffix="/ 42" hint="今日完成 5 人" icon="mic" tone="amber" />
      <StatCard label="平均分" value="80.6" suffix="分" hint="未发布" icon="award" tone="green" />
    </div>

    <div class="detail-grid">
      <div class="col gap-16">
        <section class="card">
          <div class="card-head">
            <div class="card-title">阶段进度</div>
            <span class="tiny dim">材料截止 10-15 · 答辩 10-20 ~ 10-30</span>
          </div>
          <div class="card-pad">
            <div class="phase">
              <div v-for="(p, i) in phases" :key="p.label" class="phase-item" :class="{ done: p.done, active: p.active }">
                <div class="phase-top">
                  <span class="phase-dot">
                    <AppIcon v-if="p.done" name="check" :size="13" />
                    <span v-else>{{ i + 1 }}</span>
                  </span>
                  <div class="grow">
                    <div class="between">
                      <span class="small strong">{{ p.label }}</span>
                      <span class="tiny dim">{{ p.value }}</span>
                    </div>
                    <div class="bar thin mt-8"><span :style="{ width: p.pct + '%' }" :class="{ green: p.done }" /></div>
                  </div>
                </div>
                <div v-if="i < phases.length - 1" class="phase-line" />
              </div>
            </div>
          </div>
        </section>

        <section class="card">
          <div class="card-head">
            <div class="card-title">最近动态</div>
            <button class="btn btn-sm btn-text">查看全部</button>
          </div>
          <div class="card-pad col gap-4">
            <div v-for="a in activities" :key="a.text" class="activity">
              <span class="activity-dot" :class="a.tone" />
              <div class="grow">
                <div class="small">{{ a.text }}</div>
                <div class="tiny dim mt-2">{{ a.meta }}</div>
              </div>
              <span class="tiny dim nowrap">{{ a.time }}</span>
            </div>
          </div>
        </section>
      </div>

      <div class="col gap-16">
        <section class="card card-pad">
          <div class="between">
            <span class="section-title">成绩权重</span>
            <button class="btn btn-sm btn-text">调整</button>
          </div>
          <div class="weight">
            <div v-for="w in weights" :key="w.label" class="w-row">
              <span class="small muted">{{ w.label }}</span>
              <div class="grow bar thin"><span :style="{ width: w.value + '%', background: w.color }" /></div>
              <span class="small num strong" style="width: 40px; text-align: right">{{ w.value }}%</span>
            </div>
          </div>
          <hr class="divider" />
          <div class="between small">
            <span class="muted">最终成绩</span>
            <span>答辩 40% + 源码 35% + 报告 25%</span>
          </div>
        </section>

        <section class="card card-pad">
          <div class="section-title">答辩设置</div>
          <div class="col gap-10 mt-12 small">
            <div class="between"><span class="muted">组织方式</span><span>每人一场</span></div>
            <div class="between"><span class="muted">题目数量</span><span>6 道</span></div>
            <div class="between"><span class="muted">单题时限</span><span>3 分钟</span></div>
            <div class="between"><span class="muted">录制</span><span>仅音频</span></div>
          </div>
          <button class="btn btn-sm btn-outline btn-block mt-16">修改答辩设置</button>
        </section>

        <section class="card card-pad">
          <div class="row gap-8 strong"><AppIcon name="alert" :size="16" /> 待处理</div>
          <div class="col gap-10 mt-12 small">
            <div class="between"><span class="muted">待复核答辩</span><a class="link">12 条</a></div>
            <div class="between"><span class="muted">未提交材料</span><a class="link">3 人</a></div>
            <div class="between"><span class="muted">未完成答辩</span><a class="link">12 人</a></div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import StatCard from '@/components/StatCard.vue'
import StatusChip from '@/components/StatusChip.vue'
import { offering } from '@/data/mock'

const phases = [
  { label: '选题开放', value: '40 / 42 人已选', pct: 95, done: true },
  { label: '材料提交', value: '39 / 42 人已交', pct: 93, done: true },
  { label: '在线答辩', value: '30 / 42 人完成', pct: 71, done: false, active: true },
  { label: '成绩发布', value: '尚未发布', pct: 0, done: false }
]

const weights = [
  { label: '在线答辩', value: 40, color: '#1a73e8' },
  { label: '源码质量', value: 35, color: '#34a853' },
  { label: '报告质量', value: 25, color: '#a142f4' }
]

const activities = [
  { text: '张三 完成在线答辩，系统自动评分 85.0', meta: '答辩录音 13:02 · 待复核', tone: 'blue', time: '19:15' },
  { text: '吴十 上传了源码 v3', meta: 'bookstore-v3.zip · 重新分析完成', tone: 'green', time: '18:40' },
  { text: '系统完成 5 份报告评审', meta: '平均分 82.3 · 1 份一致性异常', tone: 'violet', time: '17:20' },
  { text: '孙八 调整成绩申请', meta: '答辩 78 → 80，等待确认', tone: 'amber', time: '16:05' },
  { text: '开课已发布', meta: '学生端可见 · 42 人', tone: 'grey', time: '09-05' }
]

const total = computed(() => 42)

function closeOffering() {}
function publishGrades() {}
</script>

<style scoped>
.detail-grid { display: grid; grid-template-columns: minmax(0, 1.8fr) minmax(0, 1fr); gap: 16px; margin-top: 16px; }

.phase { display: flex; flex-direction: column; gap: 18px; }
.phase-item { position: relative; }
.phase-top { display: flex; gap: 12px; align-items: flex-start; }
.phase-dot {
  width: 26px; height: 26px; border-radius: 50%;
  background: var(--c-grey-100); color: var(--c-text-3);
  display: grid; place-items: center; font-size: 13px; font-weight: 600;
  flex: none; z-index: 1;
}
.phase-item.done .phase-dot { background: var(--c-blue); color: #fff; }
.phase-item.active .phase-dot { background: var(--c-surface); border: 2px solid var(--c-blue); color: var(--c-blue-700); }
.phase-line { position: absolute; left: 12px; top: 30px; bottom: -18px; width: 2px; background: var(--c-line-2); }
.phase-item.done .phase-line { background: var(--c-blue-100); }
.phase-item:last-child .phase-line { display: none; }

.activity { display: flex; gap: 12px; padding: 10px 4px; border-bottom: 1px dashed var(--c-line-2); }
.activity:last-child { border-bottom: none; }
.activity-dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; background: var(--c-blue); flex: none; }
.activity-dot.green { background: var(--c-green); }
.activity-dot.violet { background: var(--c-violet); }
.activity-dot.amber { background: #f9ab00; }
.activity-dot.grey { background: var(--c-line); }

.weight { display: flex; flex-direction: column; gap: 12px; margin-top: 14px; }
.w-row { display: flex; align-items: center; gap: 10px; }

@media (max-width: 1080px) {
  .detail-grid { grid-template-columns: 1fr; }
}
</style>
