# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)；格式参考
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 修复（学生「我的选题」选中后详情区反复闪烁）

- **根因**：改造成左目录 + 右详情后，`page-split` 被放进了 `v-if="loading"` 的 `v-else` 分支。
  而 `refreshDetail()` 末尾调用 `load()`，`load()` 一开 `loading` 就把整个详情区卸载；
  重新挂载后 `ReviewStatusPanel` 立即判定「评审已完成」并再次 `emit('finished')`
  → 又触发 `refreshDetail()` → 再卸载……形成「刷新 → 卸载 → 重挂 → 再刷新」的死循环。
- **修复 1**：`load(silent)` 增加静默模式，详情触发的刷新（上传完成 / 评审完成）走 `load(true)`，
  不再切换 loading；首次进入才显示骨架。
- **修复 2**：`loading` 只控制左目录的骨架与右侧空态，详情区常驻，不再随 loading 卸载重建。
- **修复 3**：长标题撑破 200px 二级目录（表现为右侧「报告」被裁切、出现横向滚动条）——
  `.subnav__item` 补 `min-width: 0`、标题 `flex: 1; min-width: 0` 截断、计数 `flex: none`，
  并给 `.subnav` 加 `overflow-x: hidden` 与 `scrollbar-gutter: stable` 消除滚动条抖动。
- **顺带**：首次进入自动选中第一条选题，右侧不再停留在「从左侧选择一条选题」空态。

### 变更（信息架构重做 · PTA 式一级集块 + 二级目录）

> 详细方案：`docs/CHANGE-ia-pta-sidebar.md`。色值 token 零修改、业务逻辑零改动、路由路径零改动。

- **一级栏改为可折叠功能集块**：`nav.js` 由平铺列表改为 `{ group, icon, children }` 两级结构，
  教师端「教学班 / 成绩 / 协作 / 个人」，学生端「我的学习 / 我的成绩 / 协作 / 个人」；
  集块名可点（进第一个功能）、箭头单独控制展开，折叠态收为 64px 图标栏。
  展开 220px / 折叠 64px 只改宽度，`--sidebar` 深色底未动。
- **课程详情页去掉顶部平铺 tab**：8 个分区改为左侧纵向目录（教学准备 / 教学执行 / 课程沉淀），
  选中项带 2px 主色指示条；`?section=students` 与目录双向同步，刷新保持、非法值回退 `overview`、
  用 `replace` 不污染历史；`/offerings/12` 旧 URL 仍直接可用。`WorkspaceNav` 已删除。
- **默认落地页改变**：`/` 按角色重定向到 `/offerings`（教师）或 `/courses`（学生）；
  原概览页的 KPI / 待办 / 通知收敛为列表页顶部**一条紧凑摘要条**（可折叠待办），
  不再是大卡片堆叠。`DashboardView` 与 `views/home/*` 已删除。
- **学生提交页改为左目录 + 右详情**：卡片网格 + 抽屉改为左侧题目目录 + 右侧常驻详情，
  上传 / 评审状态 / 版本切换 / 文件预览全部保留；抽屉的「关闭」按钮移除（改由切换题目达成）。
- **选题页难度筛选移到左侧目录**，右侧为搜索 + 题目列表；底部「已选 N 道题」固定条跟随侧栏宽度自适应。
- **答辩页进入沉浸模式**：`meta.immersive` 隐藏一级栏，把宽度让给答题区，页头面包屑保留退出入口。
- **全站文案清除「工作台」字样**（含路由标题、登录页、列表按钮、主题描述等 8 处）。

### 变更（架构级解耦 · 图标轨道 + 双端独立信息架构）

> 详细方案：`docs/CHANGE-ia-decouple-rail.md`。背景色 / 品牌色 / 语义色零新增，接口 / 状态机 / 权限 / 校验零改动，路由路径与守卫零改动。

