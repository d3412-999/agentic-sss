# 修复报告：新建教学班 500「服务端异常」+ 配置项精简

> 日期：2026-11-12
> 范围：教师端「教学班」新建/设置、选题上限、答辩配置
> 结论：500 的根因是 `OfferingService.validateTimeline` 用 `List.of(...)` 收集可为 null 的时间节点，`defenseEnd` 缺失时抛 `NullPointerException`，被全局兜底成 50000。

---

## 1. 故障定位报告

### 1.1 复现步骤

1. 教师账号 `T2019008` 登录，取 accessToken。
2. 按截图表单提交（课程模板 CD-2026、学期 2026-2027-1、教学班 121、权重 40/35/25、时间线 2026-11-12 / 2026-11-26 / 2027-01-11 / 2026-11-22）。
3. 请求体（与前端 `submitCreate` 组装结果一致）：

```json
{
  "courseId": "9101",
  "semester": "2026-2027-1",
  "className": "121",
  "description": "OCP",
  "topicSelectLimit": 3,
  "weights": { "defense": 40, "code": 35, "report": 25 },
  "defenseConfig": {
    "defenseMode": "AI_ASYNC",
    "questionCount": 5,
    "maxAnswerSeconds": 180,
    "allowRetry": true,
    "language": "zh-CN"
  },
  "timeline": {
    "selectStart": "2026-11-12",
    "selectEnd": "2026-11-26",
    "submitDeadline": "2027-01-11",
    "defenseStart": "2026-11-22"
  }
}
```

注意：`timeline` 里**没有 `defenseEnd`** —— 前端表单只有「答辩开始」，没有「答辩结束」输入项。

### 1.2 响应

```
HTTP/1.1 500
X-Trace-Id: 9a8587ea222d413cb13d73a86c5f4764

{"code":50000,"message":"服务端异常","data":null,"traceId":"9a8587ea222d413cb13d73a86c5f4764"}
```

`code=50000` 对应 `ErrorCode.INTERNAL_ERROR("服务端异常")`，由 `GlobalExceptionHandler.handleUnexpectedException` 兜底输出，说明抛出的不是 `BizException`。

### 1.3 堆栈关键行

原始进程由 IDEA 启动，日志输出到 IDE 控制台，未落盘，因此未能直接抓取堆栈。改用**三组对照实验**把范围收敛到唯一语句：

| 实验 | timeline 内容 | 结果 | 说明 |
|---|---|---|---|
| A | 带 `defenseEnd=2027-01-20`，顺序合法 | **200 成功** | 有结束节点即正常 |
| B | 带 `defenseEnd`，但答辩开始早于提交截止 | **400** `{"code":40001,"message":"时间节点顺序不合法",...}` | 业务校验本身正常 |
| C | 不带 `defenseEnd`，其余顺序合法 | **500** `{"code":50000,"message":"服务端异常","traceId":"86e8474af5a649cd8bf65032f55d1865"}` | 唯一变量是 null |

三组只有「`defenseEnd` 是否为 null」不同，A/B 证明异常处理链与业务校验都正常，C 证明 null 必然 500。

定位到 `OfferingService.validateTimeline`：

```java
// 修复前
var chain = List.of(offering.getSelectStart(), offering.getSelectEnd(),
        offering.getSubmitDeadline(), offering.getDefenseStart(), offering.getDefenseEnd());
```

`List.of(E...)` 内部对每个元素执行 `Objects.requireNonNull`，任一为 null 立即抛 `NullPointerException`。该方法内其余语句只有 `isBefore` 比较（已做 null 判断）和 `throw BizException`，因此 NPE 只能来自这里。

### 1.4 根因结论

**根因**：时间线校验用 `List.of` 构造不可变集合，而「答辩结束」是可选节点（前端从未提交）。请求只要带 `timeline` 且未填答辩结束，`offering.getDefenseEnd()` 即为 null → `List.of` 抛 NPE → 未被 `@ExceptionHandler(BizException.class)` 捕获 → 落到 `Exception` 兜底 → 500「服务端异常」。

