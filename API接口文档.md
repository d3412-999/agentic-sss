# 《面向对象程序设计课程设计（Java）》智能答辩与评分系统 · API 接口文档

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0.0 |
| 适用端 | 教师端 Web、学生端 Web（Vue 3 + Element Plus） |
| 后端 | Spring Boot 3.x（Java 17）+ MySQL 8 + Redis + 对象存储 |
| AI 服务 | Python FastAPI + LangChain + LightRAG（内网调用，见第 21 章） |
| 传输协议 | HTTPS + JSON；语音答辩为 WebSocket（见第 17 章） |
| Base URL | `https://{host}/api/v1` |
| 字符集 | UTF-8 |
| 最近更新 | 2026-10-22 |

---

## 目录

1. 概述（角色、权限、流程、假设）
2. 通用约定（认证、响应包、分页、错误码、上传、异步任务）
3. 认证与用户 `/auth`
4. 课程与开课 `/teacher/offerings`
5. 学生名单 `/teacher/offerings/{id}/students`
6. 题目 `/topics`
7. 学生选题 `/student/selections`
8. 提交物（源码 / 报告）`/submissions`
9. 源码静态分析 `/submissions/{id}/code-analysis`
10. 报告评审 `/submissions/{id}/report-evaluation`
11. 答辩 `/student/defenses`
12. 评分与成绩 `/grades`
13. 教师复核 `/teacher/defenses`
14. 文件与后台任务 `/files` `/tasks`
15. 通知 `/notifications`
16. 统计看板 `/teacher/offerings/{id}/dashboard`
17. WebSocket 语音答辩协议
18. 状态机
19. 数据字典（枚举）
20. 关键时序图
21. AI 服务内部契约（Java ⇄ Python）
22. 前后端落地建议
23. 变更记录

---

## 1. 概述

### 1.1 业务范围

系统覆盖两个阶段：

- **选题阶段**：教师创建开课 → 导入学生名单 → 上传并发布题目；学生登录 → 选择课程 → 选择一道或多道题目 → 上传源码与课程设计报告。
- **答辩阶段**：系统依据「所选题目 + 源码 + 报告」自动生成答辩题目 → 学生与系统进行**面对面语音答辩** → 系统对答辩录音、源码质量、报告质量分别评分并汇总 → 教师回看录像、复核材料与分数 → 教师发布并导出成绩。

### 1.2 角色与权限矩阵

| 功能 | 学生 STUDENT | 教师 TEACHER | 说明 |
|---|:---:|:---:|---|
| 登录 / 改密 | ✅ | ✅ | 统一账号体系 |
| 创建课程、开课、发布 | ❌ | ✅ | 仅本人创建的开课 |
| 导入学生名单 | ❌ | ✅ | 仅本人开课 |
| 上传 / 发布题目 | ❌ | ✅ | 仅本人开课 |
| 浏览可选的课程与题目 | ✅ | 👁 只读 | 学生只能看已发布 |
| 选题 / 退选 | ✅ | ❌ | 受时间窗与题目容量限制 |
| 上传源码 / 报告 | ✅ | 👁 只读 | 仅本人提交，截止前可覆盖 |
| 发起答辩、语音作答 | ✅ | ❌ | 需材料齐全 |
| 查看本人成绩 | ✅ | — | 教师发布后可见 |
| 复核答辩录音、改分 | ❌ | ✅ | 改分留痕 |
| 发布成绩、导出成绩 | ❌ | ✅ | 导出 Excel |
| 查看他人材料 | ❌ | ✅（本开课内） | 越权返回 40301 |

> 约定：教师只能操作 `offering.teacherId == 当前用户` 的数据；学生只能操作 `selection.studentId == 当前用户` 的数据。

### 1.3 业务总流程

```mermaid
flowchart LR
  A[教师创建开课] --> B[导入学生名单]
  B --> C[上传题目并发布]
  C --> D[学生选课/选题]
  D --> E[上传源码 + 报告]
  E --> F[系统自动分析: 源码/报告]
  F --> G[学生进入答辩]
  G --> H[系统生成答辩题]
  H --> I[语音答辩 + 实时转写]
  I --> J[系统自动评分]
  J --> K[教师复核录像与材料]
  K --> L[教师发布成绩]
  L --> M[导出成绩单]
```

### 1.4 核心实体关系

```mermaid
erDiagram
  COURSE ||--o{ OFFERING : "开课"
  OFFERING ||--o{ OFFERING_STUDENT : "名单"
  OFFERING ||--o{ TOPIC : "题目"
  STUDENT ||--o{ SELECTION : "选题"
  TOPIC ||--o{ SELECTION : "被选"
  SELECTION ||--o{ SUBMISSION : "源码/报告"
  SELECTION ||--o{ DEFENSE_SESSION : "答辩"
  DEFENSE_SESSION ||--o{ DEFENSE_QUESTION : "答辩题"
  DEFENSE_SESSION ||--o{ DEFENSE_ANSWER : "作答"
  DEFENSE_SESSION ||--o{ SCORE : "评分"
  SELECTION ||--o{ GRADE : "最终成绩"
```

### 1.5 关键假设与约束

1. **开课（offering）** = 某学期某门课的一个教学班，是绝大多数接口的作用域。
2. 一个学生可同时选择**多道题目**（不设上限）；**每道题独立**提交源码与报告、独立进行源码/报告评分；**答辩按学生组织一次**（固定 AI 异步答辩），题目覆盖其全部所选题目。
3. 源码为 `.zip`（≤ 100 MB，解压后 ≤ 500 MB、≤ 3000 个文件）；报告为 `.pdf` / `.docx`（≤ 50 MB）。
4. 答辩为**实时语音**：浏览器采集 → WebSocket 上行音频帧 → 服务端 ASR 流式转写；断线可重连续答；同时保留 HTTP 分片上传作为兜底。
5. 自动评分**幂等**，可重跑；教师可覆盖，覆盖后进入 `LOCKED` 状态。
6. 默认权重：答辩 40% + 源码 35% + 报告 25%，教师可按开课调整，三者之和须为 100。
7. 源码静态检查（Checkstyle / PMD / SpotBugs）可由 Java 侧 `subprocess` 直接调用 JVM 工具；语义级评审（可读性、设计合理性）与答辩题生成、语音评分由 Python AI 服务完成。

---

## 2. 通用约定

### 2.1 环境与地址

| 环境 | Base URL |
|---|---|
| 本地开发 | `http://localhost:8080/api/v1` |
| 测试 | `https://test-sss.example.edu.cn/api/v1` |
| 生产 | `https://sss.example.edu.cn/api/v1` |

WebSocket：`wss://{host}/ws/v1/defenses/{defenseId}?ticket={ticket}`

### 2.2 认证

- 登录成功后返回 `accessToken`（JWT，有效期 **2 小时**）与 `refreshToken`（**7 天**，一次性轮换）。
- 除 `/auth/login`、`/auth/refresh` 外，所有接口必须携带：

```http
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

- JWT Claims 约定：

```json
{
  "sub": "2023010101",
  "uid": "1839201930123456789",
  "role": "STUDENT",
  "name": "张三",
  "exp": 1760000000,
  "jti": "b7f2c1d0-..."
}
```

- 权限不足返回 `40301`；Token 过期返回 `40101`，前端应静默调用 `/auth/refresh` 后重放原请求（最多一次）。

### 2.3 统一响应包

```json
{
  "code": 0,
  "message": "ok",
  "data": {},
  "traceId": "6b1f0c2e9a7d4f13"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| code | int | `0` 成功；非 0 见 2.5 |
| message | string | 面向用户的提示，可直接 toast |
| data | object/array/null | 业务数据 |
| traceId | string | 链路追踪 ID，报障时提供 |

HTTP 状态码与 `code` 并用：成功统一 `200`（创建可为 `201`），异步受理 `202`，参数错误 `400`，鉴权 `401`，越权 `403`，不存在 `404`，状态冲突 `409`，限流 `429`，服务端 `500`。

### 2.4 分页、排序与筛选

请求 Query：

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| page | int | 1 | 从 1 开始 |
| size | int | 20 | 最大 100 |
| sort | string | 由接口定义 | 例：`createdAt,desc` |

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "list": [],
    "total": 128,
    "page": 1,
    "size": 20,
    "pages": 7
  },
  "traceId": "6b1f0c2e9a7d4f13"
}
```

### 2.5 错误码

| code | HTTP | 含义 | 前端处理建议 |
|---|---|---|---|
| 0 | 200 | 成功 | — |
| 40001 | 400 | 参数校验失败（`data.errors` 给出字段级信息） | 表单回显 |
| 40002 | 400 | 文件类型/大小不合法 | 提示重选 |
| 40101 | 401 | 未登录或 Token 过期 | 刷新或跳登录 |
| 40102 | 401 | 账号或密码错误 | 提示 |
| 40103 | 401 | 账号被禁用 | 联系教师 |
| 40301 | 403 | 越权访问 | 提示无权限 |
| 40401 | 404 | 资源不存在 | 返回列表 |
| 40901 | 409 | 状态冲突（如开课未发布即选题） | 刷新状态 |
| 40902 | 409 | 重复操作（重复选题/重复发布） | 幂等提示 |
| 41301 | 413 | 请求体过大 | 分片上传 |
| 42201 | 422 | 业务规则不满足（容量已满、截止时间已过） | 展示具体原因 |
| 42202 | 422 | 材料不齐（缺源码/报告），不能答辩 | 跳转补交 |
| 42901 | 429 | 请求过于频繁 | 退避重试 |
| 50000 | 500 | 服务端异常 | 提示 + traceId |
| 50301 | 503 | AI 服务不可用 | 稍后重试，可转人工 |

字段级错误示例：

```json
{
  "code": 40001,
  "message": "参数校验失败",
  "data": {
    "errors": [
      { "field": "semester", "message": "学期格式应为 2026-2027-1" },
      { "field": "weights", "message": "三项权重之和必须为 100" }
    ]
  },
  "traceId": "6b1f0c2e9a7d4f13"
}
```

### 2.6 文件上传

- 统一使用 `multipart/form-data`，文件字段名固定为 `file`。
- 单文件上限由各接口注明；网关层默认 110 MB（`spring.servlet.multipart.max-file-size`）。
- 建议前端先计算 SHA-256 放入请求头 `X-File-Sha256`，服务端命中相同哈希可直接复用（秒传）。
- 上传成功返回 `fileId`，后续业务接口只传 `fileId`；下载统一走 `/files/{fileId}/download`。
- 服务端对压缩包做安全校验：禁止路径穿越、符号链接、可执行文件；解压失败返回 `40002`。

### 2.7 异步任务

耗时操作（题目生成、语音评分、报告评审、成绩导出）采用「受理 + 轮询」：

1. 提交接口返回 `202`：

```json
{ "code": 0, "message": "accepted", "data": { "taskId": "tk_9f2c...", "status": "PENDING" } }
```

2. 轮询 `GET /tasks/{taskId}`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "taskId": "tk_9f2c...",
    "type": "DEFENSE_QUESTION_GEN",
    "status": "RUNNING",
    "progress": 60,
    "result": null,
    "error": null,
    "createdAt": "2026-10-22T10:00:00+08:00",
    "finishedAt": null
  },
  "traceId": "6b1f0c2e9a7d4f13"
}
```

`status`：`PENDING | RUNNING | SUCCEEDED | FAILED | CANCELED`。轮询建议 1s 起、指数退避至 5s，超过 10 分钟提示失败。

### 2.8 数据格式

