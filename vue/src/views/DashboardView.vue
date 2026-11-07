<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api/endpoints'
import { session, currentRole, setMe } from '../stores/session'

const loading = ref(true)
const error = ref('')
const health = ref(null)
const me = ref(session.me)

const endpointGroups = [
  { name: '认证', count: 5, note: '登录 / 刷新 / 登出 / 当前用户 / 改密' },
  { name: '教师开课与学生名单', count: 15, note: '课程模板、开课生命周期、仪表盘、名单管理' },
  { name: '题目', count: 10, note: '教师出题、发布归档、学生浏览' },
  { name: '学生选题', count: 5, note: '开课列表、批量选题（幂等）、退选' },
  { name: '提交物与文件', count: 9, note: '源码包 / 报告上传、下载、重新分析' },
  { name: '成绩', count: 9, note: '成绩列表、调整、发布、导出、学生查询' },
  { name: '通知与任务', count: 5, note: '站内通知、异步任务轮询' },
  { name: '健康检查', count: 1, note: '免鉴权探活' },
]

const portContracts = [
  { service: '前端 Vite', port: '5173', note: 'CORS 白名单 / Vite dev server' },
  { service: '后端 Spring Boot', port: '8081', note: 'vue/.env 的 VITE_API_TARGET' },
  { service: 'AI 服务 FastAPI', port: '8000', note: 'sss.ai.base-url，仅后端内部调用' },
  { service: 'MySQL', port: '3306', note: 'Flyway 自动执行 V1 / V2' },
  { service: 'Redis', port: '6379', note: '登录限流、幂等、令牌版本' },
]

onMounted(async () => {
  try {
    const [h, m] = await Promise.all([api.health(), api.me()])
    health.value = h
    me.value = m
    setMe(m)
  } catch (e) {
    error.value = e?.message || '加载概览失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <h1>概览</h1>
    <p class="muted" style="margin-top: 0">前后端链路联通状态与系统能力一览</p>

    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div class="grid grid--3" style="margin: 20px 0">
      <div class="stat">
        <div class="stat__label">后端服务</div>
        <div class="stat__value">
          <span v-if="health" class="badge badge--ok">{{ health.status }}</span>
          <span v-else-if="loading" class="badge badge--gray">检测中</span>
          <span v-else class="badge badge--down">不可达</span>
        </div>
        <div class="muted" style="margin-top: 8px">{{ health?.app || '等待 /api/v1/health' }}</div>
      </div>
      <div class="stat">
        <div class="stat__label">当前用户</div>
        <div class="stat__value" style="font-size: 18px">{{ me?.name || session.user?.name || '—' }}</div>
        <div class="muted" style="margin-top: 8px">{{ currentRole }} · {{ me?.username }}</div>
      </div>
      <div class="stat">
        <div class="stat__label">已实现接口</div>
        <div class="stat__value">59</div>
        <div class="muted" style="margin-top: 8px">8 个 Controller · REST / JSON</div>
      </div>
    </div>

    <div class="card">
      <div class="card__head"><h2>当前账号信息</h2></div>
      <div v-if="me" class="table-wrap">
        <table>
          <tbody>
            <tr><th style="width: 140px">姓名 / 账号</th><td>{{ me.name }} · {{ me.username }}</td></tr>
            <tr><th>角色 / 班级</th><td>{{ me.role }} · {{ me.className || '—' }}</td></tr>
            <tr><th>邮箱</th><td>{{ me.email || '—' }}</td></tr>
            <tr>
              <th>关联开课</th>
              <td>
                <span v-if="!me.offerings?.length" class="muted">暂无（教师账号以 /teacher/offerings 列表为准）</span>
                <span v-for="o in me.offerings" v-else :key="o.offeringId" class="tag">
                  {{ o.courseName }} · {{ o.semester }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty">尚未加载 /auth/me</div>
    </div>

    <div class="grid grid--2">
      <div class="card">
        <div class="card__head"><h2>接口能力</h2><span class="muted">详见 Swagger</span></div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>分组</th><th>端点</th><th>说明</th></tr></thead>
            <tbody>
              <tr v-for="g in endpointGroups" :key="g.name">
                <td>{{ g.name }}</td><td>{{ g.count }}</td><td class="muted">{{ g.note }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card">
        <div class="card__head"><h2>端口与配置契约</h2></div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>服务</th><th>默认端口</th><th>关联配置</th></tr></thead>
            <tbody>
              <tr v-for="p in portContracts" :key="p.service">
                <td>{{ p.service }}</td><td>{{ p.port }}</td><td class="muted">{{ p.note }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="alert alert--info" style="margin: 14px 0 0">
          AI 服务的 5 个 /internal/ai/** 接口仍是 501 占位，且只允许后端携带
          X-Internal-Token 访问，前端不直连 8000 端口。
        </div>
      </div>
    </div>
  </div>
</template>
