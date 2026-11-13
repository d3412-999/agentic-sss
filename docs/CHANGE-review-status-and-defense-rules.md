# 报告页卡死修复 · 答辩批量发起 · 三原则题目体系（V13）

> 本文覆盖三件事：① 报告/源码评审「页面一直转圈」的根因与状态驱动改造；② 教师端批量发起答辩；
> ③ 题数分档规则 + 三原则维度判定 + 加分题。
> 迁移脚本：`backend/src/main/resources/db/migration/V13__review_status_and_defense_rule.sql`（V12 → V13，已在本地库执行）。

---

## 1. 技术实现方案

### 1.1 报告页加载卡住的根因

页面卡住的表象是「一直 loading」，实际是四层问题叠加：

| # | 根因 | 证据 | 修复 |
|---|---|---|---|
| 1 | **源码与报告是两个独立异步任务**，前端只按「提交物状态」猜测进度。源码先完成 → `submission.status` 变 `ANALYZED` → 前端停止轮询，报告还在跑或已失败 | `async_task` 里 SOURCE/REPORT 两条互不相关；`UploadResultVO` 返回 202 后前端只能反复拉详情 | 新增 `GET /submissions/{id}/review-status`，两侧各自状态 + `finished` 统一收尾 |
| 2 | **失败被写成了「0 分」**，与「没评出来」无法区分，UI 显示 0 分却不报错 | 旧 `AiEvaluationService.fail()` 里 `setScore(0)` | `fail()` 改为 `setScore(null)` + `setErrorMessage(...)`；成功时清空 error |
| 3 | **任务可能永远停在 RUNNING**（进程重启、OOM、网络中断），没有任何兜底 | 任务表无超时收敛逻辑 | 新增 `ReviewWatchdog`（`@Scheduled`，默认 120s 扫一次，超过 15 分钟的 PENDING/RUNNING → FAILED + `error_message`，可重试） |
| 4 | **报告任务可能漏入队**：源码评审成功后只更新自己的业务表，没有确保报告任务存在 | 历史上存在 REPORT 侧 `report_evaluation` 长期缺记录、`async_task` 也无任务的组合 | `AiEvaluationService.enqueueReportIfNeeded()` 三条件补偿（报告版本存在 && 业务记录缺失或 PENDING && 无 PENDING/RUNNING 任务），包 try/catch 不拖垮已成功的源码评审 |

前端侧另有三处自身缺陷一并修掉：

- `loading` 在异常分支不复位 → 改为**所有分支都在 `finally` 收尾**；
- 旧响应覆盖新响应 → `seq` 请求序号，`mine !== seq` 直接丢弃；
- 组件卸载后仍在请求 → `AbortController` + `onBeforeUnmount`，`request()` 把 `AbortError` 识别为 `code=-2`（取消），不再误报「后端没启动」。

### 1.2 状态机变化

**评审状态（新，只读视图，不改原有流转）**

```
业务表为准（code_analysis / report_evaluation）
  ── 无记录 → 按 submission.status 映射
       ANALYZING / UPLOADED / PARSING → PENDING
       INVALID                        → FAILED
       其它                           → MISSING
  ── 有记录 → SUCCEEDED / FAILED / PENDING / RUNNING（原样透出）

overall（聚合，前端只认 finished 决定是否停轮询）
  两侧都缺            → NONE
  任一侧 PENDING/RUNNING → RUNNING   finished=false
  两侧都 SUCCEEDED     → DONE       finished=true
  部分成功部分失败      → PARTIAL    finished=true
  两侧都失败           → FAILED     finished=true
```

**答辩会话状态机不变**：`CREATED → READY → IN_PROGRESS → COMPLETED → EVALUATED`（另有 `VIOLATED` / `TERMINATED` 旁支）。
新增的 `SKIPPED` 只是**作答记录**的状态，不动会话状态机。

**批量任务状态机（新）**：`RUNNING → DONE | PARTIAL | FAILED`
（`failed==0` → DONE；`succeeded==0 && failed>0` → FAILED；其余 → PARTIAL。全部 SKIP 也算 DONE。）

### 1.3 数据模型（V13）