- **ID**：雪花 ID，序列化为 **字符串**（避免 JS 精度丢失）。
- **时间**：ISO-8601 带时区，如 `2026-10-22T10:00:00+08:00`；仅日期用 `2026-10-22`。
- **枚举**：大写蛇形，如 `IN_PROGRESS`。
- **金额/分数**：分数用 `number`，保留 1 位小数（如 `87.5`）。
- **富文本**：`description` 允许 Markdown，前端渲染前需 XSS 过滤。

### 2.9 幂等、并发与限流

- 写接口可携带 `Idempotency-Key: <uuid>`，服务端 24h 内去重，重复请求返回首次结果。
- 更新类接口携带 `If-Match: <version>` 或请求体 `version`，版本不一致返回 `40901`（乐观锁）。
- 单用户接口限流：默认 20 QPS；登录 5 次/分钟；答辩 WebSocket 每会话 1 条连接（重连踢旧连接）。

---

## 3. 认证与用户 `/auth`

### 3.1 登录

`POST /auth/login` ｜ 权限：匿名

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 学号 / 工号 |
| password | string | 是 | 密码（首次为教师导入时生成的初始密码） |
| role | string | 否 | `STUDENT` / `TEACHER`，用于前端区分入口，服务端以账号真实角色为准 |

```json
{ "username": "2023010101", "password": "Ss@123456", "role": "STUDENT" }
```

**响应 data**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "rt_7d1c9a...",
  "tokenType": "Bearer",
  "expiresIn": 7200,
  "user": {
    "id": "1839201930123456789",
    "username": "2023010101",
    "name": "张三",
    "role": "STUDENT",
    "className": "计科2301",
    "avatarUrl": null,
    "mustChangePassword": false
  }
}
```

错误：`40102` 账号或密码错误；`40103` 账号被禁用；`42901` 登录过于频繁。

### 3.2 刷新 Token

`POST /auth/refresh` ｜ 权限：匿名（携带 refreshToken）

```json
{ "refreshToken": "rt_7d1c9a..." }
```

响应同 3.1（refreshToken 轮换，旧 token 立即失效）。错误：`40101` 刷新令牌无效或已使用。

### 3.3 退出登录

`POST /auth/logout` ｜ 权限：已登录

请求头携带 `Authorization`；服务端将当前 `jti` 加入 Redis 黑名单至过期。响应 `data: null`。

### 3.4 当前用户

`GET /auth/me` ｜ 权限：已登录

```json
{
  "id": "1839201930123456789",
  "username": "2023010101",
  "name": "张三",
  "role": "STUDENT",
  "className": "计科2301",
  "email": "zhangsan@example.edu.cn",
  "offerings": [
    { "offeringId": "1839201930199990001", "courseName": "面向对象程序设计课程设计(Java)", "semester": "2026-2027-1" }
  ]
}
```

### 3.5 修改密码

`PUT /auth/password` ｜ 权限：已登录

```json
{ "oldPassword": "Ss@123456", "newPassword": "Ss@654321" }
```

规则：长度 ≥ 8，含字母与数字；新旧不得相同。成功后所有 refreshToken 失效，前端跳登录。

---

## 4. 课程与开课 `/teacher`

### 4.1 课程模板列表

`GET /teacher/courses?keyword=Java` ｜ 权限：TEACHER

```json
{
  "list": [
    {
      "id": "1839000000000000001",
      "code": "CS3001",
      "name": "面向对象程序设计课程设计(Java)",
      "credit": 2.0,
      "description": "以 Java 为载体，完成一个完整小型系统的设计与实现"
    }
  ],
  "total": 1, "page": 1, "size": 20, "pages": 1
}
```

### 4.2 新建课程模板

`POST /teacher/courses` ｜ 权限：TEACHER

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| code | string | 是 | 课程代码，唯一 |
| name | string | 是 | 课程名称 |
| credit | number | 否 | 学分 |
| description | string | 否 | 课程简介，Markdown |

### 4.3 开课列表

`GET /teacher/offerings?semester=2026-2027-1&status=PUBLISHED&keyword=&page=1&size=20` ｜ 权限：TEACHER（仅本人）

`status`：`DRAFT | PUBLISHED | CLOSED | ARCHIVED`

```json
{
  "list": [
    {
      "id": "1839201930199990001",
      "courseName": "面向对象程序设计课程设计(Java)",
      "semester": "2026-2027-1",
      "className": "计科2301",
      "status": "PUBLISHED",
      "studentCount": 42,
      "topicCount": 8,
      "selectionCount": 96,
      "defenseFinishedCount": 30,
      "createdAt": "2026-09-01T09:00:00+08:00"
    }
  ],
  "total": 1, "page": 1, "size": 20, "pages": 1
}
```

### 4.4 创建开课

`POST /teacher/offerings` ｜ 权限：TEACHER ｜ 支持 `Idempotency-Key`

```json
{
  "semester": "2026-2027-1",
  "className": "计科2301",
  "description": "本学期课程设计，要求 2~3 人组队或独立完成",
  "weights": { "defense": 40, "code": 35, "report": 25 },
  "defenseConfig": {
    "maxAnswerSeconds": 180,
    "allowRetry": false,
    "language": "zh-CN",
    "enableVideo": false
  },
  "timeline": {
    "selectStart": "2026-09-10T00:00:00+08:00",
    "selectEnd": "2026-09-20T23:59:59+08:00",
    "submitDeadline": "2026-10-15T23:59:59+08:00",
    "defenseStart": "2026-10-20T08:00:00+08:00",
    "defenseEnd": "2026-10-30T18:00:00+08:00"
  }
}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| courseId | string | 是 | 必须存在 |
| semester | string | 是 | `YYYY-YYYY-N` |
| className | string | 是 | ≤ 50 字 |
| topicSelectLimit | int | 否 | 1~10，默认 3 |
| weights | object | 否 | 三项整数，和 = 100 |
| defenseConfig.defenseMode | string | 否 | **已移除**：固定走 AI 异步答辩，传值会被忽略 |
| defenseConfig.questionCount | int | 否 | **已废弃**：不再作为教学班配置项，传值会被忽略；答辩题数在发起答辩时指定，未指定用服务端默认值 |
| defenseConfig.maxAnswerSeconds | int | 否 | 60~600，默认 180 |
| defenseConfig.enableVideo | bool | 否 | 是否同时录制视频（默认仅音频） |

响应：完整 offering 对象，`status = DRAFT`。

### 4.5 开课详情

`GET /teacher/offerings/{offeringId}` ｜ 权限：TEACHER（本人）

返回 4.4 全部字段 + 统计 + `publishedAt`、`version`。

### 4.6 修改开课

`PATCH /teacher/offerings/{offeringId}` ｜ 权限：TEACHER（本人）

- 仅 `DRAFT` 可改全部字段；`PUBLISHED` 只允许改 `timeline`（只能延后）、`defenseConfig`、`description`。
- 请求体携带 `version` 做乐观锁，冲突返回 `40901`。
- 权重调整后，未发布成绩的自动重算（见 12.7）。

### 4.7 发布开课

`POST /teacher/offerings/{offeringId}/publish` ｜ 权限：TEACHER（本人）

前置校验（全部满足才允许发布，否则 `42201` 并返回 `data.blockers`）：

- 学生名单 ≥ 1
- 已发布题目 ≥ 1
- `timeline.submitDeadline` 晚于当前时间
- 权重和为 100

```json
{
  "code": 42201,
  "message": "发布条件不满足",
  "data": {
    "blockers": [
      { "code": "NO_STUDENT", "message": "学生名单为空，请先导入" },
      { "code": "NO_TOPIC", "message": "尚未发布任何题目" }
    ]
  },
  "traceId": "6b1f0c2e9a7d4f13"
}
```

成功后 `status = PUBLISHED`，学生端立即可见。

### 4.8 关闭 / 归档开课

- `POST /teacher/offerings/{offeringId}/close`：停止选题与提交，已提交材料仍可复核评分。
- `POST /teacher/offerings/{offeringId}/archive`：结课归档，仅教师可读，学生端隐藏。

### 4.9 开课统计看板

`GET /teacher/offerings/{offeringId}/dashboard` ｜ 权限：TEACHER（本人）

```json
{
  "studentCount": 42,
  "selectedStudentCount": 40,
  "selectionCount": 96,
  "submissionRate": 0.93,
  "sourceSubmittedCount": 39,
  "reportSubmittedCount": 38,
  "defenseFinishedCount": 30,
  "defensePendingCount": 10,
  "averageScore": { "defense": 82.4, "code": 78.1, "report": 80.6, "final": 80.6 },
  "scoreDistribution": [
    { "range": "90-100", "count": 6 },
    { "range": "80-89", "count": 15 },
    { "range": "70-79", "count": 12 },
    { "range": "60-69", "count": 6 },
    { "range": "0-59", "count": 1 }
  ],
  "topIssues": [
    { "tool": "PMD", "rule": "UnusedLocalVariable", "count": 41 },
    { "tool": "Checkstyle", "rule": "JavadocMethod", "count": 37 }
  ]
}
```

---

## 5. 学生名单 `/teacher/offerings/{offeringId}/students`

导入是**两段式**：先 `parse-*` 拿到逐行预览（只读、不写库），教师确认后 `confirm` 真正入库。
行级问题（重复学号、已在本班、姓名为空）不会让整批失败——预览里逐行标注，确认后逐行返回结果。

### 5.0 导入限额（前端提示与后端校验共用同一份）

`GET /meta/student-import-policy` ｜ 无需登录

```json
{ "allowedExtensions": ["xlsx","xls","docx","csv"], "maxFileBytes": 2097152, "maxRows": 500 }
```

`GET /teacher/offerings/{offeringId}/students/import/policy` 返回同一份值（带归属校验）。

### 5.1 下载导入模板

`GET /teacher/offerings/{offeringId}/students/import/template` ｜ 权限：TEACHER（本人）

返回 xlsx 二进制（`学生名单导入样例.xlsx`）。表头固定 `学号 / 姓名 / 邮箱`（邮箱可空），
学号列预设为文本格式，避免 Excel 把 2023010104 显示成科学计数法。

### 5.2 解析：文本粘贴

`POST /teacher/offerings/{offeringId}/students/import/parse-text` ｜ 权限：TEACHER（本人）

```json
{ "text": "2023010104 赵六\n2023010105 钱七" }
```

分隔符自动识别：制表符 → 英文/中文逗号 → 空白（含全角空格）→ `学号-姓名`；
首行若是「学号 姓名」表头会自动跳过（`headerDetected=true`）。

### 5.3 解析：文件上传