- **骨架改为图标轨道（rail）**：`--sidebar-w` 244px → 76px，一级导航只放「图标 + 两字」，
  深色侧栏底色 `--sidebar` 保持不变；`--topbar-h` 62px → 52px，顶栏去掉重复的大标题，
  只留「角色端 > 当前页」面包屑与工具区；内容区 `max-width` 1320px → 1600px。
  主内容可用宽度增加约 168px，长表格与两列布局不再挤在窄槽里。
- **导航从「一张表 + roles 过滤」改为双端独立树**：`config/nav.js` 拆出 `TEACHER_NAV` /
  `STUDENT_NAV` / `ADMIN_NAV`，条目归属由所在树决定，不再靠 `roles` 数组拼装；
  `navGroupsForRole()` / `navItemsForRole()` 签名与返回结构不变，命令面板不受影响。
- **首页按角色拆分**：`DashboardView.vue` 变成 10 行薄壳，业务拆到
  `views/home/TeacherHome.vue`（教学驾驶舱：KPI + 教学班行列表 + 运行情况 + 待办）
  与 `views/home/StudentHome.vue`（任务动线：六步条 + 「下一步」引导卡 + 材料进度 + 截止提醒）。
  原先单文件里 16 处 `isTeacher` 分叉全部消除。
- **成绩页按角色拆分**：`GradesView.vue` 变成薄壳，
  `views/grades/TeacherGrades.vue`（选教学班 + 重算发布）与
  `views/grades/StudentGrades.vue`（只读成绩卡）各自独立，7 处角色分叉消除。
- **教学班列表改为行列表**：7 列表格换成「课程图标 + 课程名/状态 + 元信息副标题 + 右侧操作组」，
  整行可点进工作台；学生数 / 题目数 / 已选数 / 学期并入副标题，信息不丢但更易扫读。
- **管理员页接入统一页头**，与其余页面结构一致（此前是手写 `h2` + 按钮行）。

### 变更（信息架构重组 · 教师端 / 学生端）

> 详细方案：`docs/CHANGE-ia-restructure-teacher-student.md`。仅结构与样式，接口/状态机/权限/校验零改动。

- **导航按角色分组**：`nav.js` 的 `group` 支持 `{ TEACHER, STUDENT }` 双写，教师端「教学」、
  学生端「学习」；条目与路径一个未改，`navItemsForRole()` 行为不变。
- **工作台吸顶上下文条**：课程名 + 状态 + 元信息 + 发布/归档，与紧凑版二级导航一起
  `sticky top: var(--topbar-h)`，滚动长表格仍可切 tab。
- **tab 页头统一**：新增 `TabHead`（标题 / 说明 / 计数 / 右侧主操作），学生、题目、提交评审、
  成绩、答辩五个 tab 接入；主操作从筛选行上移，破坏性操作（撤销发布）与主操作分离，
  批量操作改为选中后才出现。
- **学生动线**：新增 `config/flow.js` 的 `STUDENT_FLOW` 与 `StepFlow` 组件，
  课程 / 选题 / 我的选题 / 答辩 / 成绩五处显示「进入课程→选题→提交→AI 评审→答辩→成绩」，
  当前步高亮；答辩页学生端还可看到违规计数。
- **教师首页**：主操作改为「教学班管理 / 去选题」，任务中心降为次操作；待办按
  `danger > warn > info > ok` 排序。
- **答辩页页头**换用 `PageHeader`，与其余页面结构一致。

### 新增（评审状态可观测 · 批量发起答辩 · 三原则题目体系，V13）

> 详细方案：`docs/CHANGE-review-status-and-defense-rules.md`；迁移：`V13__review_status_and_defense_rule.sql`。

- **评审状态接口** `GET /submissions/{id}/review-status`：把源码与报告两侧各自的
  `PENDING/RUNNING/SUCCEEDED/FAILED` 聚合成一个 `overall` + `finished`。前端只认 `finished`
  决定是否停轮询（此前用「源码评完」当收尾信号，报告还在跑就已经停刷，页面就一直转圈）。
  前端改为 2s→4s→8s→10s 指数退避、`visibilitychange` 补拉、30s 未完成给「立即刷新」、
  失败只对可重试的那一侧给重试入口；两侧独立 loading/error、卸载 abort、请求序号防旧覆盖。