```sql
-- 评审侧：失败原因与重试次数可落库，看门狗与 UI 共用
ALTER TABLE code_analysis     ADD COLUMN error_message VARCHAR(1000), ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE report_evaluation ADD COLUMN error_message VARCHAR(1000), ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
CREATE INDEX idx_code_analysis_status_updated     ON code_analysis(status, updated_at);
CREATE INDEX idx_report_evaluation_status_updated ON report_evaluation(status, updated_at);

-- 答辩会话：题数快照 + 判定结果 + 覆盖痕迹 + 加分题结果
ALTER TABLE defense_session
  ADD COLUMN code_score_snapshot      DECIMAL(5,1),   -- 出题时的源码分，事后重评不追溯
  ADD COLUMN question_rule_tier       VARCHAR(8),     -- T0~T4
  ADD COLUMN difficulty_mix           VARCHAR(32),    -- 'EASY:3,MEDIUM:3,HARD:2'
  ADD COLUMN question_override_reason VARCHAR(500),   -- 教师手改题数的理由
  ADD COLUMN bonus_question_count     TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN ai_dependent             TINYINT,        -- NULL=未判定 / 0 / 1
  ADD COLUMN principle_result         VARCHAR(500),   -- JSON 数组，逐维度结论
  ADD COLUMN ai_dependent_overridden  TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN override_reason          VARCHAR(500),
  ADD COLUMN override_by              BIGINT,
  ADD COLUMN override_at              DATETIME(3),
  ADD COLUMN bonus_result             VARCHAR(16),    -- CORRECT / WRONG / UNANSWERED
  ADD COLUMN bonus_score              DECIMAL(5,1),
  ADD COLUMN bonus_comment            VARCHAR(500);

-- 题目：维度标签 + 加分标记
ALTER TABLE defense_question ADD COLUMN dimension VARCHAR(16), ADD COLUMN is_bonus TINYINT NOT NULL DEFAULT 0;

-- 作答：逐题分数、通过与否、证据、评语、跳过
ALTER TABLE defense_answer
  ADD COLUMN score DECIMAL(5,1), ADD COLUMN passed TINYINT,
  ADD COLUMN evidence VARCHAR(1000), ADD COLUMN comment VARCHAR(500),
  ADD COLUMN skipped TINYINT NOT NULL DEFAULT 0;

-- 批量任务
CREATE TABLE defense_batch_task (
  id BIGINT PRIMARY KEY, offering_id BIGINT NOT NULL, operator_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  total INT NOT NULL DEFAULT 0, succeeded INT NOT NULL DEFAULT 0,
  skipped INT NOT NULL DEFAULT 0, failed INT NOT NULL DEFAULT 0,
  config JSON, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), finished_at DATETIME(3)
);
CREATE TABLE defense_batch_item (
  id BIGINT PRIMARY KEY, batch_id BIGINT NOT NULL, selection_id BIGINT NOT NULL, student_id BIGINT,
  status VARCHAR(16) NOT NULL, session_id BIGINT, question_count TINYINT, reason VARCHAR(500)
);
```

全部为**增量 ADD COLUMN / CREATE TABLE**，无 DROP、无数据改写；历史行新列取 DEFAULT（`ai_dependent` 为 NULL 表示「未判定」，与「未判定为依赖」区分开）。

### 1.4 接口契约

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/submissions/{id}/review-status` | 本人或本开课教师 | 源码 + 报告两侧状态、分数、错误、更新时间，`finished` 决定是否停轮询 |
| POST | `/api/v1/teacher/defenses/batch` | TEACHER（本人开课） | 批量发起，立即返回 `batchId`，后台执行 |
| GET | `/api/v1/teacher/defenses/batch/{batchId}` | TEACHER（本人开课） | 逐学生结果 + 计数，供轮询 |
| GET | `/api/v1/teacher/defense/question-rule` | TEACHER | 题数分档、难度配比、阈值、维度、批量上限的**唯一来源** |
| POST | `/api/v1/teacher/defense/sessions/{sessionId}/ai-dependent-override` | TEACHER（本人开课） | 覆盖判定，`reason` ≥5 字，仅留痕 |
| POST | `/api/v1/defense/sessions/{sessionId}/questions/{questionId}/skip` | STUDENT（本人） | 仅加分题、仅 IN_PROGRESS |

`review-status` 响应示例（实测）：

```json
{"code":0,"data":{
  "submissionId":"2121170974002397186","selectionId":"2121170868322713602",
  "overall":"DONE","finished":true,
  "source":{"submissionId":"2121170964728791041","type":"SOURCE","status":"SUCCEEDED",
            "score":80.6,"grade":null,"error":null,"retryable":false,
            "updatedAt":"2026-11-13T17:19:03.122+08:00","taskId":null},
  "report":{"submissionId":"2121170974002397186","type":"REPORT","status":"SUCCEEDED",
            "score":79.0,"error":null,"retryable":false,
            "updatedAt":"2026-11-13T17:19:09.762+08:00","taskId":null},
  "updatedAt":"2026-11-13T17:19:09.762+08:00"}}