`POST /teacher/offerings/{offeringId}/students/import/parse-file` ｜ `multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| file | file | 是 | `.xlsx` / `.xls` / `.docx` / `.csv`，≤ 2 MB，≤ 500 行；`.doc` 会提示另存为 |

xlsx/xls 读第一个 sheet 的前三列；docx 优先取第一个多列表格，没有表格时按段落文本解析；
csv 先按 UTF-8 严格解码，失败回退 GBK。

### 5.4 预览结构（`PreviewVO`）

```json
{
  "rows": [
    { "rowNo": 2, "studentNo": "2023010101", "name": "张三", "email": "", "state": "IN_ROSTER", "message": "已在本班，将跳过" },
    { "rowNo": 3, "studentNo": "2023010104", "name": "赵六", "email": "", "state": "NEW", "message": "将创建账号并加入本班" },
    { "rowNo": 4, "studentNo": "2023010104", "name": "赵六", "email": "", "state": "INVALID", "message": "与第 3 行学号重复" }
  ],
  "total": 3, "importable": 1, "skipped": 1, "invalid": 1,
  "headerDetected": false, "hint": null, "source": "TEXT"
}
```

| state | 含义 | 是否会被提交 |
|---|---|---|
| `NEW` | 系统里没有这个学号，将建号并入班 | 是 |
| `LINK` | 账号已存在（学生角色），只建名单关系；姓名以系统内为准 | 是 |
| `IN_ROSTER` | 已在本班，跳过 | 否 |
| `INVALID` | 该行不合法（学号格式/姓名空/批内重号/被教师账号占用） | 否 |

> 行级问题走 HTTP 200 + 逐行 `state`，**不是 4xx**：一次导入 200 行里有个别脏数据，
> 直接整批 400 会让老师没法继续。前端表格逐行展示状态与原因，并支持「只看异常 / 下载错误清单」。

### 5.5 确认导入

`POST /teacher/offerings/{offeringId}/students/import/confirm` ｜ 权限：TEACHER（本人）｜ 幂等：`Idempotency-Key`

```json
{ "rows": [ { "studentNo": "2023010104", "name": "赵六", "email": "zhaoliu@sss.local", "rowNo": 3 } ] }
```

```json
{
  "total": 2, "created": 1, "linked": 1, "skipped": 0, "failed": 0,
  "rows": [
    { "rowNo": 3, "studentNo": "2023010104", "name": "赵六", "result": "CREATED", "message": "已创建账号并加入本班" },
    { "rowNo": 6, "studentNo": "2023010105", "name": "钱七", "result": "LINKED",  "message": "已加入本班（保留系统姓名：钱七）" }
  ]
}
```

`result`：`CREATED` / `LINKED` / `SKIPPED` / `FAILED`。

**事务策略**：按 `import-chunk-size`（默认 200）分批提交，每批一个短事务；
某批抛异常时自动退回**逐行事务**，把失败精确到行，不让同批其他行陪葬。
同一 `Idempotency-Key` 重复提交返回 `40902`；换 key 再提交同一批只会全部 `SKIPPED`（不产生重复账号或重复关联）。

**初始密码**：默认 `密码 = 学号`（`sss.student.default-password-mode=STUDENT_NO`），
一律置 `must_change_password=1`，首次登录强制改密；接口**不返回**密码。
改成 `FIXED` 时所有新账号使用 `sss.student.default-password`。

### 5.5.1 批量移除

`POST /teacher/offerings/{offeringId}/students/batch-remove` ｜ 权限：TEACHER（本人）

```json
{ "studentIds": ["1839201930123456789", "1839201930123456790"] }
```

```json
{ "total": 2, "removed": 1, "failed": 1,
  "rows": [ { "studentId": "…789", "name": "张三", "success": true,  "message": "已移出本班" },
            { "studentId": "…790", "name": "李四", "success": false, "message": "该生已有提交物或成绩，不能从名单移除" } ] }
```

### 5.5.2 名单导出

`GET /teacher/offerings/{offeringId}/students/export` → CSV（学号 / 姓名 / 班级 / 状态 / 选题数 / 最近登录，带 BOM 防 Excel 乱码）。

### 5.6 学生列表

### 5.6 启用 / 禁用

`PATCH /teacher/offerings/{offeringId}/students/{studentId}` ｜ 权限：TEACHER（本人）

```json
{ "status": "DISABLED" }
```

禁用后该生无法登录，已提交材料保留。

### 5.7 移出名单

`DELETE /teacher/offerings/{offeringId}/students/{studentId}` ｜ 权限：TEACHER（本人）

仅当该生**无任何提交与答辩记录**时允许，否则 `42201`。


---

## 6. 题目 `/topics`

题目属于某个开课。**V9 起题目没有草稿/发布/归档状态**：创建即对学生可见、可选。
题目只有四个业务字段：标题、描述、难度、附件；交付要求、标签、期望技术栈、容量上限均已下线。

### 6.1 题目列表（教师）

`GET /teacher/offerings/{offeringId}/topics?difficulty=&keyword=&page=1&size=20` ｜ 权限：TEACHER（本人）

```json
{
  "list": [
    {
      "id": "1839300000000000001",
      "title": "基于 JavaFX 的图书管理系统",
      "difficulty": "MEDIUM",
      "difficultyLabel": "进阶",
      "selectedCount": 4,
      "attachmentCount": 2,
      "createdAt": "2026-09-05T10:00:00+08:00",
      "updatedAt": "2026-09-18T15:20:00+08:00"
    }
  ],
  "total": 8, "page": 1, "size": 20, "pages": 1
}
```

`difficulty` 过滤接受编码（`EASY`/`MEDIUM`/`HARD`）或中文展示名；无法识别的值视为「不筛选」，不会报错。

### 6.2 新建题目

`POST /teacher/offerings/{offeringId}/topics` ｜ 权限：TEACHER（本人）

```json
{
  "title": "基于 JavaFX 的图书管理系统",
  "description": "设计并实现一个图书管理系统，支持借还、查询、统计。",
  "difficulty": "MEDIUM",
  "attachments": ["2120897397787512834", "2120897397871398913"]
}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| title | string | 是 | 2~100 字，同一开课内唯一 |
| description | string | 是 | ≤ 5000 字 |
| difficulty | string | 否 | `EASY` / `MEDIUM` / `HARD`，缺省 `MEDIUM`；非法值 `40001` |
| attachments | string[] | 否 | 已上传成功的 `fileId` 列表，单题 ≤ `maxPerTopic`（默认 20） |

`attachments` 里的文件必须满足：存在、`bucket = TOPIC_ATTACHMENT`、`created_by = 当前教师`，否则 `42201` / `40301`。

### 6.3 题目详情

`GET /topics/{topicId}` ｜ 权限：TEACHER（本人）或已选该题的学生

返回 6.2 全部字段 + `selectedCount`、`attachmentCount`、`version`、`createdAt`、`updatedAt`、`attachments[]`（含 `downloadUrl`、`contentType`、`size`）。

### 6.4 修改题目

`PUT /topics/{topicId}` ｜ 权限：TEACHER（本人）

- 请求体：`version`（必填）、`title`、`description`、`difficulty`，三者均可选。
- 已被学生选择的题目**不允许**改 `title`（`40901`），其余可改；修改留痕（`audit_log`）。

### 6.5 删除题目

`DELETE /topics/{topicId}` ｜ 权限：TEACHER（本人）

已有学生选择 → `42201`「已有学生选择该题，请先联系学生退选」。删除同时解绑附件引用，文件本体保留以便秒传复用。

### 6.6 附件管理（编辑态）

| 操作 | 方法与路径 | 说明 |
|---|---|---|
| 追加 | `POST /topics/{topicId}/attachments` | 请求体 `{ "fileIds": ["..."] }`，返回最新附件列表；重复 fileId 自动忽略 |
| 删除 | `DELETE /topics/{topicId}/attachments/{fileId}` | 解绑；该文件不再被任何题目引用时连同磁盘文件清理 |
| 排序 | `PUT /topics/{topicId}/attachments/order` | 请求体 `{ "fileIds": [...] }`，顺序即新的 `sort_no`，集合必须与当前一致 |

### 6.7 文件上传 `/files`

`POST /files/batch` ｜ `multipart/form-data`，字段名 `files`（可重复）｜ 权限：TEACHER

```json
{
  "succeeded": [
    { "fileId": "2120897397787512834", "filename": "题目说明.pdf", "size": 69215,
      "sha256": "cfa318…", "contentType": "application/pdf" }
  ],
  "failed": [
    { "filename": "fake.pdf", "reason": "「fake.pdf」内容与扩展名不符，已拒绝（请勿修改文件后缀）" }
  ]
}
```

- 数量/总量超限 → `40001`（写盘前拦截，不产生半成品文件）；单文件格式/大小问题 → 逐条 `failed`，其余文件照常成功。
- 三重校验：扩展名白名单 → 文件头魔数（PDF `%PDF`、DOC OLE2、DOCX 需含 `word/` 条目）→ MIME。
- 限额来自 `sss.upload.topic-attachment.*`，前端可用 `GET /meta/upload-policy` 读取同一份配置。
- 兼容入口：`POST /files`（单文件，字段 `file`）。

`GET /files/{fileId}/download?inline=true` ｜ 权限：上传者 / 教师 / 题目附件所属开课名单内学生

- `inline=true` 且类型为 PDF 时返回 `Content-Disposition: inline`，供前端内嵌预览；其它类型强制 `attachment`。
- 固定返回 `X-Content-Type-Options: nosniff`。

### 6.8 字典

`GET /meta/difficulties` → `[{ "code": "EASY", "label": "入门", "sort": 10 }, …]`
`GET /meta/upload-policy` → `{ "allowedExtensions": ["pdf","doc","docx"], "maxFileBytes": 20971520, "maxFilesPerRequest": 5, "maxTotalBytes": 52428800, "maxPerTopic": 20 }`

> 这两个字典接口**无需登录**（`/api/v1/meta/**` 已放行）：内容是静态常量，不含用户数据；
> 放行后即使 token 过期，难度筛选条也能拿到字典，不会静默回退到前端兜底值。

> 已移除：`POST /topics/{id}/publish`、`POST /topics/{id}/archive`、`POST /teacher/offerings/{id}/topics/publish`、`POST /teacher/offerings/{id}/topics/import`。

---

## 7. 学生选题 `/student`

### 7.1 可选课程列表

`GET /student/offerings?semester=2026-2027-1&keyword=&page=1&size=20` ｜ 权限：STUDENT

只返回 `PUBLISHED` 且当前学生在名单内的开课。

```json
{
  "list": [
    {
      "offeringId": "1839201930199990001",
      "courseName": "面向对象程序设计课程设计(Java)",
      "teacherName": "李老师",
      "semester": "2026-2027-1",
      "className": "计科2301",
      "selectStart": "2026-09-10T00:00:00+08:00",
      "selectEnd": "2026-09-20T23:59:59+08:00",
      "submitDeadline": "2026-10-15T23:59:59+08:00",
      "topicSelectLimit": 3,
      "mySelectionCount": 2,
      "canSelect": true,
      "cannotSelectReason": null
    }
  ],
  "total": 1, "page": 1, "size": 20, "pages": 1
}
```

`cannotSelectReason` 取值示例：`NOT_IN_WINDOW`、`LIMIT_REACHED`、`CLOSED`。

### 7.2 题目列表（学生）

`GET /student/offerings/{offeringId}/topics?keyword=&difficulty=&page=1&size=20` ｜ 权限：STUDENT（名单内）

```json
{
  "list": [
    {
      "id": "1839300000000000001",
      "title": "基于 JavaFX 的图书管理系统",
      "summary": "设计并实现一个图书管理系统……",
      "difficulty": "MEDIUM",
      "difficultyLabel": "进阶",
      "selectedCount": 4,
      "attachmentCount": 2,
      "selectedByMe": false
    }
  ],
  "total": 8, "page": 1, "size": 20, "pages": 1
}
```

题目没有状态字段与容量字段：只要属于该开课就可见，`selectedCount` 只是「已选人数」参考值，不代表能否再选。

### 7.3 题目详情（学生）

`GET /student/offerings/{offeringId}/topics/{topicId}` ｜ 权限：STUDENT（名单内）

返回完整描述、难度、`selectedCount`、`selectedByMe`、`attachments[]`（PDF 可内嵌预览，Word 仅下载）。

### 7.4 选题（支持多选）

`POST /student/selections` ｜ 权限：STUDENT（名单内）｜ 支持 `Idempotency-Key`

```json
{ "offeringId": "1839201930199990001", "topicIds": ["1839300000000000001", "1839300000000000003"] }
```

**业务规则**（违反任一返回 `42201`，`data.reason` 给出具体原因）：

