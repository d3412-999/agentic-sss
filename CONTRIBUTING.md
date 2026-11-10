# 贡献指南

感谢你愿意改进这个项目。本项目是课程设计场景下的全栈示例（Spring Boot 3 + Vue 3 + FastAPI），
代码量不大，但约定比较具体，动手前请先读一遍本文与 [`README.md`](README.md) 的「开发约定」一节。

## 目录约定

| 目录 | 内容 | 构建命令 |
|---|---|---|
| `backend/` | Spring Boot 3 后端（Gradle 工程，含 Flyway 迁移） | `./gradlew build -x test` |
| `vue/` | Vue 3 + Vite 前端 | `npm ci && npm run build` |
| `langchain_service/` | FastAPI AI 服务 | `pip install -r requirements.txt` |
| `docs/` | 需求文档与截图 | — |
| `scripts/` | 本地初始化脚本（建库、启 Redis） | — |

## 提交前自检

```bash
cd backend && ./gradlew build -x test     # Windows: gradlew.bat build -x test
cd vue && npm ci && npm run build
```

CI（`.github/workflows/ci.yml`）会跑同样的三条命令，红了的 PR 不会合并。

## 分支与提交信息

- 分支：`feat/<简短描述>`、`fix/<简短描述>`、`docs/<简短描述>`；
- 提交信息用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：
  `feat(grade): 支持按分项撤回`, `fix(auth): 修复刷新令牌并发失效`；
- 一次提交只做一件事，不要把格式化改动和功能改动混在一起；
- 界面改动请附截图，接口改动请说明影响的前端调用点。

## 代码约定

### 后端

- 统一响应体 `common/Result.java`、错误码 `common/ErrorCode.java`，业务异常抛 `BizException`；
- 主键为雪花 ID（MyBatis-Plus `ASSIGN_ID`），不建物理外键，并发表带 `version` 乐观锁；
- 逻辑删除字段 `deleted`：**手写 SQL 必须自己加 `deleted = 0`**；
- 表结构只能通过 `backend/src/main/resources/db/migration/V*.sql` 变更；已执行过的
  `V1` / `V2` 不要修改，新增变更请加 `V3__xxx.sql`；
- 实体与 Mapper 由 `backend/tools/gen_entities.py` 从 DDL 反向生成，改表后重新生成；
- 新增接口必须写进对应的 Controller 与 DTO，并确认 Swagger 能正常渲染。

### 前端

- 不引入 UI 框架与图标库：组件放 `vue/src/components/`，样式统一在 `vue/src/styles/main.css`；
- 网络请求走 `vue/src/api/http.js` 封装，接口常量集中登记在 `vue/src/api/endpoints.js`；
- 新增页面复用既有 class 与设计变量，保证浅色/深色双主题与 420px 窄屏可用。

### AI 服务

- 内部接口统一 `/internal/ai/**`，通过 `X-Internal-Token` 校验调用方；
- 保持「不启动也能跑通主链路」的降级约定：后端遇到 AI 不可用统一返回 `50301`。

## 安全红线

- **不要提交任何真实密钥**：`.env` 已被 `.gitignore` 忽略，需要新增配置项时改 `.env.example`；
- 不要把数据库导出、用户数据、上传文件（`data/`）带进提交；
- 发现安全问题请按 [`SECURITY.md`](SECURITY.md) 私下报告，不要开公开 Issue。