- **失败不再伪装成 0 分**：`AiEvaluationService.fail()` 改为 `score=null + error_message`，
  成功时清空 error——「评了但答得差」和「没评出来」必须能区分。
- **评审看门狗** `ReviewWatchdog`：`@EnableScheduling` + 默认 120s 扫一次，超过
  `stale-minutes=15` 仍停在 PENDING/RUNNING 的任务收敛为 FAILED 并可重试（进程重启/OOM 不再留下永久 RUNNING）。
- **报告漏入队补偿**：源码成功后 `enqueueReportIfNeeded()` 在「报告版本存在 && 业务记录缺失或 PENDING &&
  无 PENDING/RUNNING 任务」三个条件同时成立时补入队，包 try/catch 不拖垮已成功的源码评审。
- **批量发起答辩** `POST /teacher/defenses/batch` + `GET /teacher/defenses/batch/{id}`：逐学生结果
  （成功 sessionId / 跳过原因 / 失败原因），单条失败不影响其余；后台线程显式传
  `SecurityContextHolder`（池化线程不继承 ThreadLocal），计数在库里原子累加、收尾只 UPDATE 状态。
  前端多选（单选/全选筛选结果）→ 底部操作条 → 确认弹窗（题数分布）→ 进度 → 汇总 → 仅重试失败项。
- **题数分档替换线性公式**：`0-59→6 / 60-74→7 / 75-84→8 / 85-94→9 / 95-100→10`，同档同量、上限 10、
  越界夹取、历史不追溯；手改题数必须填理由并落 `question_override_reason`。
  难度配比基础 40/40/20、T3 起 30/40/30，用最大余数法保证求和等于题数。
- **三原则题目体系**：题目打 `P1_RETELL / P2_REASON / P3_MODIFY` 维度标签，每次每维度至少 1 题；
  维度平均分 ≥60 为通过，两项及以上不通过 → `ai_dependent`。**判定只在服务端算**，AI 只给分数与证据；
  题干不暴露「第几原则」。教师可覆盖判定，必须填理由（≥5 字），仅留痕不改分，原始结论保留。
- **加分题**：固定 1 道、`IS_BONUS`、排最后、不计总分/等级/判定，限时 120s、可跳过（`SKIPPED`），
  结果三态 `CORRECT/WRONG/UNANSWERED`；学生端只看「不计入总分」，教师端看三态 + 答案 + 简评。
- **配置化**：`sss.ai.review.stale-minutes/watchdog-*`、`sss.defense.question-rule.*`、
  `sss.defense.principle.*`、`sss.defense.batch.*`；`GET /teacher/defense/question-rule` 是前端预览的唯一来源。

### 修复（批量发起全部「未登录或登录已过期」）

- **根因**：`run()` 在线程 A 设置了 `SecurityContext`，但再扇出到 `Executors.newFixedThreadPool`
  的工作线程 B/C 时 ThreadLocal 不继承，`createSession` 内的归属校验拿不到登录用户。
  改为身份显式下传到每个 `runOne`，逐线程 `setAuthentication` + `finally clearContext`。
- **批次计数被冲回 0**：`record()` 用 SQL 原子 `+1`，收尾若 `updateById(内存对象)` 会把计数覆盖成创建时的 0。
  改为 `LambdaUpdateWrapper` 只更新 `status / finished_at`，再同步回内存对象供返回。

### 新增（提交物多文件：源码 1~9 份、报告 1~9 份）

- **数据模型**：新增 `submission_version_file`（一个版本 = 1~9 个文件），文件挂在
  `submission_version` 上而不是 `submission` 上——「每类仅显示最新版本」是展示策略，
  旧版本的多文件组合不能丢。`V10__submission_multi_file.sql` 建表 + 历史回填 + 相似度表；
  `submission_version.file_id` 保留写入首个文件，历史读取路径不变。