1. 开课处于 `PUBLISHED`，且在 `[selectStart, selectEnd]` 内；
2. 题目属于该开课且未被删除（V9 起无「未发布」概念）；
3. 本次 + 已有选题数 ≤ `topicSelectLimit`（`null` = 不限）；
4. 原子计数：并发下用单条 UPDATE 递增，题目已删除时返回 `42201 TOPIC_UNAVAILABLE`；
5. 不可重复选择同一题目（`40902`）。

**响应**

```json
{
  "selections": [
    {
      "id": "1840100000000000001",
      "topicId": "1839300000000000001",
      "topicTitle": "基于 JavaFX 的图书管理系统",
      "status": "SELECTED",
      "createdAt": "2026-09-12T09:30:00+08:00"
    },
    {
      "id": "1840100000000000002",
      "topicId": "1839300000000000003",
      "topicTitle": "校园二手交易平台",
      "status": "SELECTED",
      "createdAt": "2026-09-12T09:30:00+08:00"
    }
  ]
}
```

### 7.5 我的选题

`GET /student/selections?offeringId=1839201930199990001` ｜ 权限：STUDENT

```json
{
  "list": [
    {
      "id": "1840100000000000001",
      "offeringId": "1839201930199990001",
      "topic": { "id": "1839300000000000001", "title": "基于 JavaFX 的图书管理系统", "difficulty": "MEDIUM" },
      "status": "SUBMITTED",
      "progress": {
        "sourceUploaded": true,
        "reportUploaded": true,
        "codeAnalyzed": true,
        "reportEvaluated": true,
        "defenseStatus": "NOT_STARTED"
      },
      "scores": { "code": 81.0, "report": 84.5, "defense": null, "final": null },
      "submitDeadline": "2026-10-15T23:59:59+08:00",
      "version": 3
    }
  ],
  "total": 2, "page": 1, "size": 20, "pages": 1
}
```

`status`：`SELECTED | SUBMITTED | DEFENDING | DEFENDED | GRADED | CANCELED`

### 7.6 退选

`DELETE /student/selections/{selectionId}` ｜ 权限：STUDENT（本人）

- 仅 `SELECTED`、未提交任何材料、且在当前时间允许的范围内时才可退选；
- 退选释放题目名额；成功后 `status = CANCELED`（软删除，保留操作日志）。

### 7.7 选题详情

`GET /student/selections/{selectionId}` ｜ 权限：STUDENT（本人）或 TEACHER（本开课）

返回题目完整信息、提交物列表、各维度评分与评语（未发布成绩时 `finalScore` 为 `null`，`scoresVisible = false`）。

---

## 8. 提交物（源码 / 报告）`/submissions`

一次「上传」生成一个 **submission 版本**；同一 `selection` 可保留多个历史版本，评分始终取**最新有效版本**。

### 8.1 上传源码

`POST /student/selections/{selectionId}/source` ｜ 权限：STUDENT（本人）

`multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| file | file | 是 | `.zip`（也接受 `.jar` 源码包），≤ 100 MB |
| remark | string | 否 | 版本说明，≤ 200 字 |
| analysisProfile | string | 否 | `JAVA_MAVEN` / `JAVA_GRADLE` / `JAVA_PLAIN`，默认自动识别 |

**响应**

```json
{
  "submissionId": "1840200000000000001",
  "selectionId": "1840100000000000001",
  "type": "SOURCE",
  "version": 2,
  "status": "PARSING",
  "file": {
    "fileId": "f_7c2a91...",
    "filename": "bookstore-v2.zip",
    "size": 1843200,
    "sha256": "9f2c..."
  },
  "parseTaskId": "tk_3a91...",
  "createdAt": "2026-10-10T21:02:00+08:00"
}
```

服务端异步解析（`GET /tasks/{parseTaskId}` 或 8.4 查询）：

- 解压与安全校验、统计 `.java` 文件数与有效代码行；
- 识别构建方式（`pom.xml` / `build.gradle`）；
- 语法粗检（编译失败不阻断，但会在分析结果中标注 `compilePassed=false`）；
- 结果写入 `submission.parse_result`。

解析失败示例：`status = INVALID`，`data.errors = [{ "code": "ZIP_SLIP", "message": "压缩包包含非法路径 ../" }]`。

### 8.2 上传课程设计报告

`POST /student/selections/{selectionId}/report` ｜ 权限：STUDENT（本人）

`multipart/form-data`：`file`（`.pdf` / `.docx`，≤ 50 MB）、`remark`。

```json
{
  "submissionId": "1840200000000000002",
  "type": "REPORT",
  "version": 1,
  "status": "PARSING",
  "pageCount": 24,
  "parseTaskId": "tk_4b02..."
}
```

服务端将 PDF/DOCX 转为文本 + 结构树（章节、图、表），供报告评分与答辩题生成使用。

### 8.3 提交记录

`GET /student/selections/{selectionId}/submissions` ｜ 权限：STUDENT（本人）或 TEACHER（本开课）

```json
{
  "list": [
    { "id": "1840200000000000002", "type": "REPORT", "version": 1, "status": "VALID", "size": 1048576, "createdAt": "2026-10-10T21:10:00+08:00" },
    { "id": "1840200000000000001", "type": "SOURCE", "version": 2, "status": "VALID", "size": 1843200, "createdAt": "2026-10-10T21:02:00+08:00" }
  ],
  "total": 2
}
```

`status`：`UPLOADED | PARSING | VALID | INVALID | ANALYZING | ANALYZED`

### 8.4 提交详情

`GET /submissions/{submissionId}` ｜ 权限：本人或本开课教师

```json
{
  "id": "1840200000000000001",
  "type": "SOURCE",
  "version": 2,
  "status": "ANALYZED",
  "file": { "fileId": "f_7c2a91...", "filename": "bookstore-v2.zip", "size": 1843200, "sha256": "9f2c..." },
  "parseResult": {
    "javaFileCount": 42,
    "totalLines": 6840,
    "codeLines": 5120,
    "buildTool": "MAVEN",
    "compilePassed": true,
    "packages": ["com.example.bookstore"],
    "warnings": []
  },
  "analysisId": "ca_5d10...",
  "createdAt": "2026-10-10T21:02:00+08:00",
  "updatedAt": "2026-10-10T21:09:00+08:00"
}
```

### 8.5 下载提交物

`GET /submissions/{submissionId}/download?mode=url` ｜ 权限：本人或本开课教师

```json
{ "url": "https://oss.example.edu.cn/sss/2026/...?Expires=1760000000&Signature=...", "expiresIn": 600 }
```

`mode=stream` 时直接返回二进制流（带 `Content-Disposition`）。

### 8.6 删除提交

`DELETE /submissions/{submissionId}` ｜ 权限：STUDENT（本人，截止前且未评分）

- 软删除该版本，`status = DELETED`；若删除的是最新版本，自动回退到上一有效版本。

### 8.7 重新提交（覆盖）

再次调用 8.1 / 8.2 即可，`version` 自增，旧版本保留；`selection.status` 回到 `SUBMITTED`，并触发重新分析（可配置为「仅教师确认后」）。

### 8.8 重新分析

`POST /submissions/{submissionId}/reanalyze` ｜ 权限：TEACHER（本人开课）或 STUDENT（每人每天 1 次）

```json
{ "tools": ["CHECKSTYLE", "PMD", "SPOTBUGS"], "force": true }
```

返回 `202 + taskId`，结果通过 9.2 / 9.3 查询。

---

## 9. 源码静态分析 `/submissions/{submissionId}/code-analysis`

分析结果由三部分组成：**静态规则检查**（Checkstyle / PMD / SpotBugs，JVM 子进程）、**度量**（复杂度、重复率、注释率）、**AI 语义评审**（可读性、设计合理性，Python 服务）。

### 9.1 触发分析

`POST /submissions/{submissionId}/code-analysis/run` ｜ 权限：TEACHER（本人开课）或系统自动触发

```json
{ "tools": ["CHECKSTYLE", "PMD", "SPOTBUGS"], "ruleset": "GOOGLE", "force": false }
```

返回 `202`：

```json
{ "taskId": "tk_ca_77b1...", "status": "PENDING" }
```

> 上传源码并解析成功后，系统默认自动触发一次；`force=true` 忽略缓存重跑。

### 9.2 分析结果总览

`GET /submissions/{submissionId}/code-analysis` ｜ 权限：本人或本开课教师

```json
{
  "analysisId": "ca_5d10...",
  "status": "SUCCEEDED",
  "score": 81.5,
  "grade": "良好",
  "compilePassed": true,
  "finishedAt": "2026-10-10T21:09:00+08:00",
  "summary": "整体结构清晰，分层合理；存在较多未使用变量与魔法值，部分类职责过重。",
  "dimensions": [
    { "key": "STYLE", "name": "编码规范", "weight": 25, "score": 22.0 },
    { "key": "BUG", "name": "缺陷与风险", "weight": 30, "score": 24.0 },
    { "key": "COMPLEXITY", "name": "复杂度与重复", "weight": 20, "score": 16.0 },
    { "key": "DESIGN", "name": "设计合理性", "weight": 15, "score": 12.0 },
    { "key": "DOC", "name": "注释与文档", "weight": 10, "score": 7.5 }
  ],
  "metrics": {
    "javaFileCount": 42,
    "codeLines": 5120,
    "avgCyclomaticComplexity": 3.4,
    "maxCyclomaticComplexity": 18,
    "duplicationRate": 0.061,
    "commentRate": 0.18,
    "classCount": 38,
    "maxClassMethods": 27
  },
  "issueCounts": {
    "CHECKSTYLE": { "ERROR": 3, "WARNING": 57, "INFO": 12 },
    "PMD": { "HIGH": 2, "MEDIUM": 14, "LOW": 31 },
    "SPOTBUGS": { "HIGH": 1, "MEDIUM": 6, "LOW": 9 },
    "AI": { "HIGH": 1, "MEDIUM": 3, "LOW": 5 }
  },
  "toolErrors": []
}
```

> 若某一工具执行失败（如 JVM 未安装），`status` 仍可为 `SUCCEEDED`，但 `toolErrors` 会给出 `{ "tool": "SPOTBUGS", "message": "java not found" }`，该维度按剩余工具归一化计分。

### 9.3 问题明细

`GET /submissions/{submissionId}/code-analysis/issues?tool=PMD&severity=HIGH&page=1&size=50` ｜ 权限：本人或本开课教师

```json
{
  "list": [
    {
      "id": "ci_01",
      "tool": "PMD",
      "rule": "UnusedLocalVariable",
      "severity": "MEDIUM",
      "message": "局部变量 'tmp' 声明后从未使用",
      "file": "src/main/java/com/example/bookstore/service/BookService.java",
      "line": 128,
      "column": 9,
      "codeSnippet": "List<Book> tmp = repo.findAll();",
      "ignored": false
    },
    {
      "id": "ci_02",
      "tool": "SPOTBUGS",
      "rule": "NP_NULL_ON_SOME_PATH",
      "severity": "HIGH",
      "message": "存在可能的空指针解引用",
      "file": "src/main/java/com/example/bookstore/controller/BookController.java",
      "line": 64,
      "column": null,
      "codeSnippet": "return book.getAuthor().getName();",
      "ignored": false
    }
  ],
  "total": 17, "page": 1, "size": 50, "pages": 1
}
```

### 9.4 忽略某条问题

`PATCH /submissions/{submissionId}/code-analysis/issues/{issueId}` ｜ 权限：TEACHER（本人开课）

```json
{ "ignored": true, "reason": "教学示例代码，允许魔法值" }
```

忽略后重算分数（仅教师可操作，留痕）。

### 9.5 评分细则（默认，可在开课配置覆盖）

| 维度 | 权重 | 计分方式 |
|---|---|---|
| 编码规范 STYLE | 25 | Checkstyle 违规数按千行代码密度映射到 0~100 |
| 缺陷与风险 BUG | 30 | PMD + SpotBugs 按严重度加权（HIGH=3, MEDIUM=2, LOW=1）扣分 |
| 复杂度与重复 COMPLEXITY | 20 | 平均圈复杂度、最大复杂度、重复率三项加权 |
| 设计合理性 DESIGN | 15 | AI 语义评审（分层、命名、职责、设计模式使用） |
| 注释与文档 DOC | 10 | 注释率、类/方法 Javadoc 覆盖率 |

最终 `score = Σ(dimension.score × weight / 100)`，四舍五入 1 位小数。

---

## 10. 报告评审 `/submissions/{submissionId}/report-evaluation`

### 10.1 触发评审

`POST /submissions/{submissionId}/report-evaluation/run` ｜ 权限：TEACHER（本人开课）或系统自动触发

```json
{ "force": false, "rubricId": "RUBRIC_DEFAULT" }
```

返回 `202 + taskId`。

### 10.2 评审结果

`GET /submissions/{submissionId}/report-evaluation` ｜ 权限：本人或本开课教师

```json
{
  "evaluationId": "re_31f0...",
  "status": "SUCCEEDED",
  "score": 84.5,
  "grade": "良好",
  "summary": "报告结构完整，需求分析与设计章节较扎实；测试用例覆盖不足，部分图表缺少说明。",
  "dimensions": [
    { "key": "STRUCTURE", "name": "结构完整性", "weight": 15, "score": 14.0 },
    { "key": "REQUIREMENT", "name": "需求分析", "weight": 20, "score": 17.5 },
    { "key": "DESIGN", "name": "系统设计", "weight": 25, "score": 21.0 },
    { "key": "IMPLEMENTATION", "name": "实现说明", "weight": 20, "score": 16.5 },
    { "key": "TESTING", "name": "测试与验证", "weight": 10, "score": 6.5 },
    { "key": "FORMAT", "name": "格式规范", "weight": 10, "score": 9.0 }
  ],
  "highlights": [
    "第 3 章用例图与活动图较完整",
    "对设计模式的选用给出了对比说明"
  ],
  "problems": [
    { "section": "5.2 测试", "message": "仅列出 3 条测试用例，未覆盖异常分支", "suggestion": "补充边界值与异常场景用例" },
    { "section": "4.3 类图", "message": "类图与代码不一致：缺少 BorrowService", "suggestion": "按最终代码更新类图" }
  ],
  "consistency": {
    "score": 78.0,
    "issues": ["报告中提到的 BookDAO 在源码中不存在", "报告称使用 Spring Boot，源码为原生 JDBC"]
  },
  "plagiarism": { "checked": true, "rate": 0.08, "note": "与上一届报告相似度 8%" },
  "pageCount": 24,
  "finishedAt": "2026-10-10T21:20:00+08:00"
}
```

### 10.3 评分细则（默认）

| 维度 | 权重 | 观察点 |
|---|---|---|
| 结构完整性 | 15 | 封面/摘要/目录/章节齐全，页数合理 |
| 需求分析 | 20 | 用例、功能点、非功能需求是否清晰可测 |
| 系统设计 | 25 | 架构图、类图、数据库设计、设计模式运用 |
| 实现说明 | 20 | 关键代码与设计对应，难点说明 |
| 测试与验证 | 10 | 测试用例、结果、缺陷记录 |
| 格式规范 | 10 | 图表编号、引用、排版、参考文献 |

`consistency` 为报告与源码的一致性检查（由 AI 服务结合 LightRAG 检索源码实体完成），不计入报告分，但会展示给教师作为参考。

### 10.4 重新评审

重复调用 10.1，`force=true` 覆盖旧结果并保留历史（`evaluation_version`）。教师可对 `problems` 标记「已采纳/忽略」，不影响分数。
---

## 11. 答辩 `/student/defenses`

答辩会话由学生发起，系统基于「所选题目 + 源码 + 报告」生成题目，学生通过语音逐一作答。

### 11.1 创建答辩会话

`POST /student/defenses` ｜ 权限：STUDENT（本人）｜ 支持 `Idempotency-Key`

```json
{ "offeringId": "1839201930199990001", "selectionIds": ["1840100000000000001", "1840100000000000002"] }
```

`selectionIds` 省略时表示该开课下全部有效选题。

**前置校验**（不满足返回 `42202`，`data.blockers` 指明缺什么）：

- 开课处于答辩时间窗内；
- 每个 selection 都有**有效源码**与**有效报告**；
- 源码解析、报告解析已完成；
- 无进行中的答辩会话（有则直接返回该会话，幂等）。

**响应**

```json
{
  "defenseId": "1840300000000000001",
  "offeringId": "1839201930199990001",
  "mode": "PER_STUDENT",
  "status": "CREATED",
  "selectionIds": ["1840100000000000001", "1840100000000000002"],
  "questionCount": 0,
  "createdAt": "2026-10-21T19:00:00+08:00"
}
```

`status`：`CREATED | GENERATING | READY | IN_PROGRESS | COMPLETED | EVALUATED | EXPIRED`

### 11.2 生成答辩题目

`POST /student/defenses/{defenseId}/questions:generate` ｜ 权限：STUDENT（本人）

```json
{ "questionCount": 6, "focus": ["DESIGN", "CODE"], "difficulty": "MIXED" }
```

`focus` 可选：`DESIGN`（设计决策）、`CODE`（代码细节）、`REPORT`（报告内容）、`CONCEPT`（课程知识点）、`EXTENSION`（扩展思考）。

返回 `202`：

```json
{ "taskId": "tk_qg_91c2...", "status": "PENDING", "estimatedSeconds": 40 }
```

生成完成后 `status = READY`。题目生成依据（AI 服务）：

- 题目描述与要求（避免超纲）；
- 源码 AST 摘要 + 关键类/方法（抽取学生真实写过的实现）；
- 报告章节树与关键段落；
- 历史答辩题库去重（同题不重复问同一学生）。

### 11.3 获取答辩题目

`GET /student/defenses/{defenseId}/questions` ｜ 权限：STUDENT（本人）或 TEACHER（本开课）

**答辩开始前**返回题目列表时**不含参考答案与评分要点**（防泄题），仅含题干与作答时限。

```json
{
  "list": [
    {
      "id": "dq_001",
      "order": 1,
      "type": "DESIGN",
      "difficulty": "MEDIUM",
      "question": "你在 BookService 中同时承担了库存校验与借阅记录写入，为什么这样设计？如果并发借阅同一本书会出现什么问题？",
      "sourceRefs": [
        { "type": "FILE", "path": "src/main/java/com/example/bookstore/service/BookService.java", "line": 45 },
        { "type": "REPORT_SECTION", "section": "4.2 业务逻辑设计" }
      ],
      "maxAnswerSeconds": 180,
      "expectedPoints": null
    },
    {
      "id": "dq_002",
      "order": 2,
      "type": "CODE",
      "difficulty": "HARD",
      "question": "BookController 第 64 行 `return book.getAuthor().getName();` 可能抛出空指针，你会如何修改？请说明你的判空策略。",
      "sourceRefs": [
        { "type": "FILE", "path": "src/main/java/com/example/bookstore/controller/BookController.java", "line": 64 },
        { "type": "ISSUE", "issueId": "ci_02" }
      ],
      "maxAnswerSeconds": 180,
      "expectedPoints": null
    },
    {
      "id": "dq_003",
      "order": 3,
      "type": "REPORT",
      "difficulty": "EASY",
      "question": "报告 5.2 节只列了 3 条测试用例，请补充一个你认为最应该覆盖的异常场景，并说明预期结果。",
      "sourceRefs": [{ "type": "REPORT_SECTION", "section": "5.2 测试用例" }],
      "maxAnswerSeconds": 120,
      "expectedPoints": null
    }
  ],
  "total": 6,
  "generatedAt": "2026-10-21T19:01:10+08:00"
}
```

教师端同一接口返回 `expectedPoints`（评分要点）与 `referenceAnswer`。

### 11.4 开始答辩

`POST /student/defenses/{defenseId}/start` ｜ 权限：STUDENT（本人）

```json
{ "deviceInfo": { "userAgent": "Chrome/141", "micPermission": true }, "clientTimestamp": "2026-10-21T19:02:00+08:00" }
```

**响应**

```json
{
  "defenseId": "1840300000000000001",
  "status": "IN_PROGRESS",
  "wsUrl": "wss://sss.example.edu.cn/ws/v1/defenses/1840300000000000001?ticket=eyJ0eXAiOiJKV1Qi...",
  "ticketExpiresIn": 600,
  "audio": { "codec": "opus", "sampleRate": 16000, "channels": 1, "frameMs": 60 },
  "firstQuestion": { "id": "dq_001", "order": 1, "maxAnswerSeconds": 180 },
  "totalQuestions": 6
}
```

规则：

- 同一会话只能有一个活跃连接，新连接会踢掉旧连接（旧连接收到 `session.replaced`）；
- `ticket` 有效期 10 分钟，仅用于建连，不可复用；
- 开始后 90 分钟内未完成，`status = EXPIRED`，需教师重置（13.5）。

### 11.5 断线重连

`POST /student/defenses/{defenseId}/reconnect` ｜ 权限：STUDENT（本人）

服务器保留会话 15 分钟（`status` 仍为 `IN_PROGRESS`，`currentQuestionId` 记录断点）：

```json
{
  "wsUrl": "wss://...?ticket=...",
  "ticketExpiresIn": 600,
  "currentQuestionId": "dq_003",
  "answeredCount": 2
}
```

### 11.6 上传音频作答（HTTP 兜底）

`POST /student/defenses/{defenseId}/answers` ｜ 权限：STUDENT（本人）

`multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| questionId | string | 是 | 对应题目 ID |
| file | file | 是 | `audio/webm`（Opus）或 `audio/wav`，≤ 30 MB |
| durationMs | int | 是 | 音频时长，须与题目时限一致（服务端会校验） |
| clientSeq | int | 否 | 幂等序号，重复上传返回同一条 |

```json
{
  "answerId": "da_5c71...",
  "questionId": "dq_003",
  "status": "TRANSCRIBING",
  "durationMs": 96000,
  "transcriptTaskId": "tk_asr_2b9a..."
}
```

转写完成后可通过 11.8 查看文本。

### 11.7 结束答辩

`POST /student/defenses/{defenseId}/finish` ｜ 权限：STUDENT（本人）

```json
{ "reason": "COMPLETED", "answeredCount": 6 }
```

`reason`：`COMPLETED`（正常答完）/ `TIME_LIMIT`（超时）/ `MANUAL`（学生主动结束）。

**响应**

```json
{
  "defenseId": "1840300000000000001",
  "status": "COMPLETED",
  "answeredCount": 6,
  "totalDurationMs": 780000,
  "evaluationTaskId": "tk_dv_8f2c..."
}
```

结束后不能再作答；评分任务异步运行，完成后 `status = EVALUATED`。

### 11.8 答辩转写稿

`GET /student/defenses/{defenseId}/transcript` ｜ 权限：STUDENT（本人）或 TEACHER（本开课）

```json
{
  "segments": [
    {
      "index": 1,
      "questionId": "dq_001",
      "question": "你在 BookService 中同时承担了库存校验与借阅记录写入……",
      "answer": "当时考虑到借阅是一个整体流程，放在一个服务里可以保证事务……但并发场景确实没有加锁，应该用悲观锁或者对库存行加版本号……",
      "startMs": 12000,
      "endMs": 104000,
      "audioUrl": "https://oss.../answer-1.webm",
      "asrConfidence": 0.93
    }
  ],
  "fullText": "...",
  "language": "zh-CN"
}
```

