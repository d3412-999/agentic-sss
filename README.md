# 课程设计智能答辩与评分系统（agentic-sss）

面向《面向对象程序设计课程设计》这类课程的 AI 辅助教学系统，覆盖 **选题 → 提交 → 源码/报告评审 → 语音答辩 → 评分发布** 全流程。

Spring Boot 3 单体后端（59 个 REST 接口、22 张表、JWT 鉴权）+ Vue 3 前端 + 独立部署的 FastAPI AI 服务。

```
教师建课 / 出题 ──┐
                  ├──> 学生选题 ──> 提交源码(zip) + 报告(pdf) ──> 静态分析 + AI 语义评审
                  │                                                     │
                  │                                                     ▼
                  │                                  语音答辩（WebSocket + ASR）──> AI 评分
                  │                                                     │
                  └───────────── 成绩 = 答辩×w1 + 源码×w2 + 报告×w3 ──> 发布
```

## 功能

| 模块 | 能力 | 状态 |
|---|---|---|
| 认证 | 登录 / 刷新 / 登出 / 改密 / 当前用户，BCrypt + 无状态 JWT + 令牌版本号 | ✅ |
| 教学组织 | 课程模板、开课生命周期（草稿→发布→关闭→归档）、学生名单、教学仪表盘 | ✅ |
| 题目与选题 | 出题、批量发布/归档、学生浏览；选题用「原子 SQL + 唯一索引」防超选 | ✅ |
| 提交与文件 | 源码 zip / 报告上传（SHA-256 秒传、Zip Slip 与可执行文件校验）、版本历史、异步解析 | ✅ |
| AI 评审 | 异步调用 AI 服务做源码评审与报告评审，结果落库并参与算分 | ⚠️ 链路已通，AI 侧为 501 占位 |
| 成绩 | 三部分加权算分、人工调整、发布/撤销、异步导出 CSV、学生查询 | ✅ |
| 通知与任务 | 站内通知、异步任务进度轮询 | ✅ |
| 语音答辩 | WebSocket 语音流、ASR 转写、AI 出题与评分 | 🚧 表结构与客户端已就绪，业务未接入 |
| 静态分析 | Checkstyle / PMD / SpotBugs 调度 | 🚧 未实现 |

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + Vue Router（原生 `fetch` 封装） |
| 后端 | Spring Boot 3.3.5、Java 17、MyBatis-Plus 3.5.7、Flyway 10 |
| 安全 | Spring Security 6 + JJWT 0.12，`Authorization: Bearer <token>` |
| 存储 | MySQL 8（Flyway 自动建表）、Redis 7（限流 / 幂等 / 令牌）、本地磁盘（`./data/objects`） |
| AI 服务 | Python 3.12+、FastAPI、LangChain（规划中） |
| 构建 | Maven（根 `pom.xml` 为聚合器） |

## 目录结构

```
agentic-sss/
├── pom.xml                       # Maven 聚合器（IDE 打开本目录即可）
├── docker-compose.yml            # 本地 MySQL + Redis
├── .env.example                  # 环境变量样例
├── scripts/
│   ├── init-local-db.sql         # 用本机 MySQL 时建库建账号
│   └── start-redis.ps1           # Windows 下启动本机 Redis
├── spring boot_service/          # 后端
│   ├── pom.xml
│   ├── tools/gen_entities.py     # 从 DDL 反向生成 entity / mapper
│   └── src/main/
│       ├── java/org/spring_web/spring_boot_service/
│       │   ├── controller/ service/ mapper/ entity/ dto/
│       │   ├── config/ security/ common/ client/
│       │   └── SpringBootServiceApplication.java
│       └── resources/
│           ├── application.yaml
│           └── db/migration/     # V1 建表、V2 演示数据
├── langchain_service/            # AI 服务（FastAPI）
│   ├── main.py
│   └── app/{core,api}/
└── vue/                          # 前端
    ├── vite.config.js
    └── src/{api,router,stores,styles,views}/
```

## 快速开始

### 1. 环境要求

| 工具 | 版本 |
|---|---|
| JDK | 17 |
| Maven | 3.9+ |
| Node.js / npm | 18+ / 9+ |
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
cd "spring boot_service"
mvn spring-boot:run
```

首次启动 Flyway 会自动建 22 张表并写入演示数据。启动后：

| 地址 | 说明 |
|---|---|
| http://127.0.0.1:8081/api/v1/health | 健康检查（免鉴权） |
| http://127.0.0.1:8081/swagger-ui.html | Swagger UI（右上角 Authorize 填 accessToken） |
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
| MiscController | 5 | `/api/v1/notifications/*`、`/api/v1/tasks/*` |
| HealthController | 1 | `/api/v1/health` |

鉴权规则：`/teacher/**` 需 `TEACHER`，`/student/**` 需 `STUDENT`，健康检查、登录、刷新、Swagger、actuator 放行。参数细节以 Swagger 为准。

## 用 IDEA 运行

仓库已带 `.idea/runConfigurations/`（共享运行配置）与 UTF-8 编码设置：

1. `File → Open` 选择仓库根目录，等待 Maven 导入完成；
2. 右上角依次运行 `1. 后端 sss-backend (:8081)`、`2. 前端 vue (npm run dev :5173)`、`3. AI 服务 (FastAPI :8000)`；
3. 若 SDK 名称不一致，在 `Project Structure → Project SDK` 选择本机 JDK 17。

## 开发约定

- 统一响应包与错误码：`common/Result.java`、`common/ErrorCode.java`；
- 业务异常抛 `BizException`，由 `GlobalExceptionHandler` 转换；
- 主键为 BIGINT 雪花 ID（MyBatis-Plus `ASSIGN_ID`），不建物理外键，并发表带 `version` 乐观锁；
- 逻辑删除字段 `deleted`，**手写 SQL 必须自己加 `deleted = 0`**；
- 表结构只通过 `db/migration/V*.sql` 变更，已执行过的 V1/V2 不要修改；
- 实体与 Mapper 由 `tools/gen_entities.py` 从 DDL 生成，改表后请重新生成。

```bash
mvn -DskipTests package                                   # 构建后端（仓库根目录）
cd vue && npm run build                                   # 构建前端
python "spring boot_service/tools/gen_entities.py"        # 重新生成实体
```

## 已知限制

- AI 服务的 5 个 `/internal/ai/**` 接口返回 501 占位；语音答辩（WebSocket + ASR）、静态分析调度、对象存储（MinIO）尚未实现。
- 前端只覆盖登录、概览、开课与题目、通知四个页面，其余能力请通过 Swagger 调用。
- 演示数据与默认口令仅供本地使用。部署到生产前必须：删除或隔离 `V2__seed_demo_data.sql`、替换 `JWT_SECRET` 与 `AI_INTERNAL_TOKEN`、收紧 CORS 白名单、更换全部默认口令、限制 AI 服务网络访问。

## 贡献

欢迎提交 Issue 与 Pull Request。提交前请确保 `mvn -DskipTests package` 与 `npm run build` 均通过（CI 同样会检查）。

## 许可证

[MIT](LICENSE)
