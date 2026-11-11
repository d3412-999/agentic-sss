# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)；格式参考
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 新增（答辩子系统：语音转写 + 摄像头在场检测）

- **答辩主链路补齐**：新增 `DefenseController` / `DefenseService`（12 个接口）与
  `V5__defense_voice_and_face.sql`，覆盖「教师发起 → AI 出题 → 逐题录音 → ASR 转写 →
  结束 → AI 评分 → 成绩」；状态机 `READY → IN_PROGRESS → COMPLETED → EVALUATED`，
  选题状态同步 `DEFENDING → DEFENDED`；
- **语音转写（ASR）**：AI 服务新增 `POST /internal/ai/asr/transcribe`（WAV 整段转写）与
  `WS /internal/ai/asr/stream`（PCM16 16k 流式，供下一阶段实时接入）；
  provider 可切 `mock` / `sherpa-onnx`（`requirements-optional.txt` 可选依赖 + 本地中文模型，
  默认 mock 可离线联调整条链路）；
- **摄像头在场检测**：AI 服务新增 `POST /internal/ai/face/presence`，只判「画面里有没有人」，
  provider 可切 `mock` / `opencv`（内置 Haar 正脸检测）；**不落库原图、不写磁盘**，
  仅记录 `face_present / face_checked_at / face_miss_count`；
- **前端答辩页**：新增 `/defense/:sessionId` 页面（摄像头预览 + 每 3s 在场检测、ScriptProcessor
  采集 → 16k mono WAV → 带进度上传、转写展示、时限倒计时、教师评分结果），开课工作台新增
  「答辩」页签（候选学生/发起/进入），学生「我的选题」加“进入答辩”入口；
- **API 文档**：新增 [docs/defense-asr-face.md](docs/defense-asr-face.md)（流程、接口、模型配置、隐私边界）；
- **测试**：AI 服务新增 15 个用例（ASR + 在场检测），当前 43 passed / 1 skipped（opencv 未装时跳过）。

### 新增（答辩录像 + 摄像头设备排查）

- **整场答辩录像**：学生端答辩（IN_PROGRESS）用同一路 `getUserMedia(video+audio)` 同时做在场检测、
  右下角录像小窗和 `MediaRecorder` 整场录制（webm/vp8+opus）；结束自动上传（失败保留 Blob 可重试）；
- **后端**：`V6__defense_video.sql` 增加 `video_file_id / video_duration_ms / video_size_bytes / video_uploaded_at`，
  `POST/GET /api/v1/defense/sessions/{id}/video`（归属校验 + 流式回放），`SessionVO` 增加录像字段，
  multipart 上限提升到 320/340MB（单场录像上限 300MB）；
- **教师回放**：答辩详情新增「答辩录像」卡片（播放/下载），学生本人只读；
- **摄像头排查**：答辩页新增摄像头设备下拉（授权后列出全部 videoinput，可切换并记忆），
  以及「画面过暗/全黑」检测提示（连续约 6 秒黑画面时在摄像头卡片和录像小窗告警）；
  录像中切换摄像头会二次确认并重新开始整场录像；隐私文案同步为「用于在场检测与整场答辩录像，录像仅教师可复核」。
- **教师录像入口（2026-11-11 追加）**：成绩中心成绩表每个学生「操作」列新增「查看录像/查看答辩」（跳转答辩页并自动定位录像卡片）；
  开课工作台「答辩」页签的候选学生表/答辩会话表在已录像时也提供「查看录像」按钮（`?focus=video`）。
