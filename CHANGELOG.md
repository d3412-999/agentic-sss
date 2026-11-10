# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)；格式参考
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 计划中

- 语音答辩：WebSocket 语音流 + ASR 转写 + AI 出题与评分；
- 静态分析：Checkstyle / PMD / SpotBugs 调度与问题明细落库；
- 报告正文抽取（PDFBox 或 Python 侧解析），让报告评分不再是盲评；
- 对象存储（MinIO）替换本地磁盘 `data/objects`；
- 后端单元测试与集成测试接入 CI。

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

[Unreleased]: https://github.com/OWNER/REPO/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/OWNER/REPO/releases/tag/v0.1.0