```

`batch` 响应（实测，含跳过）：

```json
{"code":0,"data":{"batchId":"2121184864734359554","status":"DONE","total":2,
  "succeeded":0,"skipped":2,"failed":0,
  "items":[{"selectionId":"2121170868322713602","studentNo":"12503070203","studentName":"gbt",
            "status":"SKIPPED","sessionId":null,"questionCount":null,"reason":"已有进行中的答辩"}]}}
```

### 1.5 时序（文字版）

**A. 评审状态轮询**

```
学生/教师            Vue                    后端                     AI
   |  打开选题页      |                       |                       |
   |---------------->| GET review-status     |                       |
   |                 |---------------------->| 读 code_analysis      |
   |                 |                       | 读 report_evaluation  |
   |                 |<----------------------| overall=RUNNING       |
   |                 | 2s 后重试              |                       |
   |                 |----------- 4s ---------|                       |
   |                 |----------- 8s ---------|                       |
   |                 |----------- 10s(封顶) ---|                       |
   |  切回标签页      | visibilitychange → 立即拉一次（退避归零）      |
   |                 |                       | 看门狗 120s 扫一次     |
   |                 |                       | 超 15min → FAILED      |
   |                 |<----------------------| overall=DONE          |
   |                 | finished=true → 停表 + 触发页面刷新            |
```

**B. 批量发起**

```
教师        前端                        后端                        AI
 |  勾选 N 人 |                            |                          |
 |----------->| 确认弹窗（人数/题数分布）    |                          |
 |  确认      |---POST /defenses/batch---->| 建 batch(RUNNING)        |
 |            |<---202 batchId------------| 抓 Authentication        |
 |            |                            | executor.execute → run() |
 |            |                            |  precheck 分拣 SKIPPED   |
 |            |                            |  并发 ≤3 扇出 runOne()   |
 |            |                            |   每个工作线程 setAuth   |
 |            |                            |   createSession ────────>| 出题
 |            |                            |   record() 原子累加      |
 |            |---GET batch/{id} (退避)---->|                          |
 |            |<---RUNNING/items 实时增长----| 收尾：只 UPDATE 状态     |
 |            |<---DONE + 汇总--------------|                          |
 |  失败项重试 |---再次 POST（仅失败项）---->|                          |
```

### 1.6 配置项清单（全部可配，代码里没有魔法数）

```yaml
sss:
  ai:
    review:
      stale-minutes: 15              # 看门狗判定「卡住」的阈值
      watchdog-enabled: true
      watchdog-interval-seconds: 120
  defense:
    question-rule:
      base-count: 6                  # 基础题数
      max-count: 10                  # 计分题上限（加分题不占）
      bonus-count: 1
      bonus-max-answer-seconds: 120
      bonus-skippable: true
      base-mix: [40, 40, 20]         # 基础/中档难度配比
      high-tier-mix: [30, 40, 30]    # 高分档配比
      high-tier-threshold: 3         # 从 T3 起换配比
      tiers:                         # 题数分档
        - {code: T0, min: 0,  max: 59,  add: 0}
        - {code: T1, min: 60, max: 74,  add: 1}
        - {code: T2, min: 75, max: 84,  add: 2}
        - {code: T3, min: 85, max: 94,  add: 3}
        - {code: T4, min: 95, max: 100, add: 4}
    principle:
      threshold: 60                  # 单维度平均分通过线
      fail-count-for-ai-dependent: 2 # 两项及以上不通过 → 完全依赖 AI
      override-reason-min-length: 5
    batch:
      max-students: 50
      concurrency: 2                 # 钳制在 1~3
      allow-when-grade-published: false