**为什么之前没暴露**：`applyTimeline` 对 null 是安全的（`Times.startOfDay/endOfDay` 都判空），null 一路传到 `validateTimeline` 才炸；且前端表单没有答辩结束输入项，等于新建教学班 100% 触发。

**次生问题**：前端 `submitCreate` 只显示 `err.message`，不显示 traceId；时间线倒置时（截图里答辩开始 2026-11-22 早于提交截止 2027-01-11）后端返回的 400 具体原因在部分路径下不够醒目。已一并改善。

---

## 2. 改动清单

### 后端

| 文件 | 修改点 | 原因 |
|---|---|---|
| `backend/.../service/OfferingService.java` | `validateTimeline` 改用 `Arrays.asList`（允许 null）；新增 `resolveCourseId`；`create` 不再设置 `topicSelectLimit`；`patch` 删除限选分支；`applyDefenseConfig` 删除 `defenseMode`、题数固定 null；`toVo`/`studentOfferingVo` 对 `courseId`、`topicSelectLimit` 判空 | 修 500 根因；课程模板与数量限制移除后的 null 安全 |
| `backend/.../dto/OfferingDtos.java` | `DefenseConfig` 删除 `defenseMode`；`OfferingCreateRequest.courseId` 去掉 `@NotBlank` | 契约精简；模板改为可选 |
| `backend/.../entity/Offering.java` | 删除 `defenseMode` 字段 | 固定 AI 异步答辩，不再读写该列 |
| `backend/.../service/SelectionService.java` | 选题限额判断先判空再比较 | NULL = 不限；避免 Integer 自动拆箱 NPE |
| `backend/.../service/DefenseService.java` | `courseMapper.selectById` 前判空 | `course_id` 可为 null |
| `backend/.../service/GradeService.java` | 同上 | 同上 |
| `backend/src/main/resources/db/migration/V8__remove_offering_limits.sql` | 新增迁移 | 列放宽 + 存量归一 |

### 前端

| 文件 | 修改点 | 原因 |
|---|---|---|
| `vue/src/views/OfferingsView.vue` | 表单删除课程模板、每人最多选几题、答辩模式、答辩题目数；删除「课程模板」入口与新建模板弹窗；提交体只保留必要字段；新增 `errorText` 拼接 message + traceId；列表课程名为空显示「未关联课程」 | 功能精简 + 可诊断错误 |
| `vue/src/components/offering/SettingsTab.vue` | 删除限选输入与「模式」展示；题数显示「不限」；错误带 traceId；状态机文案去掉「限选数」 | 与后端契约一致 |
| `vue/src/views/TopicSelectView.vue` | `topicSelectLimit ?? Infinity`；剩余名额与文案适配「不限」 | NULL 不得被当成 0 而禁止选题 |
| `vue/src/views/CoursesView.vue` | 显示 `?? '不限'` | 同上 |
| `vue/src/views/DashboardView.vue` | 显示 `?? '不限'` | 同上 |
| `API接口文档.md` | 创建开课示例与字段表移除 `defenseMode`/`topicSelectLimit`/`questionCount` | 契约同步 |

### 未改动

- V1~V7 迁移文件（历史不可变）。
- 既有接口路径、错误码语义、审计日志写入点（create/patch 仍调用 `auditLog.record`）。

---

## 3. V8 迁移 SQL

文件：`backend/src/main/resources/db/migration/V8__remove_offering_limits.sql`

```sql
ALTER TABLE offering
    MODIFY COLUMN topic_select_limit INT     NULL DEFAULT NULL COMMENT '每人最多可选题目数；NULL = 不限',
    MODIFY COLUMN question_count     TINYINT NULL DEFAULT NULL COMMENT '答辩题目数量；NULL = 不限',
    MODIFY COLUMN course_id          BIGINT  NULL              COMMENT '课程 ID；NULL = 未关联课程模板',
    MODIFY COLUMN defense_mode       VARCHAR(16) NOT NULL DEFAULT 'PER_STUDENT'
        COMMENT '已废弃：固定 AI 异步答辩，仅保留历史数据';

-- 存量归一：历史教学班的 3 / 5 等硬限制统一改为「不限」。
UPDATE offering SET topic_select_limit = NULL WHERE topic_select_limit IS NOT NULL;
UPDATE offering SET question_count = NULL WHERE question_count IS NOT NULL;
```