- **两阶段提交**：`POST /student/selections/{id}/files?type=…` 单文件落盘换 `fileId`，
  `POST /student/selections/{id}/submissions` 绑定 `fileIds` 生成新版本。
  一文件一请求，才有逐文件进度、逐文件重试与取消；坏包被拒时其余文件不用重传。
- **校验双端同源**：`GET /meta/submission-policy` 下发源码/报告的扩展名、数量、单文件上限，
  前端 accept 与即时拦截、后端硬校验都读它。源码每份 zip 独立跑 Zip Slip / 压缩炸弹扫描，
  另有文件头魔数校验（改后缀的 `.exe` 会被 400 拒掉，错误码 `MAGIC_MISMATCH`）。
- **删除与排序**：`DELETE /submissions/{id}/files/{versionFileId}` 删单个文件（**不**自动重新评审，
  决策权留给人）；`PUT /submissions/{id}/files/order` 调整顺序，顺序决定评审时的文件优先级。
- **AI 多文件合并评审**：`AiEvaluationService` 把所有源码包合并成一份「逻辑工程」送审
  （统计相加、代码片段按顺序拼接、超 6 万字符截断并标注），payload 新增 `sourceFiles` /
  `reportFiles` 清单；FastAPI 侧 `CodeReviewRequest.source_files` 与
  `ReportEvaluateRequest.report_files` 接受该清单并在提示词里说明「由哪几份文件组成」，
  旧版单对象调用不受影响。
- **相似度检测**：`submission_similarity` 记录命中，两级判定——`IDENTICAL`（SHA-256 相同，1.0）与
  `SAME_STRUCTURE`（源码包条目名集合相同、内容不同，0.75）。只与其他同学的当前最新版本比对，
  同一学生自己的版本之间默认不比（`similarity.compare-within-same-student=false`）。
  命中写审计日志，接口 `GET /submissions/{id}/similarities` 供本人与教师查看。
- **配置归位**：单文件上限统一到 `sss.upload.submission.{source,report}.max-file-bytes`，
  删掉 `sss.storage.max-source-bytes / max-report-bytes`——同一个限额不再有两处真相。
- **前端**：新增 `components/submission/MultiFileUploader.vue`（多选、拖拽排序、逐文件进度/失败原因/
  重试/取消/删除、满额禁用），`SelectionsView` 的「已提交材料」改为按源码/报告分组列出全部文件，
  支持逐个下载与删除。

### 修复（批量添加学生报「服务端异常」+ 初始密码=学号）

- **根因**：前端调用的 `.../students/import/parse-text` 在运行中的后端进程里并不存在
  （进程是加导入功能之前的旧构建），Spring 抛 `NoResourceFoundException` 后被
  `GlobalExceptionHandler` 的 `Exception` 兜底成 `50000 服务端异常`，message 与 traceId 都指不到真因。
  重启后端即恢复；同时补上根因级护栏，让这类「版本不一致」不再伪装成服务崩溃。
- **异常映射**：新增 `NoResourceFoundException → 40401 接口不存在：POST /path`（带 `data.hint`），
  `HttpRequestMethodNotSupportedException → 40501`（带 `data.supportedMethods`），
  `ErrorCode` 增加 `METHOD_NOT_ALLOWED(40501)`；新增 `GlobalExceptionHandlerTest` 3 例守卫「路由未匹配不能是 5xx」。
- **前端提示**：`stores/toast.js` 抽出 `errorText()`，行内红字与 Toast 都展示后端 message +
  **完整** traceId（原来截断到 12 位，日志里搜不到）；导入弹窗的解析失败、单个添加失败改用它，
  `40001` 时优先展示字段级中文原因（不再只显示「参数校验失败」）。