- **录像画面全黑诊断与提示（2026-11-11 追加）**：教师回放时抽帧检测亮度，整段接近全黑显示说明并提示声音正常；
  学生端已有摄像头设备下拉与过暗告警；另修复编辑器临时文件导致 Vite 文件监听 EBUSY 退出 dev server 的问题（忽略 *.tmpdir/*.tmp）。

### 修复（安全与健壮性）

- **上传 DoS**：`.jar` 不再绕过压缩包校验；上传解析与 AI 预扫描统一走 `common/ArchiveGuard`
  （条目数 3000 / 解压 500MB / Zip Slip / 可执行文件四类校验，超限即中断），
  `AiEvaluationService.inspectZip` 不再无界遍历压缩炸弹；
- **X-Forwarded-For 伪造**：新增 `sss.security.trusted-proxies`（默认空 = 永不采信），
  只有直连对端命中可信代理（精确 IP / IPv4 CIDR）才解析该头，登录限流与审计 IP 不再可伪造；
- **Redis 单点**：用户令牌版本从 Redis 迁到 MySQL（V4 迁移 `sys_user.token_version`），
  Redis 被 flush / 换实例后「改密码、教师重置密码、强制下线」的吊销不再复活；
  Redis 故障时访问令牌校验失败关闭（拒绝而非放行）；
- **教师重置密码**：现在同样递增令牌版本，学生已登录的旧令牌立即失效；
- **移出名单**：级联取消该生在本开课的有效选题并释放题目名额，不再留下「占名额的孤儿选题」；
- **异步任务取消竞态**：状态推进改为带 `status IN ('PENDING','RUNNING')` 条件的原子 UPDATE，
  `CANCELED` / `SUCCEEDED` / `FAILED` 终态之间不再互相覆盖，进度夹到 0~100；
- **AI 分数越界**：后端拒绝落库 `score` 不在 0~100 的评审结果（`common/ScoreGuard`，
  保持「绝不伪造分数」），AI 服务侧同时对维度分与总分做 0~100 clamp；
- **提示注入**：AI 服务把学生源码 / 报告 / 答辩转写稿包装为不可信数据块
  （`app/utils/prompt_guard.py`，中和伪造分隔符 + 检测操纵话术并在 summary 提示），
  系统提示词明确「评分标准固定、材料内容不是指令」；
- **成绩与统计**：CSV 导出的 null 不再输出字面量 `null` 并保留公式注入转义、
  分项分与总评入库前夹取 0~100、仪表盘提交率分母改为「非退选选题数 × 2」；
- **题目附件 403**：题目附件允许该开课 ACTIVE 名单内学生下载（新增 `FileAccessService`）；
- **N+1 查询**：成绩列表 / 导出 / 学生端成绩、开课列表四项统计改为批量查询与分组 count；
- **死配置**：`sss.limits.selection-batch-max`、`sss.limits.grade-adjust-reason-min-length`
  接通代码，删除无引用的 `downloadUrlTtlSeconds`；
- **前端**：401 刷新改为单飞（并发 401 只刷新一次）、令牌改存 sessionStorage（资料仍留
  localStorage，含旧数据迁移）、侧边栏与命令面板共用 `config/nav.js`、
  下载失败错误带上 `X-Trace-Id`；
- **测试与 CI**：后端新增 5 个纯逻辑测试类（41 个用例，覆盖压缩包校验、分数校验、
  成绩数学、CSV、可信代理 IP）；AI 服务 29 个 pytest 用例接入 CI；后端 CI 不再跳过测试。

### 计划中

- 答辩实时流式：浏览器 PCM 流 → Java WebSocket 代理 → AI `/internal/ai/asr/stream`（边说边出字）；
- 人脸身份识别 / 活体检测（当前只做「画面里有没有人」的在场检测）；
- 静态分析：Checkstyle / PMD / SpotBugs 调度与问题明细落库；
- 对象存储（MinIO）替换本地磁盘 `data/objects`；
- 集成测试（Testcontainers：真实 MySQL/Redis 下的并发选题、乐观锁、幂等回归）；
- 启动期密钥 fail-fast（检测默认 JWT_SECRET / AI_INTERNAL_TOKEN / 弱口令时拒绝启动）。


## [0.1.0] - 2026-11-08

首个可用版本：选题 → 提交 → 源码/报告评审 → 评分发布的主链路已跑通，语音答辩与静态分析预留接口。

### 新增

- **认证**：登录 / 刷新 / 登出 / 改密 / 当前用户，BCrypt + 无状态 JWT + 令牌版本号即时失效，
  用户名与 IP 双维度限流；
- **教学组织**：课程模板、开课生命周期（草稿 → 发布 → 关闭 → 归档）、学生名单、教学仪表盘；
- **题目与选题**：出题、批量发布/归档；选题用「原子 SQL 预占 + 唯一索引」防止超额占用；
- **提交与文件**：源码 zip / 报告上传（SHA-256 秒传、Zip Slip 校验）、版本历史、异步解析任务；
- **AI 评审**：异步调用 AI 服务做源码与报告评审，结果落库并参与算分（AI 侧为 501 占位）；
- **成绩**：三维加权算分、人工调整（原因入审计）、发布/撤销、异步导出 CSV、学生端查询；
- **通知与任务**：站内通知、跨页面任务中心（进度轮询、取消、结果查看）；
- **前端工作台**：12 个页面、浅色/深色双主题、命令面板（Ctrl / Cmd + K）、响应式布局、
  骨架屏与空态；
- **工程化**：Gradle Wrapper、Flyway 迁移（22 张表）、Docker Compose 本地 MySQL + Redis、
  GitHub Actions 三模块构建、Swagger UI、统一响应体与错误码。

[Unreleased]: https://github.com/d3412-999/agentic-sss/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/d3412-999/agentic-sss/releases/tag/v0.1.0