**存量数据处理**：两条 UPDATE 把现有教学班的 `topic_select_limit`、`question_count` 统一置 NULL，避免历史班仍卡在 3 题、5 题。`defense_mode` 只改注释不改值，历史读取不受影响。

**回滚**（Flyway 不自动回滚，需手工执行）：

```sql
UPDATE offering SET topic_select_limit = 3 WHERE topic_select_limit IS NULL;
UPDATE offering SET question_count = 6      WHERE question_count IS NULL;
UPDATE offering SET course_id = (SELECT MIN(id) FROM course) WHERE course_id IS NULL;
ALTER TABLE offering
    MODIFY COLUMN topic_select_limit INT NOT NULL DEFAULT 3,
    MODIFY COLUMN question_count TINYINT NOT NULL DEFAULT 6,
    MODIFY COLUMN course_id BIGINT NOT NULL COMMENT '课程 ID';
```

**可重复验证**：`MODIFY` 与带 `IS NOT NULL` 条件的 UPDATE 均可重复执行，不会二次失败。

---

## 4. 接口契约变化

### `POST /api/v1/teacher/offerings`

| 字段 | 变化 | 说明 |
|---|---|---|
| `courseId` | 由必填改为**可选** | 缺省时绑定系统内第一条课程模板；无模板则存 NULL |
| `topicSelectLimit` | **停用** | 传值被忽略，落库恒为 NULL（不限） |
| `defenseConfig.defenseMode` | **删除** | 旧客户端传了会被 Jackson 忽略，不报错 |
| `defenseConfig.questionCount` | **停用** | 传值被忽略，落库恒为 NULL（不限） |
| `timeline.defenseEnd` | 仍可选 | 缺失不再 500；顺序校验仅在相邻非空节点间进行 |

### `PATCH /api/v1/teacher/offerings/{id}`

| 字段 | 变化 |
|---|---|
| `topicSelectLimit` | 停用；已发布状态下传了也不再触发「不可修改」拦截 |

### 响应体

| 字段 | 变化 |
|---|---|
| `topicSelectLimit` | 恒为 `null`，表示不限 |
| `defenseConfig.questionCount` | 恒为 `null` |
| `defenseConfig.defenseMode` | 不再返回 |

### 错误码

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 缺 `defenseEnd` | 500 / 50000「服务端异常」 | 200 |
| 时间线倒置 | 400 / 40001（正常） | 不变，message 含完整链路要求 |
| 旧客户端带废弃字段 | - | 200，字段被忽略 |
| 发布缺学生/题目 | 422 / 42201 + blockers | 不变 |

---

## 5. 关键代码 diff

### 5.1 根因修复（OfferingService.validateTimeline）

```diff
     private void validateTimeline(Offering offering) {
-        var chain = List.of(offering.getSelectStart(), offering.getSelectEnd(),
-                offering.getSubmitDeadline(), offering.getDefenseStart(), offering.getDefenseEnd());
+        // 这里必须用允许 null 的集合：答辩结束等节点本来就是可选的，
+        // List.of(...) 遇到 null 会抛 NullPointerException，最终变成 500「服务端异常」。
+        List<LocalDateTime> chain = Arrays.asList(offering.getSelectStart(), offering.getSelectEnd(),
+                offering.getSubmitDeadline(), offering.getDefenseStart(), offering.getDefenseEnd());
         for (int i = 1; i < chain.size(); i++) {
             if (chain.get(i - 1) != null && chain.get(i) != null
                     && chain.get(i).isBefore(chain.get(i - 1))) {
```

### 5.2 数量限制移除（create）

```diff
-        int limit = request.topicSelectLimit() == null ? 3 : request.topicSelectLimit();
-        BizAssert.param(limit >= 1 && limit <= 10, "每人限选题目数需在 1~10 之间");
-
         Long duplicate = offeringMapper.selectCount(...);
@@
-        offering.setTopicSelectLimit(limit);
+        // NULL = 不限：不再限制每人可选题目数，旧客户端传入的值也一律忽略
+        offering.setTopicSelectLimit(null);
```