```

---

## 2. 功能交互逻辑

### 2.1 报告/材料评审状态（学生端 & 教师端）

- 进入选题详情先拉一次 `review-status`；`finished=false` 时按 **2s → 4s → 8s → 10s** 指数退避，封顶 10s。
- 只有 `finished=true` 才停表：**不再用「源码评完就收工」的错误信号**（这正是原来卡住的直接原因）。
- 切到别的标签页再切回来 → `visibilitychange` 立即补拉并把退避归零；断网恢复后也会自动补齐（错误不终止轮询，只是延后下一次）。
- 超过 30s 未完成 → 展示「仍在评审中，大模型处理较慢时通常 1~3 分钟」+「立即刷新」按钮。
- 某一侧 `FAILED` → 单独展示原因与「重新评审」，**另一侧已完成的分数保持可见**，说明「重试不会影响已完成的那一侧」。
- 源码与报告各有独立 loading / error 状态，互不阻塞。

### 2.2 批量发起答辩（教师端）

```
多选（单选 / 全选当前筛选结果）
  → 底部固定操作条：已选 N 人 · 预计 M 道计分题（另含加分题，不计入总分）
  → 确认弹窗：人数 / 题数分布表（源码评分 · 档位 · 题数 · 人数）/ 并发说明
  → 执行进度（轮询 batch/{id}，2s 起退避到 10s，页面隐藏时跳过）
  → 结果汇总：成功 / 跳过 / 失败 + 逐学生原因
  → 失败项「仅重试失败」：只重提失败的那几个，已成功的不重复创建
