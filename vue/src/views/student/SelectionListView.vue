<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-title">我的选题</div>
        <div class="page-sub">共 {{ list.length }} 道题目 · 每道题需分别提交源码与课程设计报告</div>
      </div>
      <div class="page-actions">
        <RouterLink class="btn btn-primary" to="/student/courses">
          <AppIcon name="plus" :size="18" />
          继续选题
        </RouterLink>
      </div>
    </div>

    <div class="card">
      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th style="min-width: 280px">题目</th>
              <th>难度</th>
              <th style="min-width: 180px">提交材料</th>
              <th>评分</th>
              <th>状态</th>
              <th>最近更新</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in list" :key="s.id">
              <td>
                <RouterLink :to="`/student/selections/${s.id}`" class="topic-link">{{ s.topicTitle }}</RouterLink>
                <div class="row gap-6 mt-4">
                  <span v-for="tag in s.tags" :key="tag" class="chip outline">{{ tag }}</span>
                </div>
              </td>
              <td><StatusChip :status="s.difficulty" /></td>
              <td>
                <div class="col gap-6">
                  <span class="file-line" :class="{ ok: s.sourceUploaded }">
                    <AppIcon name="file" :size="15" />
                    源码 {{ s.sourceUploaded ? `v${s.sourceVersion}` : '未提交' }}
                  </span>
                  <span class="file-line" :class="{ ok: s.reportUploaded }">
                    <AppIcon name="file" :size="15" />
                    报告 {{ s.reportUploaded ? `v${s.reportVersion}` : '未提交' }}
                  </span>
                </div>
              </td>
              <td>
                <div class="row gap-10 num">
                  <span class="mini-score">
                    <i>源码</i>{{ s.codeScore ?? '—' }}
                  </span>
                  <span class="mini-score">
                    <i>报告</i>{{ s.reportScore ?? '—' }}
                  </span>
                </div>
              </td>
              <td><StatusChip :status="s.status" /></td>
              <td class="dim small nowrap">{{ s.updatedAt }}</td>
              <td class="right">
                <RouterLink class="btn btn-sm btn-tonal" :to="`/student/selections/${s.id}`">
                  {{ s.sourceUploaded ? '查看' : '去提交' }}
                </RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="grid g3 mt-16">
      <div class="card card-pad">
        <div class="row gap-8 strong"><AppIcon name="upload" :size="17" /> 提交提醒</div>
        <p class="small muted mt-8" style="line-height: 1.7">
          源码请以 zip 打包（含 pom.xml 或 build.gradle），报告支持 PDF / DOCX。提交截止前可重复上传，系统保留历史版本。
        </p>
      </div>
      <div class="card card-pad">
        <div class="row gap-8 strong"><AppIcon name="clock" :size="17" /> 截止时间</div>
        <p class="small muted mt-8" style="line-height: 1.7">
          材料提交截止 <b>2026-10-15 23:59</b>，逾期系统不再接收。<br />答辩窗口 10-20 ~ 10-30。
        </p>
      </div>
      <div class="card card-pad">
        <div class="row gap-8 strong"><AppIcon name="shield" :size="17" /> 学术诚信</div>
        <p class="small muted mt-8" style="line-height: 1.7">
          系统会做报告查重与源码相似度比对，答辩环节包含针对个人代码的追问，请确保材料真实。
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import AppIcon from '@/components/AppIcon.vue'
import StatusChip from '@/components/StatusChip.vue'
import { selections } from '@/data/mock'

const list = selections
</script>

<style scoped>
.topic-link { font-weight: 500; font-size: 15px; }
.topic-link:hover { color: var(--c-blue-700); }
.file-line { display: inline-flex; align-items: center; gap: 6px; font-size: 13.5px; color: var(--c-text-3); }
.file-line.ok { color: var(--c-text-2); }
.mini-score { display: inline-flex; flex-direction: column; line-height: 1.15; font-weight: 500; }
.mini-score i { font-style: normal; font-size: 12px; color: var(--c-text-3); font-weight: 400; }
</style>
