# 课程设计智能答辩与评分系统

面向《面向对象程序设计课程设计（Java）》这类课程，覆盖 **选题 → 提交 → 源码/报告评审 → 语音答辩 → 评分发布** 全流程。

当前处于**骨架阶段**：数据库表结构、后端/AI 服务工程、基础设施编排已就绪，业务接口只保留健康检查与占位，具体功能按 [API接口文档.md](API接口文档.md) 逐步实现。

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + Vue Router（已有完整界面，见 `vue/`） |
| 后端 | Spring Boot 3.3 + Java 17 + MyBatis-Plus + Flyway + MySQL 8 + Redis |
| AI 服务 | Python + FastAPI + LangChain + LightRAG |
| 文件存储 | 本地磁盘（开发）/ MinIO / 对象存储（预留） |
| 基础设施 | Docker Compose（MySQL / Redis / MinIO） |

## 目录结构

```text
Agentic sss/
├── API接口文档.md              # 前后端 + AI 内部接口契约
├── docker-compose.yml          # MySQL / Redis / MinIO
├── .gitignore
├── vue/                        # 前端（已完成界面）
├── spring boot_service/        # 后端服务（Gradle 工程根目录）
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradlew / gradlew.bat   # Gradle Wrapper
│   ├── tools/gen_entities.py   # 由 DDL 生成实体与 Mapper
│   └── src/
│       ├── main/java/org/spring_web/spring_boot_service/
│       │   ├── SpringBootServiceApplication.java
│       │   ├── common/         # Result、错误码、异常、traceId
│       │   ├── config/         # MyBatis-Plus、Security、CORS、OpenAPI
│       │   ├── controller/     # HealthController（业务控制器待补）
│       │   ├── entity/         # 22 张表对应实体
│       │   ├── mapper/         # 22 个 BaseMapper
│       │   ├── service/        # 业务服务（空）
│       │   └── dto/            # 请求/响应 DTO（空）
│       ├── main/resources/
│       │   ├── application.yaml
│       │   ├── application-dev.yaml
│       │   └── db/migration/V1__init_schema.sql   # 建表脚本
│       └── test/               # H2 上下文测试
└── langchain_service/          # AI 能力服务
    ├── app/                    # FastAPI 应用
    ├── main.py
    ├── requirements.txt
    └── .env.example
```

## 快速开始

### 1. 基础服务

```bash
docker compose up -d
docker compose ps
```

| 服务 | 地址 | 账号 |
|---|---|---|
| MySQL | `127.0.0.1:3306/sss` | `sss / sss123456`（root：`root123456`） |
| Redis | `127.0.0.1:6379` | 无密码 |
| MinIO | `http://127.0.0.1:9001` | `minioadmin / minioadmin` |

### 2. 后端

用 IntelliJ IDEA 打开 `spring boot_service`（自带 Gradle），或使用自带的 Wrapper（需要本机有 JDK 17）：

```bash
cd "spring boot_service"
gradlew.bat bootRun     # macOS / Linux 用 ./gradlew bootRun
gradlew.bat build       # 构建 + 测试
```

改表后重新生成实体 / Mapper：

```bash
cd "spring boot_service"
python tools/gen_entities.py
```

启动后自动执行 Flyway，把 `db/migration/V1__init_schema.sql` 应用到 `sss` 库。

- 健康检查：<http://127.0.0.1:8080/api/v1/health>
- Swagger：<http://127.0.0.1:8080/swagger-ui.html>

### 3. AI 服务

```bash
cd langchain_service
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env
python main.py
```

- 健康检查：<http://127.0.0.1:8000/health>
- Swagger：<http://127.0.0.1:8000/docs>

### 4. 前端

```bash
cd vue
npm install
npm run dev
```

## 数据库设计

建表脚本：[V1__init_schema.sql](spring%20boot%20service/src/main/resources/db/migration/V1__init_schema.sql)（MySQL 8.0+ 语法；已在 MySQL 8.4 容器中执行，并由 Spring Boot 启动时的 Flyway 实际迁移通过，共 22 张表）。

| 分组 | 表 |
|---|---|
| 账号与课程 | `sys_user`、`course`、`offering`、`offering_student` |
| 题目与选题 | `topic`、`topic_attachment`、`selection` |
| 提交与文件 | `submission`、`submission_version`、`file_object` |
| 分析与评审 | `code_analysis`、`code_issue`、`report_evaluation` |
| 答辩 | `defense_session`、`defense_question`、`defense_answer`、`score` |
| 成绩 | `grade`、`grade_item` |
| 支撑 | `async_task`、`notification`、`audit_log` |

**约定**

- 主键 `BIGINT`，由应用侧雪花算法生成（MyBatis-Plus `ASSIGN_ID`），不使用自增；
- 业务表统一 `created_at` / `updated_at`，可删除的表用 `deleted` 逻辑删除；
- 需要并发的表带 `version` 乐观锁（注意：`submission_version.version` 是业务版本号，不是锁）；
- JSON 字段存数组/对象快照（标签、评分要点、度量指标等）；
- 不建物理外键，关系由应用层维护；所有表 `utf8mb4_0900_ai_ci`。

## 代码约定

- 统一响应：`{ code, message, data, traceId }`，错误码见 `common/ErrorCode.java`；
- 每个请求由 `TraceIdFilter` 生成 `traceId`，同时写入响应头 `X-Trace-Id`；
- 业务异常抛 `BizException`，由 `GlobalExceptionHandler` 统一转换；
- 分页参数 `page`（从 1 开始）、`size`（最大 100），返回 `PageResult`；
- 安全骨架：Spring Security 已引入，当前 `permitAll()`，接入 JWT 后收紧；
- 实体与 Mapper 由建表脚本解析生成，改表后请同步重新生成。

## 已完成 / 待办

**已完成**

- 22 张表的 DDL、索引与注释，MySQL 8.4 容器实跑 + Flyway 迁移验证
- Spring Boot 工程骨架：构建文件、配置、统一响应/异常、traceId、MyBatis-Plus 插件、Swagger、健康检查
- 22 个实体与 Mapper
- FastAPI 骨架：健康检查、内部令牌校验、5 个 501 占位接口
- Docker Compose、目录说明、忽略规则

**待办**

- [ ] 认证模块（登录、刷新令牌、角色鉴权）
- [ ] 课程 / 开课 / 学生名单 / 题目 / 选题接口
- [ ] 文件上传、解析与对象存储适配
- [ ] 源码静态分析调度（Checkstyle / PMD / SpotBugs）
- [ ] 报告评审、答辩会话、语音链路（WebSocket + ASR）
- [ ] 评分、发布、导出
- [ ] 前端对接真实接口（当前为 mock 数据）