### 11.9 答辩自动评价

`GET /student/defenses/{defenseId}/evaluation` ｜ 权限：STUDENT（成绩发布后）或 TEACHER（随时）

```json
{
  "status": "SUCCEEDED",
  "score": 85.0,
  "grade": "良好",
  "summary": "对设计取舍解释清楚，能定位到并发风险；对空指针问题的回答偏模板化，缺少具体改法。",
  "dimensions": [
    { "key": "ACCURACY", "name": "内容准确性", "weight": 40, "score": 33.0 },
    { "key": "DEPTH", "name": "理解深度", "weight": 25, "score": 20.5 },
    { "key": "EXPRESSION", "name": "表达流畅度", "weight": 20, "score": 17.5 },
    { "key": "INTERACTION", "name": "应变与追问表现", "weight": 15, "score": 14.0 }
  ],
  "questionScores": [
    { "questionId": "dq_001", "score": 88.0, "comment": "能说明事务边界，提到了并发但未给出具体方案" },
    { "questionId": "dq_002", "score": 80.0, "comment": "判空思路正确，未提及 Optional 或断言" }
  ],
  "flags": [
    { "type": "SUSPECTED_READING", "message": "第 3 题作答与报告原文高度重合，疑似照读", "confidence": 0.71 }
  ],
  "asr": { "averageConfidence": 0.92, "lowConfidenceSegments": [4] },
  "advisorNote": "若 ASR 置信度低于 0.6，建议教师人工复核"
}
```

### 11.10 我的答辩列表

`GET /student/defenses?offeringId=1839201930199990001` ｜ 权限：STUDENT

```json
{
  "list": [
    {
      "defenseId": "1840300000000000001",
      "status": "EVALUATED",
      "score": 85.0,
      "questionCount": 6,
      "durationMs": 780000,
      "finishedAt": "2026-10-21T19:15:00+08:00",
      "canRetry": false
    }
  ],
  "total": 1
}
```

---

## 12. 评分与成绩 `/grades`

### 12.1 评分模型

```text
最终成绩 = 答辩成绩 × w_defense + 源码成绩 × w_code + 报告成绩 × w_report   （权重默认 40 / 35 / 25）
```

- **源码成绩**、**报告成绩**：多题时取各选题得分的**算术平均**（可按学分/难度加权，见开课配置 `aggregateStrategy`）。
- **答辩成绩**：一次答辩覆盖全部选题，直接取答辩评分。
- **等级**：优秀 ≥ 90；良好 80–89.9；中等 70–79.9；及格 60–69.9；不及格 < 60。
- 缺考/未提交：对应维度记为 `0` 并在 `remark` 中注明；教师可覆盖。

### 12.2 成绩总表（教师）

`GET /teacher/offerings/{offeringId}/grades?keyword=&status=&page=1&size=50` ｜ 权限：TEACHER（本人）

```json
{
  "list": [
    {
      "gradeId": "1840400000000000001",
      "studentId": "1839201930123456789",
      "studentNo": "2023010101",
      "name": "张三",
      "className": "计科2301",
      "topicCount": 2,
      "defenseScore": 85.0,
      "codeScore": 81.5,
      "reportScore": 84.5,
      "finalScore": 83.7,
      "grade": "良好",
      "status": "AUTO",
      "adjusted": false,
      "published": false,
      "updatedAt": "2026-10-21T19:20:00+08:00"
    }
  ],
  "total": 42, "page": 1, "size": 50, "pages": 1,
  "summary": { "average": 80.6, "max": 95.0, "min": 52.0, "passRate": 0.93 }
}
```

`status`：`AUTO`（系统自动）| `ADJUSTED`（教师调整）| `LOCKED`（已发布锁定）

### 12.3 成绩明细（教师）

`GET /teacher/grades/{gradeId}` ｜ 权限：TEACHER（本人开课）

```json
{
  "gradeId": "1840400000000000001",
  "student": { "studentNo": "2023010101", "name": "张三", "className": "计科2301" },
  "weights": { "defense": 40, "code": 35, "report": 25 },
  "items": [
    {
      "selectionId": "1840100000000000001",
      "topicTitle": "基于 JavaFX 的图书管理系统",
      "codeScore": 81.5,
      "reportScore": 84.5,
      "defenseScore": null,
      "finalScore": 83.0,
      "evidence": {
        "codeAnalysisId": "ca_5d10...",
        "reportEvaluationId": "re_31f0...",
        "defenseId": "1840300000000000001"
      }
    }
  ],
  "finalScore": 83.7,
  "grade": "良好",
  "status": "AUTO",
  "version": 3,
  "history": [
    { "at": "2026-10-21T19:20:00+08:00", "actor": "SYSTEM", "action": "AUTO_RECALC", "from": null, "to": 83.7 }
  ]
}
```

### 12.4 教师调整分数

`PATCH /teacher/grades/{gradeId}` ｜ 权限：TEACHER（本人开课）

```json
{
  "defenseScore": 88.0,
  "codeScore": 82.0,
  "reportScore": 85.0,
  "reason": "答辩第 1 题补充说明了并发方案，酌情加分",
  "version": 3
}
```

- `reason` **必填**（≥ 5 字），写入审计日志；
- 只传需要调整的维度，其余保留自动分；
- 重算 `finalScore`，`status = ADJUSTED`，`adjusted = true`；
- 已发布（`LOCKED`）需先 `POST /teacher/grades/{gradeId}/unlock` 才能改。

### 12.5 重算成绩

`POST /teacher/offerings/{offeringId}/grades/recalculate` ｜ 权限：TEACHER（本人）

```json
{ "scope": "PENDING", "overwriteAdjusted": false }
```

`scope`：`ALL` / `PENDING`（未出分的）/ `STUDENT`（配合 `studentIds`）。返回 `202 + taskId`。

### 12.6 发布成绩

`POST /teacher/offerings/{offeringId}/grades/publish` ｜ 权限：TEACHER（本人）｜ 支持 `Idempotency-Key`

```json
{ "gradeIds": [], "allowIncomplete": false, "notifyStudents": true }
```

- `gradeIds` 为空表示全部；
- `allowIncomplete=false` 时，存在未完成答辩的学生会返回 `42201` 并列出名单；
- 发布后学生可见，`status = LOCKED`；`notifyStudents=true` 走站内信/邮件。

**响应**

```json
{ "published": 40, "skipped": 2, "skippedStudents": ["2023010117", "2023010118"], "publishedAt": "2026-10-31T10:00:00+08:00" }
```

### 12.7 撤销发布

`POST /teacher/offerings/{offeringId}/grades/unpublish` ｜ 权限：TEACHER（本人）

发布后 24 小时内可撤销（`{ "gradeIds": [] }`），超时需联系管理员。撤销后学生端立即不可见。

### 12.8 导出成绩

`POST /teacher/offerings/{offeringId}/grades/export` ｜ 权限：TEACHER（本人）

```json
{ "format": "XLSX", "includeEvidence": true, "includeComments": true, "includeIssues": false }
```

返回 `202`：

```json
{ "taskId": "tk_ex_55a1...", "status": "PENDING" }
```

任务完成后 `GET /tasks/{taskId}` 的 `result`：

```json
{ "fileId": "f_ex_77c1...", "filename": "面向对象程序设计课程设计_计科2301_成绩单_20261031.xlsx", "size": 20480, "downloadUrl": "/api/v1/files/f_ex_77c1.../download" }
```

导出列：学号、姓名、班级、选题数、答辩分、源码分、报告分、总评、等级、教师备注、发布时间。

### 12.9 学生查看成绩

`GET /student/grades?offeringId=1839201930199990001` ｜ 权限：STUDENT（本人）

仅返回已发布成绩：

```json
{
  "list": [
    {
      "gradeId": "1840400000000000001",
      "courseName": "面向对象程序设计课程设计(Java)",
      "semester": "2026-2027-1",
      "finalScore": 83.7,
      "grade": "良好",
      "dimensions": { "defense": 85.0, "code": 81.5, "report": 84.5 },
      "items": [
        { "topicTitle": "基于 JavaFX 的图书管理系统", "codeScore": 81.5, "reportScore": 84.5 }
      ],
      "teacherComment": "整体完成度较好，注意并发与异常处理的严谨性。",
      "publishedAt": "2026-10-31T10:00:00+08:00"
    }
  ],
  "total": 1
}
```

---

## 13. 教师复核 `/teacher`

### 13.1 答辩列表（教师）

`GET /teacher/offerings/{offeringId}/defenses?status=EVALUATED&keyword=张&page=1&size=20` ｜ 权限：TEACHER（本人）

```json
{
  "list": [
    {
      "defenseId": "1840300000000000001",
      "studentNo": "2023010101",
      "studentName": "张三",
      "topicTitles": ["基于 JavaFX 的图书管理系统", "校园二手交易平台"],
      "status": "EVALUATED",
      "score": 85.0,
      "durationMs": 780000,
      "finishedAt": "2026-10-21T19:15:00+08:00",
      "reviewed": false,
      "flags": 1
    }
  ],
  "total": 30, "page": 1, "size": 20, "pages": 2
}
```

### 13.2 答辩详情（教师）

`GET /teacher/defenses/{defenseId}` ｜ 权限：TEACHER（本人开课）

返回：学生信息、题目（含 `expectedPoints` / `referenceAnswer`）、转写稿、评分、异常标记、复核状态。

### 13.3 音视频播放

`GET /teacher/defenses/{defenseId}/media?type=AUDIO|VIDEO&questionId=dq_001&mode=url` ｜ 权限：TEACHER（本人开课）

```json
{
  "url": "https://oss.example.edu.cn/.../defense-1840300000000000001.webm?Expires=...&Signature=...",
  "contentType": "audio/webm",
  "durationMs": 780000,
  "expiresIn": 3600,
  "supportsRange": true
}
```

> 支持 HTTP Range，前端可直接用 `<audio>` / `<video>` 拖动进度；URL 1 小时有效，禁止分享外泄（绑定 Referer + 签名）。

### 13.4 学生材料总览（教师）

`GET /teacher/offerings/{offeringId}/submissions?type=SOURCE&analyzed=true&keyword=&page=1&size=20` ｜ 权限：TEACHER（本人）

```json
{
  "list": [
    {
      "studentNo": "2023010101",
      "studentName": "张三",
      "selectionId": "1840100000000000001",
      "topicTitle": "基于 JavaFX 的图书管理系统",
      "source": { "submissionId": "1840200000000000001", "version": 2, "codeScore": 81.5, "status": "ANALYZED" },
      "report": { "submissionId": "1840200000000000002", "version": 1, "reportScore": 84.5, "status": "ANALYZED" }
    }
  ],
  "total": 39, "page": 1, "size": 20, "pages": 2
}
```

### 13.5 复核结论

`POST /teacher/defenses/{defenseId}/review` ｜ 权限：TEACHER（本人开课）

```json
{ "action": "APPROVE", "comment": "录像与评分一致，无异议" }
```

`action`：`APPROVE`（通过）/ `FLAG`（标记异常，如疑似照读、替考）/ `RESET`（重置会话，允许学生重答，需 `reason`）。

**重置响应**

```json
{ "defenseId": "1840300000000000001", "status": "CREATED", "resetCount": 1, "resetAt": "2026-10-22T10:00:00+08:00" }
```

> 重置会清空本次作答与评分，原录音归档保留（`defense_archive`），不可恢复。

### 13.6 教师评语

`PATCH /teacher/defenses/{defenseId}/evaluation` ｜ 权限：TEACHER（本人开课）