### 5.3 答辩配置精简

```diff
-        OfferingDtos.DefenseConfig effective = config == null
-                ? new OfferingDtos.DefenseConfig("PER_STUDENT", 6, 180, false, "zh-CN", false)
-                : config;
-        int questions = effective.questionCount() == null ? 6 : effective.questionCount();
+        OfferingDtos.DefenseConfig effective = config == null
+                ? new OfferingDtos.DefenseConfig(null, 180, false, "zh-CN", false)
+                : config;
         int seconds = ...
-        BizAssert.param(questions >= 3 && questions <= 12, "答辩题目数需在 3~12 之间");
-        offering.setDefenseMode(...);
-        offering.setQuestionCount(questions);
+        // 统一「不限」：即使旧客户端仍传固定题数也忽略
+        offering.setQuestionCount(null);
```

### 5.4 选题校验 null 安全（SelectionService）

```diff
-        BizAssert.rule(existing.size() + distinct.size() <= offering.getTopicSelectLimit(),
-                "超出每人限选题目数（上限 " + offering.getTopicSelectLimit() + "）");
+        Integer topicLimit = offering.getTopicSelectLimit();
+        BizAssert.rule(topicLimit == null || existing.size() + distinct.size() <= topicLimit,
+                "超出每人限选题目数（上限 " + topicLimit + "）");
```

### 5.5 前端错误提示

```diff
-  } catch (err) {
-    const fields = err?.data?.errors
-    formError.value = Array.isArray(fields) && fields.length
-      ? fields.map((f) => f.message).join('；')
-      : err.message
-  } finally {
+  } catch (err) {
+    formError.value = errorText(err)
+  } finally {
```

```js
function errorText(err) {
  const fields = err?.data?.errors
  const detail = Array.isArray(fields) && fields.length
    ? fields.map((f) => f.message).filter(Boolean).join('；')
    : ''
  const message = detail || err?.message || '请求失败'
  return err?.traceId ? `${message}（traceId: ${err.traceId}）` : message
}
```

---

## 6. 验证记录

### 6.1 迁移

```
Flyway: Current version of schema `sss`: 7
Flyway: Migrating schema `sss` to version "8 - remove offering limits"
Flyway: Successfully applied 1 migration to schema `sss`, now at version v8
```

列定义（`information_schema.COLUMNS`）：

```
course_id           YES  NULL  bigint
defense_mode        NO   PER_STUDENT  varchar(16)
question_count      YES  NULL  tinyint
topic_select_limit  YES  NULL  int
```

### 6.2 接口回归

| 用例 | 请求 | 结果 |
|---|---|---|
| A 仅必填项（无课程模板/无数量字段/无答辩开始） | 学期、班级、权重、3 个时间节点 | **200**，`topicSelectLimit=null`、`questionCount=null`、`courseId` 自动绑定 9101 |
| B 截图原样（答辩开始早于提交截止） | 见 1.1 | **400** `{"code":40001,"message":"时间节点顺序不合法","data":{"errors":[{"field":"timeline","message":"时间需满足：选题开始 ≤ 选题结束 ≤ 提交截止 ≤ 答辩开始 ≤ 答辩结束"}]}}` |
| C 旧客户端字段全带 | `courseId` + `topicSelectLimit:3` + `defenseMode` + `questionCount:5` | **200**，两个数量字段均落为 `null`，废弃字段被忽略 |
| 详情 | `GET /teacher/offerings/{id}` | **200** |
| 列表 | `GET /teacher/offerings` | **200**，新记录可见 |
| 发布（缺学生/题目） | `POST .../publish` | **422** `{"code":42201,"blockers":[{"code":"NO_STUDENT"...},{"code":"NO_TOPIC"...}]}`，非 5xx |
| 历史教学班 9201 | `GET /teacher/offerings/9201` | **200**，`topicSelectLimit=null`、`questionCount=null`，其余字段完整 |
| 学生端课程卡片 | `GET /student/offerings` | **200**，`topicSelectLimit=null`、`canSelect=true` |
| 学生一次选 4 题 | `POST /student/selections` 4 个 topicId | **200**，4 条 `SELECTED`（旧上限 3 已被移除） |

