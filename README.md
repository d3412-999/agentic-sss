# 课程设计智能答辩与评分系统（agentic-sss）

> 面向《面向对象程序设计课程设计》这类课程的 AI 辅助教学系统，覆盖 **选题 → 提交 → 源码/报告评审 → 语音答辩 → 评分发布** 全流程。

Spring Boot 3 后端（59 个 REST 接口、22 张表、JWT 鉴权）+ Vue 3 工作台前端 + 独立部署的 FastAPI AI 服务。

<!-- 部署到 GitHub 后把 d3412-999/agentic-sss 换成实际地址，并放开下面这行 CI 徽章 -->
[![CI](https://github.com/d3412-999/agentic-sss/actions/workflows/ci.yml/badge.svg)](https://github.com/d3412-999/agentic-sss/actions/workflows/ci.yml)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-42B883?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![Vite](https://img.shields.io/badge/Vite-6-646CFF?logo=vite&logoColor=white)](https://vitejs.dev/)
[![FastAPI](https://img.shields.io/badge/FastAPI-Python%203.12-009688?logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7.x-DC382D?logo=redis&logoColor=white)](https://redis.io/)

![教师概览](docs/screenshots/02-teacher-dashboard.jpg)

<table>
<tr>
<td width="50%"><img src="docs/screenshots/04-topic-select.jpg" alt="学生选题中心" /></td>
<td width="50%"><img src="docs/screenshots/01-login.jpg" alt="登录页" /></td>
</tr>
</table>

## 目录

- [为什么做这个项目](#为什么做这个项目)
- [功能特性](#功能特性)
- [界面](#界面)
- [技术栈](#技术栈)
- [架构](#架构)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [配置](#配置)
- [API](#api)
- [用 IDEA 运行](#用-idea-运行)
- [开发约定](#开发约定)
- [测试与 CI](#测试与-ci)
- [文档](#文档)
- [常见问题](#常见问题)
- [已知限制](#已知限制)
- [路线图](#路线图)
- [贡献](#贡献)
- [许可证](#许可证)

## 为什么做这个项目

课程设计的痛点在“过程”而不是“结果”：出题靠 Excel、选题靠抢、材料散落在群文件里、答辩时凭印象给分，
最后成绩构成说不清楚。这个项目把整条流程收进一个系统：教师配置开课 → 学生选题并提交材料 →
系统异步做源码/报告评审 → 教师核对分数并发布，每一步都有状态、留痕与可解释的算分依据。

它同时是一个**全栈工程实践样本**：真实的分层后端（Controller / Service / Mapper + Flyway 迁移）、
无 UI 框架的前端设计系统、独立部署的 AI 服务，以及可复现的本地环境（Docker Compose）与 CI。

## 功能特性

| 模块 | 能力 | 状态 |
|---|---|---|
| 认证 | 登录 / 刷新 / 登出 / 改密 / 当前用户，BCrypt + 无状态 JWT + 令牌版本号即时失效 | ✅ |
| 教学组织 | 课程模板、开课生命周期（草稿→发布→关闭→归档）、学生名单、教学仪表盘 | ✅ |
| 题目与选题 | 出题、批量发布/归档、学生浏览；选题用「原子 SQL 预占 + 唯一索引」防超选 | ✅ |
| 提交与文件 | 源码 zip / 报告上传（SHA-256 秒传、Zip Slip 检查）、版本历史、异步解析 | ✅ |
| AI 评审 | 异步调用 AI 服务做源码与报告评审，结果落库并参与算分；PDFBox / POI 抽取报告正文 | ✅ 已实现（真实评审需配置 LLM_API_KEY） |
| 优秀作品模板库 | 教师挑选优秀报告加入 AI 模板库（LightRAG 优先，本地兜底），供后续报告评审 RAG 参考 | ✅ |
| 成绩 | 三维加权算分、人工调整（原因入审计）、发布/撤销、异步导出 CSV、学生查询 | ✅ |
| 通知与任务 | 站内通知、跨页面任务中心（进度轮询、取消、结果查看） | ✅ |
| 前端工作台 | 12 个页面、深色主题、命令面板（Ctrl K）、响应式布局、骨架屏与空态 | ✅ |
| 语音答辩 | 教师发起答辩（AI 出题）→ 学生逐题录音 → ASR 转写 → AI 评分 → 成绩；摄像头「在场检测」（只判画面里有没有人，不落库图片） | ✅ 主链路已实现（实时流式代理、人脸身份识别为下一阶段） |
| 静态分析 | Checkstyle / PMD / SpotBugs 调度与问题明细落库 | 🚧 未实现 |

## 界面

| 页面 | 角色 | 说明 |
|---|---|---|
| 概览 `/` | 全员 | 角色化 KPI、教学班进度、材料流水线、通知与流程说明 |
| 教学班 `/offerings` | 教师 | 状态筛选、新建开课抽屉、课程模板、发布阻塞项逐条提示 |
| 开课工作台 `/offerings/:id` | 教师 | 概览 / 题目 / 学生 / 成绩 / 优秀作品 / 答辩 / 设置 七个页签 |
| 我的课程 `/courses` | 学生 | 课程卡片、选题窗口、限额与不可选原因（后端枚举码已翻译成中文） |
| 选题中心 `/courses/:id/topics` | 学生 | 卡片化题目、多选、名额可视化、详情抽屉 |
| 我的选题 `/selections` | 学生 | 五步材料流水线、拖拽上传（带进度）、版本历史、重新评审、退选、进入答辩 |
| 答辩 `/defense/:sessionId` | 教师/学生 | 逐题录音作答（WAV 上传 → ASR 转写）、摄像头在场检测、结束答辩、教师 AI 评分 |
| 成绩中心 `/grades` | 教师/学生 | 教师：摘要+表格+调整/发布/导出；学生：分项与综合分构成 |
| 任务中心 `/tasks` | 全员 | 本机发起的异步任务、进度、取消、结果 JSON |
| 通知 / 设置 | 全员 | 未读筛选与已读、改密、主题切换、会话安全 |

界面细节：

- **设计系统**：CSS 变量驱动的浅色/深色双主题（跟随系统偏好并本地记忆），品牌靛蓝 + 青色强调；
- **交互**：破坏性操作走确认弹窗，异步结果用 Toast（附带 `traceId` 便于排查），列表有骨架屏与「下一步」空态；
- **快捷入口**：`Ctrl / Cmd + K` 打开命令面板，可跳转任意有权限的页面、切换主题、打开 Swagger；
- **响应式**：≥1180px 四列 KPI；≤900px 侧边栏折叠为抽屉、隐藏面包屑；420px 可用。

<p align="center">
  <img src="docs/screenshots/06-dark-theme.jpg" alt="深色主题" width="70%" />
</p>

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3.5 + Vite 6 + Vue Router 4（原生 `fetch` 封装，无 UI 框架、无图标库） |
| 后端 | Spring Boot 3.3.5、Java 17、MyBatis-Plus 3.5.7、Flyway 10.22 |
| 安全 | Spring Security 6 + JJWT 0.12，`Authorization: Bearer <token>` |
| 存储 | MySQL 8（Flyway 自动建表）、Redis 7（限流 / 幂等 / 令牌）、本地磁盘（`./data/objects`） |
| AI 服务 | Python 3.12+、FastAPI、LangChain（OpenAI 兼容）+ LightRAG（可选） |
| 构建 | Gradle 9 + Wrapper（`./gradlew build`）+ GitHub Actions |

## 架构

```
浏览器 (Vue 3 :5173)
   │  REST + Bearer JWT   { code, message, data, traceId }
   ▼
Spring Boot :8081  ── Controller → Service → Mapper
   │        │              │
   │JDBC    │Redis         │HTTP + X-Internal-Token
   ▼        ▼              ▼
MySQL:3306  Redis:6379   FastAPI :8000（AI 能力，不对外暴露）
   │
   └── 本地磁盘 ./data/objects（FileStorageService）
```

## 目录结构

```
agentic-sss/
├── .github/
│   ├── workflows/ci.yml           # 三模块并行 CI
│   ├── ISSUE_TEMPLATE/            # Bug / 功能建议表单
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── dependabot.yml             # 依赖自动升级
├── docker-compose.yml             # 本地 MySQL + Redis
├── .env.example                   # 环境变量样例
├── .editorconfig / .gitattributes # 编辑器与换行约定
├── docs/
│   ├── 需求文档.md                 # 角色/状态机/功能需求/创新点/验收清单
│   └── screenshots/               # README 用截图
├── scripts/
│   ├── init-local-db.sql          # 用本机 MySQL 时建库建账号
│   └── start-redis.ps1            # Windows 下启动本机 Redis
├── backend/                       # 后端（Gradle 工程）
│   ├── build.gradle / settings.gradle / gradle.properties
│   ├── gradlew / gradlew.bat / gradle/wrapper/
│   ├── tools/gen_entities.py      # 从 DDL 反向生成 entity / mapper
│   └── src/main/
│       ├── java/org/spring_web/spring_boot_service/
│       │   ├── controller/ service/ mapper/ entity/ dto/
│       │   ├── config/ security/ common/ client/
│       │   └── SpringBootServiceApplication.java
│       └── resources/{application.yaml, db/migration/}
├── langchain_service/             # AI 服务（FastAPI）
│   ├── main.py
│   └── app/{core,api,schemas,prompts,services,rag,utils}/
├── vue/                           # 前端
│   ├── vite.config.js
│   └── src/
│       ├── components/            # Icon / AppLayout / Modal / Drawer / Pager ...
│       │   └── offering/          # 工作台五个页签
│       ├── views/                 # 12 个页面
│       ├── stores/                # session / theme / toast / confirm / tasks
│       ├── api/                   # http 封装 + 59 个接口映射
│       └── styles/main.css        # 设计系统
├── CONTRIBUTING.md / SECURITY.md / CODE_OF_CONDUCT.md
├── CHANGELOG.md
└── LICENSE
```

## 快速开始

### 1. 环境要求

| 工具 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 随仓库自带 Wrapper（9.0.0，无需本机安装） |
| Node.js / npm | 18+（推荐 20）/ 9+ |
| Python | 3.12+（仅 AI 服务需要） |
| MySQL | 8.0+ |
| Redis | 7.x（**必需**，登录与幂等依赖它） |

### 2. 启动基础设施

```bash
cp .env.example .env
docker compose up -d          # MySQL + Redis
docker compose ps             # 等两个服务变成 healthy
```

若用本机已装的 MySQL / Redis：

```bash
mysql -h 127.0.0.1 -u root -p < scripts/init-local-db.sql   # 建 sss 库与应用账号
powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1
```

### 3. 启动后端（:8081）

```bash
cd backend
./gradlew bootRun             # Windows: gradlew.bat bootRun
```

首次启动 Flyway 自动建 22 张表并写入演示数据。

| 地址 | 说明 |
|---|---|
| http://127.0.0.1:8081/api/v1/health | 健康检查（免鉴权） |
| http://127.0.0.1:8081/swagger-ui.html | Swagger UI（Authorize 填 accessToken） |
| http://127.0.0.1:8081/actuator/health | Actuator |

### 4. 启动前端（:5173）

```bash
cd vue
cp .env.example .env
npm install
npm run dev
```

### 5. 启动 AI 服务（:8000，可选）

```bash
cd langchain_service
python -m venv .venv
.venv\Scripts\activate            # Linux / macOS: source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
python main.py
```

不启动 AI 服务也能跑通主链路，涉及 AI 的能力会降级为 `50301 AI_UNAVAILABLE`。

没有大模型 Key 时，可以在 langchain_service/.env 里把 LLM_PROVIDER 改为 mock，AI 接口会返回结构完整的示例数据，便于前后端联调。

**语音转写 / 在场检测（可选，答辩需要）**：默认 `ASR_PROVIDER=mock`、`FACE_PROVIDER=mock`，不装模型也能联调整条答辩链路。要切换成真实能力：

```bash
cd langchain_service
pip install -r requirements-optional.txt   # sherpa-onnx + opencv-python-headless
# 下载中文流式/离线模型到 ./models/ 并把 ASR_MODEL_DIR 指过去（含 tokens.txt 与 *.onnx）
# .env: ASR_PROVIDER=sherpa_onnx  FACE_PROVIDER=opencv
```

模型与接口细节见 [docs/defense-asr-face.md](docs/defense-asr-face.md)。

### 6. 演示账号

密码统一为 `123456`（由 `V2__seed_demo_data.sql` 写入，**仅用于本地演示**）。

| 账号 | 姓名 | 角色 |
|---|---|---|
| `2023010101` / `2023010102` / `2023010103` | 张三 / 李四 / 王五 | 学生 |
| `T2019008` / `T2019009` | 李文博 / 周敏 | 教师 |
| `admin` | 系统管理员 | 管理员 |

## 配置

所有配置项都带默认值，改一处需同步相关位置：

| 配置 | 默认值 | 位置 |
|---|---|---|
| 后端端口 | `8081` | `application.yaml` `server.port` ↔ `vue/.env` `VITE_API_TARGET` |
| 前端端口 | `5173` | Vite dev server ↔ `sss.cors.allowed-origins` |
| AI 服务端口 | `8000` | `langchain_service/.env` `AI_PORT` ↔ `sss.ai.base-url` |
| 数据库 | `sss` / `sss` / `sss123456` | `.env` ↔ `application.yaml` ↔ `docker-compose.yml` |
| Redis 口令 | `123456` | `.env` `REDIS_PASSWORD` ↔ `application.yaml` |
| 内部令牌 | `change-me` | `application.yaml` `sss.ai.internal-token` ↔ `langchain_service/.env` |
| JWT 密钥 | `change-me-...` | `application.yaml`（≥ 32 字节） |
| ASR 提供方 | `mock` | `langchain_service/.env` `ASR_PROVIDER`（`mock` / `sherpa_onnx`） |
| 人脸在场提供方 | `mock` | `langchain_service/.env` `FACE_PROVIDER`（`mock` / `opencv`） |

完整环境变量清单见 `.env.example`。Spring Boot 不读取 `.env` 文件，它读取同名环境变量；不设置时使用 `application.yaml` 里的默认值。

> 端口默认 8081 是因为 8080 常被其他本地服务占用。改为 8080 时请同时改 `vue/.env`。

## API

所有响应统一为 `{ code, message, data, traceId }`，成功时 `code = 0`；响应头带 `X-Trace-Id`；雪花 ID 序列化为字符串。

| Controller | 端点数 | 路径 |
|---|---|---|
| AuthController | 5 | `/api/v1/auth/*` |
| TeacherOfferingController | 15 | `/api/v1/teacher/*` |
| TopicController | 10 | `/api/v1/teacher|student|topics` |
| StudentSelectionController | 5 | `/api/v1/student/*` |
| SubmissionController | 9 | `/api/v1/student/selections/{id}/...`、`/api/v1/files/*` |
| GradeController | 9 | `/api/v1/teacher/grades/*`、`/api/v1/student/grades/*` |
| DefenseController | 12 | `/api/v1/teacher/defense/*`、`/api/v1/defense/sessions/*`（含录像 POST/GET）、`/api/v1/student/defense/*` |
| MiscController | 5 | `/api/v1/notifications/*`、`/api/v1/tasks/*` |
| HealthController | 1 | `/api/v1/health` |

鉴权规则：`/teacher/**` 需 `TEACHER`，`/student/**` 需 `STUDENT`，健康检查、登录、刷新、Swagger、actuator 放行。参数细节以 Swagger 为准。

## 用 IDEA 运行

仓库**不提交** `.idea/`（含本机 SDK 名与路径的配置会给其他人添麻烦），clone 后：

1. `File → Open` 选择仓库根目录，等待 Gradle 同步 —— IDEA 会自动识别 `backend/` 为 Gradle 工程，用自带 Wrapper；
2. 若 SDK 名称不一致，在 `Project Structure → Project SDK` 选择本机 JDK 17；
3. 自己建三个运行配置即可：
   - 后端：Spring Boot 类型，主类 `org.spring_web.spring_boot_service.SpringBootServiceApplication`，
     VM 参数 `-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai`；
   - 前端：npm 类型，`package.json` 指向 `vue/package.json`，命令 `run dev`；
   - AI 服务：Shell / Python 类型，解释器用 `langchain_service/.venv`，脚本 `langchain_service/main.py`。

## 开发约定

- 统一响应包与错误码：`common/Result.java`、`common/ErrorCode.java`；
- 业务异常抛 `BizException`，由 `GlobalExceptionHandler` 转换；
- 主键为 BIGINT 雪花 ID（MyBatis-Plus `ASSIGN_ID`），不建物理外键，并发表带 `version` 乐观锁；
- 逻辑删除字段 `deleted`，**手写 SQL 必须自己加 `deleted = 0`**；
- 表结构只通过 `db/migration/V*.sql` 变更，已执行过的 V1/V2 不要修改；
- 实体与 Mapper 由 `tools/gen_entities.py` 从 DDL 生成，改表后请重新生成；
- 前端不引入 UI 框架：组件与样式集中在 `src/components` 与 `src/styles/main.css`，新增页面复用既有 class。

```bash
cd backend && ./gradlew build                             # 构建后端（含 src/test 纯逻辑单测）
cd vue && npm run build                                   # 构建前端
cd langchain_service && python -m pytest -q               # AI 服务测试（mock 模式）
python backend/tools/gen_entities.py                      # 重新生成实体
```

## 测试与 CI

三端都有自动化测试，CI 会真实执行：

| Job | 命令 | 说明 |
|---|---|---|
| Backend | `./gradlew build --no-daemon` | JDK 17 + Gradle 缓存；执行 `src/test/java/.../common` 下的纯逻辑单测 |
| Frontend | `npm ci && npm run build` | Node 20，锁定 `vue/package-lock.json` |
| AI service | `pip install -r requirements.txt` + `python -m pytest -q` | Python 3.12，mock provider 跑接口冒烟测试，之后再做一次导入检查 |

- 后端单测集中在 `common` 包：压缩包安全校验（`ArchiveGuard`）、AI 分数校验（`ScoreGuard`）、
  成绩数学（`GradeMath`）、CSV 单元格（`CsvUtil`）、可信代理 IP 解析（`TrustedProxyIp`）——
  这些类刻意只依赖 `java.*`，因此可以脱离 Spring 容器直接测试；
- AI 服务测试位于 `langchain_service/tests`（29 个用例），`LLM_PROVIDER=mock` 时无需网络与 Key；
- 并发选题、乐观锁、幂等等依赖真实 MySQL/Redis 的回归测试尚未落地，计划用 Testcontainers 补齐。

配置见 [`.github/workflows/ci.yml`](.github/workflows/ci.yml)。


## 文档

- [需求文档](docs/需求文档.md)：角色权限、状态机、功能需求与验收标准、创新点、非功能需求；
- [贡献指南](CONTRIBUTING.md)：目录约定、提交规范、代码约定、安全红线；
- [安全策略](SECURITY.md)：默认配置风险、漏洞报告方式、已处理攻击面；
- [更新日志](CHANGELOG.md)：版本变更与后续计划；
- 本文：运行、配置、接口概览；
- 运行后访问 Swagger 查看全部 59 个接口的参数与示例。

## 常见问题

| 现象 | 原因 / 解决 |
|---|---|
| 登录返回 `50302 Redis 不可用` | Redis 没起：`docker compose up -d redis` 或 `scripts/start-redis.ps1`（脚本会按 `REDIS_HOME`/PATH/常见目录查找 redis-server.exe） |
| 接口返回 `50303 数据库不可用` | MySQL 没起或端口不是 3306，确认后重启后端 |
| 登录返回 `42901` | 同一用户名或同一 IP 1 分钟内失败过多（用户名 5 次、IP 20 次），等 1 分钟 |
| 选题返回 `42201 TOPIC_FULL` | 题目名额已被别人抢完，刷新题目列表重选 |
| 上传后一直 `PARSING` | AI 服务未启动：`cd langchain_service && python main.py`，不启动会在降级后停在解析失败 |
| `gradlew` 报 JDK 版本错误 | Gradle 9 要求 JDK 17+，`JAVA_HOME` 指向 JDK 17（例如 `D:\a\jdk\jdk17`） |
| 前端 404 / 代理打空 | `vue/.env` 的 `VITE_API_TARGET` 与后端端口不一致，改完重启 `npm run dev` |

## 已知限制

- 答辩主链路已实现（AI 出题、逐题录音 + ASR 转写、教师 AI 评分、摄像头「在场检测」）；**WebSocket 实时流式转写**的 Java 代理与**人脸身份识别/活体**属于下一阶段，当前是用 WAV 整段上传 + OpenCV 正脸检测（只判画面里有没有人）。
- 默认 `ASR_PROVIDER=mock` / `FACE_PROVIDER=mock`，真实能力需要安装 `requirements-optional.txt` 并下载模型；sherpa-onnx/opencv 真实链路需在目标机复核。
- 报告正文已用 PDFBox（PDF）/ Apache POI（DOCX）抽取；扫描版 PDF 若没有文本层，仍会退回“无正文”，需要 OCR 才能评分。
- AI 评审已做提示注入防护（不可信材料包装 + 操纵话术检测）与 0~100 分数区间校验，但检测是启发式的，不能保证穷尽新措辞；最终分数仍由教师复核发布。
- 前端令牌存放在 sessionStorage（按标签页隔离、关闭即清除），同源 XSS 仍可读取；根治需要后端 HttpOnly Cookie。
- 默认口令与占位密钥未做启动期 fail-fast，部署到生产前必须手工替换（见 SECURITY.md）。
- 演示数据与默认口令仅供本地使用。部署到生产前必须：删除或隔离 `V2__seed_demo_data.sql`、替换 `JWT_SECRET` 与 `AI_INTERNAL_TOKEN`、收紧 CORS 白名单、更换全部默认口令、限制 AI 服务网络访问（详见 [SECURITY.md](SECURITY.md)）。

## 路线图

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1–M4 | 认证、教学组织、选题、提交与成绩主链路 | ✅ |
| M5 | 语音答辩：AI 出题 + 录音转写 + AI 评分 + 在场检测 | ✅ |
| M5.1 | 实时流式语音（浏览器 PCM 流 → Java WS 代理 → AI 流式识别）、人脸身份识别/活体 | 📋 |
| M6 | 静态分析调度与问题明细落库 | 🚧 |
| M7 | 对象存储（MinIO）、集成测试（Testcontainers） | 📋 |

详细变更与计划见 [CHANGELOG.md](CHANGELOG.md)。

## 贡献

欢迎提交 Issue 与 Pull Request，动手前请先读 [CONTRIBUTING.md](CONTRIBUTING.md) 与
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。提交前请确保 `./gradlew build -x test`（在 `backend/` 下）
与 `npm run build`（在 `vue/` 下）均通过（CI 同样会检查）。

安全问题请按 [SECURITY.md](SECURITY.md) 私下报告，不要开公开 Issue。

## 许可证

[MIT](LICENSE)
