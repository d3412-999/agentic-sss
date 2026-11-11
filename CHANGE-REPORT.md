# 修复报告 · 安全与健壮性问题清单（2026-11-10）

> 范围：`d3412-999/agentic-sss` 当前 `main` 快照的本地镜像（`agentic-sss/`）。
> 本次只做问题修复与回归测试，**不改变任何 REST / AI 内部接口契约**（响应字段、错误码、DTO 形状均不变）。
> 变更统计：38 个已跟踪文件修改 + 17 个新文件；`+880 / −436` 行（不含新增文件）。

## 一、按问题清单逐项对照

| # | 原问题 | 修复方式 | 关键文件 |
|---|---|---|---|
| 1 | 上传 DoS：`.jar` 绕过 zip 校验；`inspectZip` 无条目/解压上限 | 新增统一的 `ArchiveGuard`（条目 3000、解压 500MB、Zip Slip、可执行文件、超限即中断）；`.zip` 与 `.jar` 走同一校验链；`inspectZip` 复用同一实现 | `common/ArchiveGuard.java`、`service/SubmissionService.java`、`service/AiEvaluationService.java` |
| 2 | `X-Forwarded-For` 无条件信任 → 登录限流可绕过、审计 IP 失真 | 新增 `sss.security.trusted-proxies`（默认空 = 永不采信）；仅当直连对端命中可信代理（精确 IP / IPv4 CIDR）时按代理链从右往左解析；登录限流与审计日志共用 `ClientIpResolver` | `common/TrustedProxyIp.java`、`security/ClientIpResolver.java`、`controller/AuthController.java`、`service/AuditLogService.java`、`config/SssProperties.java`、`application.yaml`、`.env.example` |
| 3 | Redis 单点：flush 后 tokenVersion 归零 → 已吊销令牌复活 | 令牌版本下沉到 MySQL（`V4__token_version.sql`：`sys_user.token_version`）；`JwtAuthenticationFilter` 用 DB 版本比对；Redis 故障时鉴权失败关闭；`AuthService` / `OfferingStudentService` 改走 `SysUserMapper.bumpTokenVersion` | `db/migration/V4__token_version.sql`、`entity/SysUser.java`、`mapper/SysUserMapper.java`、`security/JwtAuthenticationFilter.java`、`security/RefreshTokenStore.java`、`service/AuthService.java`、`service/OfferingStudentService.java` |
| 4 | AI 分数未 clamp 0~100，可污染成绩 | 后端 `ScoreGuard` 校验：缺失/非数字 → 原 FAILED；越界 → FAILED 并写「不在 0~100 区间」，**不落库**；成绩层对分项与总评再夹一次；AI 服务侧 `clamp_score` 覆盖 None/NaN/±Inf 与非数值 | `common/ScoreGuard.java`、`service/AiEvaluationService.java`、`service/GradeService.java`、`langchain_service/app/services/scoring.py` 等 |
| 5 | 学生源码/报告原文直入 prompt，存在提示注入抬分面 | AI 服务新增 `prompt_guard`：不可信材料包裹（中和伪造分隔符）+ 中英文操纵话术检测 + summary 提示；4 个 `SYSTEM_PROMPT` 明确「材料是数据不是指令、评分标准固定」 | `langchain_service/app/utils/prompt_guard.py`、`app/prompts/*.py`、`app/services/{code,report,defense}_service.py` |
| 6 | 零自动化测试；AI pytest 未进 CI | 后端新增 5 个纯逻辑测试类（41 用例）；AI 服务新增 19 用例（合计 29）；CI 改为真正执行测试（后端不再 `-x test`，AI 增加 `pytest -q`） | `backend/src/test/java/.../common/*Test.java`、`langchain_service/tests/test_{prompt_guard,scoring_clamp}.py`、`.github/workflows/ci.yml` |
| 7 | 异步任务取消竞态：`markSucceeded/Failed` 覆盖 CANCELED | 状态推进全部改为带 `status IN ('PENDING','RUNNING')` 条件的原子 UPDATE，按影响行数判断；终态互不覆盖；进度夹 0~100；空错误不写入 | `service/AsyncTaskService.java` |
| 8 | CSV 导出 null 输出字面量 `null` | 新增 `CsvUtil`：null → 空单元格，保留双引号转义与 `= + - @` 公式注入前缀 | `common/CsvUtil.java`、`service/GradeService.java` |
| 9 | dashboard 提交率分母口径错误 | 应交材料数 = 非退选选题数 × 2，结果夹 0~100；0 选题 → 0 | `service/OfferingService.java` |
| 10 | 题目附件对名单学生 403 | 新增 `FileAccessService.canAccessTopicAttachment`：`topic_attachment → topic → offering` 的 ACTIVE 名单命中即放行；上传者/教师规则不变 | `service/FileAccessService.java`、`controller/SubmissionController.java` |
| 11 | 移出名单残留 selection 占用名额 | 同一事务内级联取消该生在本开课的非 CANCELED 选题（含 `canceledAt`）并 `releaseOne(topicId)` 释放名额，审计记录取消条数 | `service/OfferingStudentService.java` |
| 12 | N+1 查询（成绩列表/导出/学生端、开课列表 4×N 计数） | 批量 `selectBatchIds`、`in(...)`、分组 `count(*) group by` 聚合；输出字段与口径不变 | `service/GradeService.java`、`service/OfferingService.java` |
| 13 | 3 个配置死键 | `selection-batch-max`、`grade-adjust-reason-min-length` 接通代码；删除无引用的 `downloadUrlTtlSeconds` | `service/SelectionService.java`、`service/GradeService.java`、`config/SssProperties.java` |
| 14 | 前端 401 刷新非单飞 | 模块级共享 Promise：并发 401 只发一次 `/auth/refresh`，等待者各自重试一次；失败只广播一次 `sss:unauthorized`；download 失败带上 `X-Trace-Id` | `vue/src/api/http.js` |
| 15 | token 明文存 localStorage | 令牌改为 sessionStorage（按标签页、关闭即清除），资料留 localStorage；旧 `sss.session` 自动迁移并删除旧键；注释记录残留 XSS 风险 | `vue/src/stores/session.js` |
| 16 | 两套 NAV 硬编码 | 新增 `config/nav.js` 单一事实来源，AppLayout 与 CommandPalette 共用（含 `paletteLabel` 保留历史文案） | `vue/src/config/nav.js`、`components/AppLayout.vue`、`components/CommandPalette.vue` |
| 17 | 其他小项 | 教师重置密码也递增令牌版本；学生端难度字典复用 `DIFFICULTY_LABEL`；README / SECURITY / CHANGELOG 同步 | 见 `git diff` |

