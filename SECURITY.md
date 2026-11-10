# 安全策略

## 支持的版本

本项目仍在早期迭代（`0.x`），只对 `main` 分支的最新提交提供安全修复。

| 版本 | 是否维护 |
|---|---|
| `main` 最新提交 | ✅ |
| 历史提交 / 标签 | ❌ |

## 报告漏洞

**请不要通过公开 Issue 报告安全问题。** 请使用 GitHub 仓库的
[私密漏洞报告](https://docs.github.com/zh/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)（Security → Advisories → Report a vulnerability）提交，或在仓库主页找到维护者联系方式私下沟通。

报告里请尽量包含：影响范围（后端 / 前端 / AI 服务）、复现步骤、影响版本或提交号、可能的修复方向。
我们会在 7 天内确认收到，并在修复发布后于 Release Notes 中致谢（除非你希望匿名）。

## 默认配置不是生产配置

仓库里的口令、密钥全部是**本地开发用的占位值**，写在 `.env.example` 与 `application.yaml` 的默认值里：

- `JWT_SECRET=change-me-...`、`AI_INTERNAL_TOKEN=change-me`、数据库与 Redis 口令均为弱口令；
- `V2__seed_demo_data.sql` 会写入 6 个演示账号（密码统一 `123456`）；
- AI 服务的 `/internal/ai/**` 只靠共享令牌鉴权，且默认与后端跑在同一台机器的 localhost 上。

公开部署前必须：

1. 删除或隔离演示数据迁移，替换全部默认口令与令牌；
2. 收紧 `sss.cors.allowed-origins`，关闭 Swagger 的匿名访问；
3. 让 AI 服务只监听内网，或加网关鉴权；
4. 为上传目录 `data/objects` 配置访问控制与容量上限。

## 本项目已处理的攻击面

如需修改相关代码，请保持这些防护不被回退：

- 密码只存 BCrypt 哈希；JWT 用无状态令牌 + `tokenVersion` 支持即时失效；
- 登录失败按用户名（5 次/分钟）与 IP（20 次/分钟）双维度限流，依赖 Redis；
- 上传使用 SHA-256 秒传并做 Zip Slip 路径校验；
- 统一异常处理不向客户端泄露堆栈，错误信息带 `traceId` 便于关联日志；
- 手写 SQL 强制带 `deleted = 0`，避免逻辑删除数据泄露。