```json
{ "comment": "回答思路清晰，但对事务隔离级别理解有偏差，课上再强调。", "dimensions": [{ "key": "ACCURACY", "score": 36 }] }
```

仅覆盖评语与维度分（总分按权重重算），不影响 `finalScore` 的答辩权重口径。

---

## 14. 文件与后台任务 `/files` `/tasks`

### 14.1 通用上传

`POST /files` ｜ 权限：已登录

`multipart/form-data`：`file`、`purpose`（`TOPIC_ATTACHMENT` / `SOURCE` / `REPORT` / `AVATAR` / `EXPORT`）

```json
{ "fileId": "f_9a1c2b...", "filename": "题目附件.zip", "size": 204800, "sha256": "ab12...", "contentType": "application/zip" }
```

### 14.2 文件下载

`GET /files/{fileId}/download?mode=url|stream` ｜ 权限：已登录且对文件有访问权

- `mode=url`（默认）：返回 10 分钟有效的签名 URL；
- `mode=stream`：直接流式返回，支持 Range。

### 14.3 查询任务

`GET /tasks/{taskId}` ｜ 权限：发起人

```json
{
  "taskId": "tk_9f2c...",
  "type": "CODE_ANALYSIS",
  "status": "SUCCEEDED",
  "progress": 100,
  "result": { "analysisId": "ca_5d10...", "score": 81.5 },
  "error": null,
  "createdAt": "2026-10-10T21:03:00+08:00",
  "finishedAt": "2026-10-10T21:09:00+08:00"
}
```

`type`：`PARSE_SOURCE` / `PARSE_REPORT` / `CODE_ANALYSIS` / `REPORT_EVALUATION` / `DEFENSE_QUESTION_GEN` / `DEFENSE_EVALUATION` / `GRADE_EXPORT` / `GRADE_RECALC`

### 14.4 取消任务

`POST /tasks/{taskId}/cancel` ｜ 权限：发起人

仅 `PENDING` / `RUNNING` 可取消，返回 `204`；已完成任务返回 `40901`。

### 14.5 任务事件流（可选）

`GET /tasks/{taskId}/events` ｜ `text/event-stream`

```text
event: progress
data: {"status":"RUNNING","progress":45}

event: done
data: {"status":"SUCCEEDED","result":{"analysisId":"ca_5d10..."}}
```

前端可优先用 SSE 替代轮询，断线后回退到 14.3。
---

## 15. 通知 `/notifications`

### 15.1 通知列表

`GET /notifications?unreadOnly=true&page=1&size=20` ｜ 权限：已登录

```json
{
  "list": [
    {
      "id": "nt_01",
      "type": "GRADE_PUBLISHED",
      "title": "《面向对象程序设计课程设计(Java)》成绩已发布",
      "content": "你的总评成绩为 83.7，等级：良好。",
      "link": "/student/grades?offeringId=1839201930199990001",
      "read": false,
      "createdAt": "2026-10-31T10:00:05+08:00"
    }
  ],
  "total": 1,
  "unreadCount": 1
}
```

`type`：`SELECTION_OPEN` / `SUBMIT_DEADLINE` / `DEFENSE_READY` / `ANALYSIS_DONE` / `GRADE_PUBLISHED` / `TEACHER_MESSAGE`

### 15.2 标记已读

- `PATCH /notifications/{notificationId}/read` → `data: null`
- `POST /notifications/read-all` → `{ "updated": 12 }`

---

## 16. 统计看板（补充）`/teacher/offerings/{id}/dashboard`

### 16.1 时间趋势

`GET /teacher/offerings/{offeringId}/dashboard/trend?metric=SUBMISSION&from=2026-10-01&to=2026-10-31` ｜ 权限：TEACHER（本人）

```json
{
  "metric": "SUBMISSION",
  "points": [
    { "date": "2026-10-01", "value": 2 },
    { "date": "2026-10-02", "value": 5 }
  ]
}
```

### 16.2 题目维度分析

`GET /teacher/offerings/{offeringId}/dashboard/topics` ｜ 权限：TEACHER（本人）

```json
{
  "list": [
    {
      "topicId": "1839300000000000001",
      "title": "基于 JavaFX 的图书管理系统",
      "selectedCount": 12,
      "avgCodeScore": 79.8,
      "avgReportScore": 82.1,
      "avgDefenseScore": 83.4,
      "topIssues": ["UnusedLocalVariable", "MagicNumber"]
    }
  ],
  "total": 8
}
```

---

## 17. WebSocket 语音答辩协议

### 17.1 连接

```text
wss://{host}/ws/v1/defenses/{defenseId}?ticket={ticket}
```

- `ticket` 来自 `POST /student/defenses/{id}/start`（或 `/reconnect`），有效期 10 分钟、一次性；
- 建连后服务端校验：ticket 有效、`sub` 与 `defense.studentId` 一致、会话状态为 `IN_PROGRESS`；
- 校验失败：`4001`（无效 ticket）/ `4003`（越权）/ `4009`（会话状态错误），随后关闭。

### 17.2 消息格式

**控制消息**：JSON 文本帧

```json
{ "type": "answer.start", "seq": 12, "payload": { "questionId": "dq_001" } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| type | string | 事件名，见 17.3 / 17.4 |
| seq | int | 客户端自增序号，服务端原样回显用于对账 |
| payload | object | 事件负载 |

**音频帧**：二进制帧，格式固定为 **Opus, 16 kHz, 单声道, 60 ms/帧**；每帧前 4 字节为小端 `uint32` 序号，其余为 Opus 数据。

### 17.3 客户端 → 服务端

| type | 说明 | payload 示例 |
|---|---|---|
| `session.init` | 建连后第一条，声明设备与编码 | `{ "codec": "opus", "sampleRate": 16000, "clientVersion": "1.0.0" }` |
| `answer.start` | 开始回答某题 | `{ "questionId": "dq_001" }` |
| `answer.end` | 结束本题回答 | `{ "questionId": "dq_001", "durationMs": 96000 }` |
| `question.skip` | 跳过（仅 `allowRetry=true` 时允许） | `{ "questionId": "dq_002" }` |
| `ping` | 心跳，建议 15s | `{ "ts": 1760000000000 }` |
| `finish` | 主动结束答辩 | `{ "reason": "MANUAL" }` |

### 17.4 服务端 → 客户端

| type | 说明 | payload 示例 |
|---|---|---|
| `session.ready` | 建连成功 | `{ "defenseId": "...", "audio": { "codec": "opus", "frameMs": 60 }, "currentQuestion": { "id": "dq_001", "order": 1 } }` |
| `question.start` | 下发当前题目 + TTS 音频 | `{ "id": "dq_002", "order": 2, "text": "……", "audioUrl": "https://oss.../q2.mp3", "maxAnswerSeconds": 180 }` |
| `question.audio` | 逐句 TTS 流（可选） | `{ "questionId": "dq_002", "index": 1, "audioBase64": "..." }` |
| `asr.partial` | 实时转写中间结果 | `{ "questionId": "dq_002", "text": "我认为应该加锁", "confidence": 0.78 }` |
| `asr.final` | 本题最终转写 | `{ "questionId": "dq_002", "text": "……", "confidence": 0.94 }` |
| `answer.timeout` | 本题超时 | `{ "questionId": "dq_002", "nextQuestionId": "dq_003" }` |
| `eval.progress` | 评分进度 | `{ "progress": 40 }` |
| `session.end` | 答辩结束，附评价任务 ID | `{ "reason": "COMPLETED", "evaluationTaskId": "tk_dv_8f2c..." }` |
| `error` | 错误 | `{ "code": 50301, "message": "ASR 服务暂不可用", "retryable": true }` |
| `session.replaced` | 被新连接顶替 | `{ "message": "会话已在其他设备打开" }` |
| `pong` | 心跳响应 | `{ "ts": 1760000000000 }` |

### 17.5 时序约束

| 项 | 值 |
|---|---|
| 建连超时 | 10s |
| 心跳间隔 / 超时 | 15s / 45s |
| 每题最小时长 | 5s（防秒答） |
| 每题默认上限 | 180s（开课可配 60~600s） |
| 静音自动断句 | 连续 3s 无有效语音视为本题结束 |
| 会话总时长上限 | 90 分钟 |
| 断线保留 | 15 分钟（可 `/reconnect`） |
| 音频留存 | 默认 1 年（可配置），仅教师可回放 |

### 17.6 交互示例

```text
C→S  session.init            {"codec":"opus","sampleRate":16000}
S→C  session.ready           {"currentQuestion":{"id":"dq_001","order":1}}
S→C  question.start          {"id":"dq_001","text":"……","maxAnswerSeconds":180}
C→S  answer.start            {"questionId":"dq_001"}
C→S  <binary opus frames ...>
S→C  asr.partial             {"text":"我在 BookService 里……"}
C→S  answer.end              {"questionId":"dq_001","durationMs":96000}
S→C  asr.final               {"text":"……","confidence":0.94}
S→C  question.start          {"id":"dq_002", ...}
...
C→S  finish                  {"reason":"COMPLETED"}
S→C  session.end             {"evaluationTaskId":"tk_dv_8f2c..."}
```

---

## 18. 状态机

### 18.1 开课 Offering

```mermaid
stateDiagram-v2
  [*] --> DRAFT
  DRAFT --> PUBLISHED: 发布（校验名单/题目/时间）
  PUBLISHED --> CLOSED: 教师关闭
  CLOSED --> ARCHIVED: 归档
  PUBLISHED --> PUBLISHED: 调整时间/权重
```

### 18.2 选题 Selection

```mermaid
stateDiagram-v2
  [*] --> SELECTED: 学生选题
  SELECTED --> SUBMITTED: 上传源码+报告
  SELECTED --> CANCELED: 截止前退选
  SUBMITTED --> DEFENDING: 开始答辩
  DEFENDING --> DEFENDED: 答辩完成
  DEFENDED --> GRADED: 成绩发布
  SUBMITTED --> SUBMITTED: 覆盖提交（版本+1）
```

### 18.3 答辩 Defense

```mermaid
stateDiagram-v2
  [*] --> CREATED
  CREATED --> GENERATING: 请求生成题目
  GENERATING --> READY: 题目就绪
  READY --> IN_PROGRESS: /start
  IN_PROGRESS --> IN_PROGRESS: 断线重连
  IN_PROGRESS --> COMPLETED: /finish
  COMPLETED --> EVALUATED: 评分完成
  IN_PROGRESS --> EXPIRED: 超过 90 分钟
  COMPLETED --> CREATED: 教师重置
  CREATED --> EXPIRED: 超时未开始
```

### 18.4 提交物 Submission

```mermaid
stateDiagram-v2
  [*] --> UPLOADED
  UPLOADED --> PARSING
  PARSING --> VALID
  PARSING --> INVALID
  VALID --> ANALYZING
  ANALYZING --> ANALYZED
  ANALYZING --> VALID: 分析失败可重试
  VALID --> DELETED: 学生删除（截止前）