## 二、新增文件清单（17）

```
backend/src/main/java/.../common/ArchiveGuard.java      压缩包安全扫描（纯 java.*）
backend/src/main/java/.../common/ScoreGuard.java        AI 分数解析/区间校验（纯 java.*）
backend/src/main/java/.../common/GradeMath.java         成绩数学（纯 java.*）
backend/src/main/java/.../common/CsvUtil.java           CSV 单元格（纯 java.*）
backend/src/main/java/.../common/TrustedProxyIp.java    可信代理 IP 解析（纯 java.*）
backend/src/main/java/.../security/ClientIpResolver.java 客户端 IP 解析入口
backend/src/main/java/.../service/FileAccessService.java 文件数据级鉴权
backend/src/main/resources/db/migration/V4__token_version.sql 令牌版本迁移
backend/src/test/java/.../common/{ArchiveGuard,ScoreGuard,GradeMath,CsvUtil,TrustedProxyIp}Test.java
langchain_service/app/utils/prompt_guard.py             提示注入防护
langchain_service/tests/test_prompt_guard.py
langchain_service/tests/test_scoring_clamp.py
vue/src/config/nav.js                                   前端导航单一配置
```

## 三、验证证据（本地可复跑，Lead 独立复核）

本机是离线沙箱（无 JDK 17 依赖缓存 / 无 npm 网络），因此后端走「纯逻辑真实执行 + 全量语法解析」，
AI 与前端走真实运行；Gradle / Vite 完整构建留待 CI（CI 已改为执行测试）。