### 6.3 构建

```
cd backend && ./gradlew compileJava --no-daemon   -> BUILD SUCCESSFUL
cd backend && ./gradlew test --no-daemon          -> BUILD SUCCESSFUL
cd vue && npm run build                           -> ✓ built in 2.47s
```

### 6.4 页面操作

- 新建弹窗只剩：学期、教学班、课程说明、成绩权重、时间线（选题开始/结束、提交截止、答辩开始）。
- 「每人最多选几题」「答辩模式」「答辩题目数」「课程模板」四项及「课程模板」入口均不再出现。
- 请求体不再包含上述字段（已由 C 用例验证旧字段被忽略）。
- 错误区域会显示 `具体原因（traceId: xxx）`。

### 6.5 数据清理

回归产生的 5 条测试教学班（含学生、题目、选题）已按 id 精确删除，`offering` 仅剩 seed 的 9201。

---

## 7. 遗留风险与未覆盖项

1. **答辩题数仍有技术护栏**：`DefenseService.resolveQuestionCount` 保留 `1~12` 校验，AI 服务 `defense_service.py` 也有 `min(count, 12)`。这是单次会话的出题量保护（防止 prompt 爆量），不是教学班配置上限；开课侧已恒为 NULL（不限）。
2. **单次批量选题上限仍存在**：`sss.limits.selection-batch-max`（默认 10）限制单次请求最多选几题，属于防滥用护栏，与「每人累计可选」无关，未改动。如需彻底放开，需同时评估接口耗时与事务长度。
3. **课程模板降级为隐式绑定**：新建时不选模板，后端取 `course` 表第一条；若一条都没有则 `course_id=NULL`，列表显示「未关联课程」。若产品上要彻底删除「课程模板」概念，需要另做迁移把 `course` 表并入 offering。
4. **答辩模式列保留但停用**：`offering.defense_mode` 仍在表中（`NOT NULL DEFAULT 'PER_STUDENT'`），历史数据可读；确认无需回滚后可另起迁移物理删除。
5. **时间线仍要求单调**：截图里「答辩开始 2026-11-22 早于提交截止 2027-01-11」会返回 400。这是既有业务规则，不是本次 bug；若产品上允许答辩在提交截止前开始，需要单独确认后放宽。
6. **未做数据库集成测试**：本次回归是真实 HTTP + 真实 MySQL/Redis 手工验证，未新增 Testcontainers 用例；建议后续把「缺 defenseEnd 创建成功」「NULL 限额可连选多题」固化为自动化测试。

---

## 附：执行前 8 个确认项的答案

| # | 问题 | 结论与依据 |
|---|---|---|
| 1 | traceId 与堆栈 | 500 的 traceId 为 `9a8587ea222d413cb13d73a86c5f4764`；堆栈未落盘，用三组对照实验 + 代码定位锁定 `List.of` NPE（见 1.3） |
| 2 | 答辩模式可选值与去留 | 前端实际只提交 `AI_ASYNC`（DB 注释里的 PER_STUDENT/PER_TOPIC 从未被 UI 使用）；删除后固定 AI 异步；列**保留停用** |
| 3 | NULL 还是 0 | 统一 **NULL = 不限**；前端不展示数值，后端落库恒 NULL |
| 4 | 限制清单 | 选题数（`topic_select_limit`）、答辩题数（`question_count`）已移除；教学班人数、题目总数、提交次数在代码中**本就不存在**上限；仅剩单次批量选题与 AI 单次出题的技术护栏（见 7.1/7.2） |
| 5 | 存量是否迁移 | 是，V8 把存量统一置 NULL（不限） |
| 6 | 权重和 100 | 保留（创建校验 + 发布阻塞项都依赖） |
| 7 | 时间线是否允许倒置 | 不允许，保留单调校验；它不是本次 500 的根因（实验 B 证明返回的是 400） |
| 8 | 是否新增 V8 / 改前端 | 是；旧前端字段被忽略，不需要同版本兼容改造 |