```

资格校验（列表预检与创建时校验同源，两次请求之间被别人创建也不会重复）：

| 情况 | 结果 |
|---|---|
| 已有 `CREATED/READY/IN_PROGRESS` 会话 | 跳过，「已有进行中的答辩」（幂等） |
| 未提交源码 | 不可发起，「未提交源码」 |
| 源码评审未开始 / 进行中 | 不可发起，「源码评审尚未开始 / 进行中」 |
| 源码评审失败 | 不可发起，「源码评审失败，请先重新评审」 |
| 已退选 | 不可发起，「已退选」 |
| 成绩已发布（`published_at` 非空） | 默认不可发起，「成绩已发布，需先撤回成绩」（`allow-when-grade-published=true` 可放开） |

不可发起的行 checkbox 置灰 + `title` 与行内文字给出原因；单批上限来自 `/question-rule`（默认 50），超出即时提示并截断。

### 2.3 三原则 + 加分题（学生端 / 教师端）

- **学生端**：只看题目本身与自己的作答。题目卡不出现「第几原则」「P1/P2/P3」等字样；加分题只标「不计入总分」，可以「跳过本题」；评分后只看到「拓展题：答对/答错/未作答 · 简评」。
- **教师端**：
  - 题目卡显示维度标签（复述设计 / 解释修改理由 / 现场改动）；
  - 评分卡顶部「是否完全依赖 AI」结论 + 依据（`两项及以上原则未通过`），逐维度列出平均分、通过与否、证据；
  - 可「覆盖判定」：弹窗里必须选结论 + 填理由（≥5 字），保存后标记「教师已覆盖」并显示理由；**不改 AI 给出的总分与逐题分数**，原始 `principle_result` 原样保留；
  - 加分题独立成卡：答对 / 答错 / 未作答 + 学生答案 + AI 简评。

---

## 3. 规则设计细节

### 3.1 题数分档（替代原「代码评分 / 10 + 2」）

| 源码评分 | 档位 | 计分题数 | 加分题 |
|---|---|---|---|
| 0 – 59 | T0 | 6 | 1 |
| 60 – 74 | T1 | 7 | 1 |
| 75 – 84 | T2 | 8 | 1 |
| 85 – 94 | T3 | 9 | 1 |
| 95 – 100 | T4 | 10 | 1 |

- 同档同量：分数只决定档位，不参与题数插值。
- 越界夹取：`180 → T4(10)`、`-40 → T0(6)`；源码未评 / 失败 → 按 T0。
- 上限 `max-count=10` 覆盖档位结果；加分题**不占**这个上限。
- 历史不追溯：`question_count` 是落库快照，老会话保持原题数（实测旧会话仍是 5 题、`bonus_question_count=0`）。
- 教师手改题数：`questionCount` 与档位结果不同即视为覆盖，必须 `overrideReason`（≥1 字），写入 `question_override_reason`。

### 3.2 难度配比

基础 `40% / 40% / 20%`（易 / 中 / 难），T3 起换 `30% / 40% / 30%`。
用**最大余数法**分配，保证三者之和**恰好等于**题数（8 题 → `3 / 3 / 2`，实测 `EASY:3,MEDIUM:3,HARD:2`）。

### 3.3 三原则判定矩阵

每个维度取该维度所有题的**平均分**（未作答按 0 计），`passed = 平均分 ≥ 60`；
未通过维度数 `≥ 2` → `ai_dependent = true`；实际出过题的维度为 0 个 → `NULL`（未判定）。

| P1 复述 | P2 理由 | P3 改动 | 未通过数 | 判定 |
|---|---|---|---|---|
| ✅ | ✅ | ✅ | 0 | 未判定为依赖 |
| ✅ | ✅ | ❌ | 1 | 未判定为依赖 |
| ✅ | ❌ | ❌ | 2 | **完全依赖 AI** |
| ❌ | ❌ | ❌ | 3 | **完全依赖 AI** |
| ✅ | —（未出题） | ✅ | 0 | 未判定为依赖 |

**AI 只给分与证据，判定在服务端算**：换模型、换提示词都不会改变判定口径，也避免模型直接吐一个无法解释的「AI_DEPENDENT」标签。实测数据：P1 61.7 通过 / P2 59.2 未通过 / P3 62.5 通过 → `ai_dependent=0`（仅 1 项不通过）。

判定依据文案（服务端生成）：`解释修改理由：平均 59.2 分（阈值 60），3 题中 0 题单题达标 → 该维度判定未通过`。

### 3.4 加分题规则

- 固定 1 道，`dimension=IS_BONUS`、`bonus=true`、`type=EXTENSION`，**排最后**。
- **不计入总分、不影响等级、不影响判定**：AI 评分时把加分题从 `dimensions / score` 的输入里剔除；后端 `questionCount` 只统计计分题，加分题单独记 `bonus_question_count`。
- 限时 120s（`bonus-max-answer-seconds`），允许跳过（`bonus-skippable=true`）：跳过写 `status=SKIPPED, skipped=1`，教师端能看到「学生主动跳过」而不是「系统没收到」。
- 结果三态：`CORRECT`（≥60）/ `WRONG` / `UNANSWERED`。
- 数据结构与计分题分离：`is_bonus` 标记 + 独立字段 `bonus_result / bonus_score / bonus_comment`，不塞进 `score`。

---

## 4. 测试验证标准

### 4.1 自动化（全绿）

| 层 | 命令 | 结果 |
|---|---|---|
| 后端 | `./gradlew test` | **92 tests, 0 failures**（11 个测试类，含 `DefenseQuestionRuleTest` 9 例） |
| AI | `pytest tests -q` | **45 passed** |
| 前端 | `npm run test` | **37 passed**（含 `reviewPoll.test.mjs` 7 例） |
| 前端构建 | `vite build` | ✅ built（DefenseView 66.67 kB / SelectionsView 38.58 kB） |

`DefenseQuestionRuleTest` 覆盖：档位边界（0/59.9→6，60/74→7，75/84→8，85/94→9，95/100→10）、同档同量、缺分回退 T0、越界夹取（180→10、-40→6）、难度求和恒等于题数（0~100 全遍历）、高分档挑战占比不降、加分题不入 `total`、`maxCount` 覆盖档位、阈值默认 60。

`reviewPoll.test.mjs` 覆盖：退避 2/4/8/10 封顶与非法输入、只有 `finished` 才停、四种阶段视图、一阶段跑完不收尾、PARTIAL/FAILED 文案不同、只对 FAILED 且 `retryable` 给入口、30s 提示与时长文案。

### 4.2 接口联调（本地 8084 + AI 8000，飞书实测）

| 场景 | 结果 |
|---|---|
| `GET /question-rule` | ✅ 档位/配比/阈值/维度/批量上限全量返回 |
| `GET /submissions/{id}/review-status` | ✅ `overall=DONE, finished=true`，两侧分数 80.6 / 79.0 |
| `POST /defenses/batch`（2 人） | ✅ `DONE`，`succeeded=2`，各创建 8 题 + 1 加分题 |
| 同批再发一次（幂等） | ✅ `DONE, skipped=2`，原因「已有进行中的答辩」，**未重复建会话** |
| 出题维度分布 | ✅ P1×3 / P2×3 / P3×2 + 第 9 题 `IS_BONUS`，难度 3/3/2 |
| `POST .../ai-dependent-override` 理由过短 | ✅ 40001「覆盖判定必须填写理由（至少 5 个字）」 |
| `POST .../ai-dependent-override` 正常 | ✅ 落库 `ai_dependent=1, overridden=1, override_by=9002, override_at` |
| `POST .../skip`（加分题） | ✅ 返回题目 VO，落库 `skipped=1, status=SKIPPED` |
| `POST .../skip`（计分题） | ✅ 40001「只有加分题可以跳过」 |
| `finish → evaluate` | ✅ `score=64.3`，`ai_dependent=0`，`bonus_result=UNANSWERED`，`principle_result` 三行完整 |

### 4.3 验收对照（需求 → 证据）

| 需求 | 证据 |
|---|---|
| 进页面先拉一次、2s 起退避至 10s、DONE/FAILED 立即停 | `useReviewStatus` + `nextPollDelay` 单测 |
| `visibilitychange` 立即补拉 | `onVisible()` 把 attempt 归零后 `load({silent:true})` |
| 30s 未完成提示 + 手动刷新 | `shouldShowRefreshHint(30000)` 单测 + Panel 文案 |
| FAILED 显示原因与重试 | `retryTargets()` 只给 FAILED 且 retryable |
| 两侧独立 loading/error | 两个 stage 独立渲染，互不阻塞 |
| 卸载 abort / 序号防覆盖 | AbortController + `seq` |
| 批量逐学生结果 + 跳过原因 | 实测响应 `items[]` |
| 单批失败不影响其他 | `runOne` 各自 try/catch + `record` 原子累加 |
| 题数分档、同档同量、上限 10 | `DefenseQuestionRuleTest` |
| 三原则各 ≥1 题 | 实测 P1×3 P2×3 P3×2 |
| 两项不通过 → AI_DEPENDENT | 矩阵 + 实测 1 项不通过 → false |
| 覆盖必须填理由、仅留痕 | 接口实测 + 审计 `DEFENSE_AI_DEPENDENT_OVERRIDE` |
| 加分题不计总分、可跳过、教师可见三态 | AI 单测 `test_bonus_question_is_excluded_from_total` + 接口实测 |

---

## 5. 风险与兼容性

### 5.1 兼容性

| 项 | 影响 | 处理 |
|---|---|---|
| 历史会话 | 新列默认值；`ai_dependent=NULL` 表示未判定，UI 显示「未判定」，不会误报「不依赖」 | 不追溯、不回填 |
| 历史题数 | `question_count` 是快照（旧会话 5 题保留） | 列表/详情按落库值展示 |
| 评审侧 `score` | 旧失败记录曾写 0 分 | 新逻辑不再写 0；旧 0 分不主动清洗（清洗会改变已发布的成绩口径），需按 `error_message` 判读 |
| 接口 | 仅新增，未改动既有路径与字段语义 | 旧前端可继续用 |
| AI 服务 | `DefenseConfig` 新增字段均有默认值，旧请求不传也不报错 | 需**重启 AI 服务**才生效 |

### 5.2 风险与对策

1. **轮询风暴**：单页最多 1 条轮询链；批量另 1 条；退避封顶 10s；页面隐藏时批量轮询跳过一轮。单用户峰值约 0.2 QPS，远低于 20 QPS 限流。
2. **批量把 AI 打满**：`concurrency` 钳制 1~3（默认 2），50 人上限；失败只影响单条。
3. **批量后台线程丢身份**：`SecurityContextHolder` 是 ThreadLocal，**池化线程不继承**。本次实测就踩到了（首版全部 `未登录或登录已过期`），已在 `runOne` 里逐线程 `setAuthentication` + `finally clearContext`。
4. **批次计数被覆盖**：`record()` 在库里原子 `+1`，收尾若用 `updateById(内存对象)` 会把计数冲回 0（首版实测 `succeeded=2` 写成 0）。已改为 `LambdaUpdateWrapper` 只更新 `status / finished_at`。
5. **判定口径漂移**：判定只在服务端算，AI 的 `passed` 字段不采信；阈值配置化。
6. **教师覆盖滥用**：强制理由 ≥5 字 + 写审计 + 原始判定保留，可回溯。
7. **`SERVER__PORT` 环境变量污染**：本机 shell 存在 `SERVER__PORT=11464`，Spring 宽松绑定会把它当成 `server.port`，导致启动报「Port 11464 already in use」。启动后端请用 `env -u SERVER__PORT ./gradlew bootRun`，或显式 `--args='--server.port=8084'`。

### 5.3 遗留

- 前端构建产物校验用 `vite build --outDir .vite-check`（`npm run build` 会清空 `dist/`，被本机安全策略拦截）；该临时目录已加进 `.gitignore`，可手动删除。
- `defense_batch_task` 中 `2121184312378064897` 是修复计数 bug 之前的历史批次，`succeeded` 停留在 0，与 `items` 不一致；仅测试数据，不影响逻辑。
- 看门狗（15 分钟收敛）已按配置生效，但未做「人为造脏数据 + 等一轮扫描」的实测演练。