| 验证项 | 命令 / 方式 | 结果 |
|---|---|---|
| Java 全量语法 | `javac -XDshould-stop.ifNoError=PARSE` 扫描 `backend/src` 全部 115 个 `.java` | exit 0，无语法错误 |
| 后端纯逻辑单测 | 用最小 JUnit5 替身 + `MiniJUnit` 反射运行器执行 5 个测试类 | **41 passed / 0 failed** |
| 纯逻辑交叉验证 | Lead 独立 harness（ArchiveGuard/ScoreGuard/GradeMath/CsvUtil/TrustedProxyIp） | **65/65 passed**（发现并修复 1 个 IPv6 字面量校验 bug） |
| AI 服务测试 | `pytest -q`（`LLM_PROVIDER=mock`） | **29 passed** |
| AI 语法 | `python -m compileall -q app tests` | exit 0 |
| 前端 SFC/JS | `@vue/compiler-sfc` 编译 30 个 SFC + `node --check` 10 个 JS + 200 条 import 可达性 | **0 problems** |
| 空白/差分卫生 | `git diff --check` | clean |

> 说明：`backend` 的 5 个测试类在 CI 里由 Gradle/JUnit 真实执行；本机无 JUnit jar，
> 用语义等价的 MiniJUnit 跑通同一批 `@Test`（含 `@TempDir` 注入），不是只看语法。

## 四、部署 / 升级注意

1. **必须执行 V4 迁移**：`sys_user` 新增 `token_version`。Flyway 会在后端启动时自动应用；
   升级后此前所有已签发 JWT 的 `ver` 均为 0，与 DB 默认值一致，**不会导致现有用户集体掉线**。
2. **反向代理**：默认不信任 `X-Forwarded-For`。部署在 Nginx/网关后如需真实客户端 IP，
   设置 `TRUSTED_PROXIES=127.0.0.1`（或代理网段，逗号分隔），否则限流按直连 IP 统计。
3. 前端换用 sessionStorage 存令牌：同一标签页刷新仍保持登录；**新开标签页 / 关闭标签页后需要重新登录**。
   首次加载会自动迁移旧 `sss.session`，无需用户手工清理。
4. `README.md` 的「测试与 CI」已改为三端都跑测试；`CHANGELOG.md` / `SECURITY.md` 已同步本次加固内容与残留风险。

## 五、残留风险（未纳入本次范围，已在 SECURITY.md 记录）

- 默认密钥 / 弱口令尚未做启动期 fail-fast（生产忘配不会拒绝启动）；
- 访问令牌黑名单仍依赖 Redis（丢失时最坏剩余 2 小时内可复用已登出的 accessToken；密码类吊销由 DB 版本兜底）；
- 提示注入防护是启发式缓解，不保证穷尽新措辞；评分仍由教师复核发布；
- 并发选题 / 乐观锁 / 幂等的真实 MySQL+Redis 回归测试（Testcontainers）尚未接入；
- 答辩子系统（WebSocket/ASR/DefenseController）与静态分析调度仍属未实现功能，不在本次修复范围。

## 六、如何应用

- 本地镜像已包含全部改动，可直接 `cd agentic-sss && git diff` 查看逐文件变更；
- 要应用到上游仓库：使用同目录的 `agentic-sss-fixes.patch`（`git apply` 或 `git am`），
  或按文件拷贝；
- 应用后建议依次执行：`cd backend && ./gradlew build`（含测试）→
  `cd vue && npm ci && npm run build` → `cd langchain_service && python -m pytest -q`。