- **初始密码改为「密码 = 学号」**：`sss.student.default-password-mode`（`STUDENT_NO` 默认 / `FIXED` 可选），
  新建与重置密码都按该策略；批量导入改为「按学号并行计算哈希」（同密码去重），
  50 条 0.53s、500 条约 3s，仍保持 `must_change_password=1` 首次强制改密。
- **文档纠偏**：`API接口文档.md` 第 5 章按实际实现重写（两段式解析/确认、逐行状态、幂等键、
  真实限额 2MB/500 行），README 演示账号与排障表补 `40401 / 40501` 两条。
- 详见 [docs/FIX-student-batch-import-500.md](docs/FIX-student-batch-import-500.md)。

### 改造（题目模块瘦身 + 多文件附件上传）

- **题目只保留四个字段**：标题 / 描述 / 难度 / 附件。交付要求、标签、期望技术栈、容量上限、草稿状态
  全部从请求体、响应体、前端表单与列表中移除；`V9__simplify_topic.sql` 物理删除对应 5 列并重建难度索引；
- **取消题目状态机**：题目创建即对学生可见，删除 `publish` / `publishArchive` / 批量发布三个端点；
  教学班发布前置校验从「至少一道已发布题目」改为「至少一道题目」；
- **取消人数上限**：`TopicExtraMapper.reserveOne` 去掉 `selected_count < max_select_count`，
  学生端不再有「名额已满 / 剩余名额」；`selected_count` 退化为「已选人数」统计；
- **难度成为唯一分类维度**：`Difficulty` 枚举（编码 + 展示名 + 排序值），
  新增 `GET /meta/difficulties`；写入非法值 400、历史非法值展示「未分级」；建 `(offering_id, difficulty)` 索引；
- **多文件附件上传**：新增 `POST /files/batch`（字段 `files`，部分失败不影响其它文件）；
  扩展名 + 魔数 + MIME 三重校验（改后缀的伪 PDF / 伪 DOCX、`.exe` 均被拒）；白名单与限额统一在
  `sss.upload.topic-attachment.*`，前端通过 `GET /meta/upload-policy` 读同一份配置；
- **附件管理**：新增挂载 / 解绑 / 排序三个接口；解绑后若文件无人引用则连同磁盘一并清理；
  PDF 支持 `?inline=true` 内嵌预览，其它类型强制下载；
- **前端题目页重写**：难度筛选、批量删除、逐文件上传进度与重试、PDF 预览、离开提醒、
  防重复提交、「保存并继续新建」；失败提示展示具体 message 与 traceId。
- 详见 [docs/FIX-topic-simplify-and-upload.md](docs/FIX-topic-simplify-and-upload.md)。

### 修复（新建教学班 500 + 教学班配置精简）

- **新建教学班 500**：`OfferingService.validateTimeline` 用 `List.of(...)` 收集可为 null 的时间节点，
  「答辩结束」缺失时抛 `NullPointerException`，被兜底成 `50000 服务端异常`。改用允许 null 的
  `Arrays.asList`，相邻节点仅在有值时比较；详见 [docs/FIX-offering-create-500.md](docs/FIX-offering-create-500.md)；
- **移除数量限制**：`topic_select_limit`（每人最多选几题）与 `question_count`（答辩题目数）不再由教学班配置，
  `V8__remove_offering_limits.sql` 让两列可空、存量统一置 NULL；**NULL = 不限**，选题校验、学生端卡片、
  AI 出题均不得把 NULL 当成 0；
- **删除答辩模式**：前端下拉与 DTO/实体字段移除，固定 AI 异步答辩；`offering.defense_mode` 列保留停用，
  历史数据仍可读取；
- **删除课程模板选择**：新建弹窗不再选择模板，`courseId` 改为可选，缺省绑定系统内第一条模板
  （`course_id` 允许 NULL）；
- **前端错误提示**：新建教学班与设置页的错误区域展示后端具体 `message` 与 `traceId`，
  不再统一显示「服务端异常」。

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