```

---

## 19. 数据字典（枚举）

| 枚举 | 取值 | 说明 |
|---|---|---|
| Role | `STUDENT` / `TEACHER` / `ADMIN` | 账号角色 |
| OfferingStatus | `DRAFT` / `PUBLISHED` / `CLOSED` / `ARCHIVED` | 开课状态 |
| TopicStatus | `DRAFT` / `PUBLISHED` / `ARCHIVED` | 题目状态 |
| Difficulty | `EASY` / `MEDIUM` / `HARD` | 难度 |
| SelectionStatus | `SELECTED` / `SUBMITTED` / `DEFENDING` / `DEFENDED` / `GRADED` / `CANCELED` | 选题状态 |
| SubmissionType | `SOURCE` / `REPORT` | 提交物类型 |
| SubmissionStatus | `UPLOADED` / `PARSING` / `VALID` / `INVALID` / `ANALYZING` / `ANALYZED` / `DELETED` | 提交物状态 |
| DefenseStatus | `CREATED` / `GENERATING` / `READY` / `IN_PROGRESS` / `COMPLETED` / `EVALUATED` / `EXPIRED` | 答辩状态 |
| QuestionType | `DESIGN` / `CODE` / `REPORT` / `CONCEPT` / `EXTENSION` | 答辩题类型 |
| Severity | `HIGH` / `MEDIUM` / `LOW` / `ERROR` / `WARNING` / `INFO` | 问题严重度（按工具归一） |
| TaskStatus | `PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELED` | 异步任务 |
| TaskType | `PARSE_SOURCE` / `PARSE_REPORT` / `CODE_ANALYSIS` / `REPORT_EVALUATION` / `DEFENSE_QUESTION_GEN` / `DEFENSE_EVALUATION` / `GRADE_EXPORT` / `GRADE_RECALC` | 任务类型 |
| GradeStatus | `AUTO` / `ADJUSTED` / `LOCKED` | 成绩状态 |
| GradeLevel | `优秀` / `良好` / `中等` / `及格` / `不及格` | 等级 |
| Tool | `CHECKSTYLE` / `PMD` / `SPOTBUGS` / `AI` | 分析工具 |

---

## 20. 关键时序图

### 20.1 上传与自动分析

```mermaid
sequenceDiagram
  autonumber
  participant S as 学生端(Vue)
  participant API as Spring Boot
  participant OSS as 对象存储
  participant MQ as 消息队列/异步执行器
  participant JVM as Checkstyle/PMD/SpotBugs
  participant AI as Python AI 服务

  S->>API: POST /selections/{id}/source (zip)
  API->>OSS: 存储原文件
  API->>MQ: 投递 PARSE_SOURCE
  API-->>S: 202 submissionId + parseTaskId
  MQ->>API: 解压/校验/统计/编译粗检
  MQ->>JVM: 运行三件套（-f xml/json）
  JVM-->>MQ: 规则问题列表
  MQ->>AI: POST /internal/ai/code/review（AST 摘要+问题）
  AI-->>MQ: 语义评审 + 维度分
  MQ->>API: 汇总分数与问题入库
  S->>API: GET /submissions/{id}/code-analysis
  API-->>S: 分数/维度/问题明细
```

### 20.2 语音答辩

```mermaid
sequenceDiagram
  autonumber
  participant S as 学生端
  participant WS as WebSocket 网关
  participant API as Spring Boot
  participant ASR as ASR 服务
  participant AI as LLM(TTS/评分)

  S->>API: POST /defenses/{id}/start
  API-->>S: wsUrl + ticket + 首题
  S->>WS: 连接 + session.init
  WS->>API: 校验 ticket/状态
  WS-->>S: session.ready
  WS-->>S: question.start（题干+TTS）
  S->>WS: answer.start + opus 帧流
  WS->>ASR: 流式识别
  ASR-->>WS: partial/final 文本
  WS-->>S: asr.partial / asr.final
  S->>WS: answer.end
  WS-->>S: 下一题 question.start
  S->>WS: finish
  WS->>API: 关闭会话，触发 DEFENSE_EVALUATION
  API->>AI: 题目+转写稿+评分要点
  AI-->>API: 维度分+评语+异常标记
  API->>API: 汇总 finalScore（按权重）
  S->>API: GET /student/grades（发布后）
```

### 20.3 成绩发布与导出

```mermaid
sequenceDiagram
  autonumber
  participant T as 教师端
  participant API as Spring Boot
  participant AI as 评分汇总
  participant OSS as 对象存储

  T->>API: GET /teacher/offerings/{id}/grades
  API-->>T: 总表（自动分）
  T->>API: PATCH /teacher/grades/{gid}（调整+原因）
  API->>API: 重算 finalScore，记录审计
  T->>API: POST /teacher/offerings/{id}/grades/publish
  API->>API: 校验完整性，状态置 LOCKED
  API-->>T: published 数量
  T->>API: POST /teacher/offerings/{id}/grades/export
  API->>OSS: 生成 xlsx
  API-->>T: taskId
  T->>API: GET /tasks/{taskId}
  API-->>T: downloadUrl
```

---

## 21. AI 服务内部契约（Java ⇄ Python）

Java 侧不直接实现 AI 逻辑，统一调用内网 Python 服务（FastAPI + LangChain + LightRAG）。

**鉴权**：`X-Internal-Token: {shared_secret}`；仅内网白名单可达。

**通用约定**：所有接口同步返回 `202 + taskId` 或直接返回结果（< 60s 的任务），失败可重试 2 次（指数退避）。

### 21.1 生成答辩题目

`POST /internal/ai/defense/questions`

```json
{
  "requestId": "req_01",
  "offering": { "courseName": "面向对象程序设计课程设计(Java)", "semester": "2026-2027-1" },
  "topics": [
    { "topicId": "1839300000000000001", "title": "基于 JavaFX 的图书管理系统", "requirements": "……", "difficulty": "MEDIUM" }
  ],
  "source": {
    "submissionId": "1840200000000000001",
    "javaFiles": [{ "path": "src/main/java/.../BookService.java", "content": "……" }],
    "metrics": { "codeLines": 5120, "classes": 38 },
    "issues": [{ "tool": "PMD", "rule": "UnusedLocalVariable", "file": "...", "line": 128 }]
  },
  "report": {
    "submissionId": "1840200000000000002",
    "sections": [{ "title": "4.2 业务逻辑设计", "content": "……" }],
    "pageCount": 24
  },
  "config": { "questionCount": 6, "focus": ["DESIGN", "CODE"], "language": "zh-CN" }
}
```

**响应**

```json
{
  "requestId": "req_01",
  "questions": [
    {
      "type": "DESIGN",
      "difficulty": "MEDIUM",
      "question": "……",
      "expectedPoints": ["事务边界", "并发风险", "改进方案"],
      "referenceAnswer": "……",
      "sourceRefs": [{ "type": "FILE", "path": "src/.../BookService.java", "line": 45 }],
      "maxAnswerSeconds": 180
    }
  ],
  "model": "deepseek-chat",
  "tokens": { "prompt": 5210, "completion": 980 }
}
```

### 21.2 答辩评分

`POST /internal/ai/defense/evaluate`

```json
{
  "defenseId": "1840300000000000001",
  "questions": [{ "id": "dq_001", "question": "……", "expectedPoints": ["……"] }],
  "transcript": [{ "questionId": "dq_001", "text": "……", "durationMs": 92000 }],
  "audio": [{ "questionId": "dq_001", "url": "https://oss.../a1.webm", "asrConfidence": 0.93 }]
}
```

**响应**：见 11.9 结构（`score` / `dimensions` / `questionScores` / `flags`）。

### 21.3 报告评审

`POST /internal/ai/report/evaluate`：入参为报告结构化文本 + 题目要求；出参见 10.2。

### 21.4 代码语义评审

`POST /internal/ai/code/review`：入参为 AST 摘要、依赖关系、静态工具问题；出参为 `DESIGN` / `DOC` 维度得分与建议。

### 21.5 报告-源码一致性

`POST /internal/ai/consistency`：入参为报告实体与源码实体（LightRAG 检索结果）；出参为 `score` 与 `issues[]`。

### 21.6 异步回调

长任务完成后回调：

`POST {javaBase}/internal/callback/ai-tasks`

```json
{ "taskId": "tk_qg_91c2...", "status": "SUCCEEDED", "result": { "...": "..." }, "finishedAt": "2026-10-21T19:01:10+08:00" }
```

Java 侧校验签名（`X-Callback-Signature`，HMAC-SHA256）后落库，同一 `taskId` 重复回调幂等。

---

## 22. 前后端落地建议

### 22.1 Spring Boot 分层与包结构

```text
com.example.sss
├── common        // Result、错误码、分页、异常处理、审计
├── security      // JWT、过滤器、@PreAuthorize、数据权限
├── auth
├── course        // Course、Offering
├── student       // 名单导入、账号
├── topic
├── selection
├── submission    // 上传、解析、版本
├── analysis      // Checkstyle/PMD/SpotBugs 调度与结果
├── report
├── defense       // 会话、题目、WebSocket、ASR 对接
├── grading       // 汇总、调整、发布、导出
├── file          // 对象存储适配
└── integration   // ai-client（RestClient/WebClient）、mq
```

**关键实现点**

| 关注点 | 建议 |
|---|---|
| 鉴权 | Spring Security + JWT（`jjwt`），方法级 `@PreAuthorize("hasRole('TEACHER')")` |
| 数据权限 | 自定义 `@DataScope` + MyBatis 拦截器，强制拼 `teacher_id` / `student_id` |
| 文件 | 本地 MinIO/阿里 OSS，预签名 URL；压缩包解压用 `zip4j` 并限制条目数与总大小 |
| 静态分析 | `ProcessBuilder` 调 `java -jar checkstyle.jar`、`pmd`、`spotbugs`，超时 5 分钟，输出 XML/JSON 用 `Jackson` + `XmlMapper` 解析 |
| 异步 | Spring `@Async` + 线程池隔离；或用 RocketMQ/RabbitMQ；任务表 `async_task` 记录状态 |
| WebSocket | Spring `WebSocketHandler` + Redis 发布订阅做多实例路由；音频帧直接转发 ASR |
| 幂等 | `Idempotency-Key` 存 Redis（24h），键 = 用户 + 路径 + key |
| 乐观锁 | MyBatis-Plus `@Version`，冲突返回 `40901` |
| 审计 | `audit_log`（操作人、对象、前后值、原因、时间），改分/重置/发布必写 |

**核心表**

`course`、`offering`、`offering_student`、`topic`、`topic_attachment`、`selection`、`submission`、`submission_version`、`code_analysis`、`code_issue`、`report_evaluation`、`defense_session`、`defense_question`、`defense_answer`、`score`、`grade`、`grade_item`、`async_task`、`notification`、`audit_log`、`file_object`。

### 22.2 Vue 3 端

| 关注点 | 建议 |
|---|---|
| 请求封装 | axios 实例：注入 `Authorization`；`40101` → 单飞刷新 Token 并重放；统一 `code !== 0` 的 toast |
| 路由守卫 | `meta.roles` 控制 TEACHER/STUDENT 路由；未发布成绩隐藏成绩入口 |
| 上传 | 大文件分片（5 MB/片）+ 断点续传 + SHA-256 秒传；进度条 + 失败重试 |
| 答辩 | `MediaRecorder` 采集 Opus → WebSocket 二进制帧；`AudioContext` 播放 TTS；断线自动 `/reconnect` |
| 轮询 | 封装 `useTask(taskId)`：1s 起指数退避，`SUCCEEDED/FAILED` 停止，组件卸载时清理 |
| 权限按钮 | `v-permission="'grade:adjust'"` 指令，权限码由 `/auth/me` 下发 |
| 成绩表 | 虚拟滚动 + 列固定；导出走异步任务，完成后自动下载 |

---

## 23. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0.0 | 2026-10-22 | 首版：覆盖选题、提交、静态分析、报告评审、语音答辩、评分发布全链路 |

---

> 本文档为前后端联调契约。字段新增遵循「向后兼容」原则：只增不改不删；破坏性变更升主版本并在本表登记。若需 OpenAPI 3.0 描述文件（可直接导入 Apifox / Swagger UI 或生成 Spring 接口骨架），可基于本文档再生成一份 `openapi.yaml`。
