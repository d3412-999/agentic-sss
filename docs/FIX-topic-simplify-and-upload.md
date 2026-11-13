# 题目模块改造：多文件附件上传 + 字段瘦身（V9）

> 目标：题目只保留「标题 / 描述 / 难度 / 附件」；附件支持批量、逐文件进度、三重校验；
> 难度成为唯一分类维度。全部接口 4xx 带中文原因，不出现 500。

---

## 1. 现状盘点（改造前）

### 1.1 上传实现位置

| 位置 | 现状 |
|---|---|
| `POST /api/v1/files`（`SubmissionController` 内） | 字段名 `file`，**只收一个文件**；`purpose` 默认 `TOPIC_ATTACHMENT`；上限硬编码 `20MB`；无格式校验 |
| `FileStorageService.store()` | 流式拷贝 + SHA-256 秒传；`objectKey` 由 UUID 生成；**去重只按 sha256**，会把别人的同内容文件「借用」过来 |
| `GET /api/v1/files/{id}/download` | 鉴权三种放行：上传者 / 教师 / 开课名单内学生；`Content-Disposition` 固定 `attachment` |
| 前端 | `TopicsTab.vue` **没有任何附件上传 UI**，`TopicDtos` 里的 `attachments` 字段从未被前端提交过 |

结论：真实语义是「给题目挂附件」，**不是**从文件批量导入题目；已有 `topic_attachment` 关联表与 `file_object` 元数据表，无需新建表。

### 1.2 题目表结构（V8 后）

`topic`：`id / offering_id / title / description / requirements / difficulty / max_select_count / selected_count / tags / expected_tech / status / published_at / created_by / version / created_at / updated_at / deleted`，索引 `uk_topic_title(offering_id,title)`、`idx_topic_offering_status(offering_id,status)`。

### 1.3 字段使用点

| 字段 | 使用点 |
|---|---|
| `requirements` | 教师表单、学生详情抽屉；`DefenseService.buildQuestionPayload` 下发给 AI 出题；AI `report_evaluation` / `defense_questions` 提示词读 `topic.requirements` |
| `tags` | 教师表单、列表标签列；学生搜索（`like(TOPIC.tags, keyword)`）、卡片标签 |
| `expected_tech` | 教师表单；`DefenseService` 下发给 AI（AI schema 未定义，`extra=ignore`） |
| `max_select_count` | 表单「容量」、列表容量列、`TopicExtraMapper.reserveOne` 的 `WHERE selected_count < max_select_count`、`TopicService.validatePublishable`、`publishBatch` 前置校验 |
| `status` | 列表四个状态页签、发布/归档按钮、学生端 `eq(status,'PUBLISHED')`、`SelectionService` 选题校验、`OfferingService.publishBlockers` 的 `NO_TOPIC` |

### 1.4 容量与状态的“隐性”下游

- `TopicService.studentTopicVo` 用 `Math.max(0, max - selected)` 算 `remaining`，前端「名额已满」按钮、`tone()` 颜色、详情抽屉的「剩余 x / y」全部依赖它。
- `TopicExtraMapper.reserveOne` 返回 0 被硬编码成 `TOPIC_FULL`，学生端提示「名额已满」。
- 教学班发布前置校验 `NO_TOPIC` 统计的是 `status='PUBLISHED'` 的题目 → 删状态后必须改成「题目总数」。

### 1.5 执行前八个问题的代码答案

| # | 问题 | 答案 |
|---|---|---|
| 1 | 上传语义 | 「给题目挂附件」，非批量导入 |
| 2 | 单文件入口 / 存储 | `POST /files`（`file`）；本地磁盘 `./data/objects/yyyy/MM/uuid-原名`；附件表 `topic_attachment` 已存在 |
| 3 | 状态怎么办 | **由你决定：彻底取消**（新建即可见，无草稿/发布/归档） |
| 4 | 是否取消人数上限 | 是，完全取消 |
| 5 | 难度取值 | 固定三级编码，可扩展（枚举末尾追加即生效） |
| 6 | 格式与限额 | **仅 pdf/doc/docx；单文件 20MB、单次 5 个、单次合计 50MB、单题 20 个** |
| 7 | 五个字段的列 | **物理删除** |
| 8 | 附件是否被 AI 读取 | 否。AI 只读题目文字，`requirements` 从 Java 侧不再下发，AI schema 该字段有默认空值，不影响出题 |

> **迁移版本说明**：要求里写「新迁移用 V8」，但 V8 已被上一次改造占用且已落库（`V8__remove_offering_limits.sql`），
> 改动它会破坏 Flyway 校验和。因此本次使用 **V9**，语义与要求一致：不改历史迁移、只追加。

---

## 2. 改造方案

### 2.1 上传时序

```
选择/拖拽 3 个文件
   └─ 前端本地校验：扩展名 → 空文件 → 单文件大小 → 累计总量 → 单题数量
        └─ 每个文件一次 POST /files/batch（files 数组里放 1 个）
             ├─ 后端请求级校验：数量 ≤ 5、总量 ≤ 50MB（超限 400，未写盘）
             ├─ 后端文件级校验：扩展名 → 魔数 → MIME（单个失败不影响其它）
             ├─ 落盘（流式）→ 入库（失败即删盘，避免孤儿）
             └─ 返回 succeeded[1] / failed[1]
        └─ 成功项收集 fileId → 「保存」时随创建请求提交 attachments
编辑题目：上传成功后立即 POST /topics/{id}/attachments 挂载
```

**为什么一文件一请求**：浏览器只有 `xhr.upload.onprogress` 一个进度事件，一次请求传多个文件只能得到总进度。
拆成一文件一请求才能做到「逐文件进度 / 逐文件重试 / 逐文件取消」，而后端仍保留真正的批量契约（`List<MultipartFile>`）。

### 2.2 存储结构

沿用现有两张表，无新增表：

- `file_object`：`bucket = 'TOPIC_ATTACHMENT'`、`object_key = yyyy/MM/uuid-清洗后文件名`、`content_type` 归一为三种标准 MIME；
- `topic_attachment`：`(topic_id, file_id, sort_no)`，唯一键 `uk_ta_topic_file` 兜底重复挂载。

去重范围从「仅 sha256」收紧为「sha256 + bucket + created_by」——否则会把别人上传的同内容文件当成自己的复用，鉴权直接错乱。

### 2.3 字段删除策略

| 字段 | 前端 | 后端 | 数据库 |
|---|---|---|---|
| 交付要求 `requirements` | 删除输入与展示 | 实体/DTO/Service 删除；AI 出题不再下发 | **DROP COLUMN** |
| 标签 `tags` | 删除输入、列表列、学生卡片与搜索 | 同上 | **DROP COLUMN** |
| 期望技术栈 `expected_tech` | 删除输入 | 同上 | **DROP COLUMN** |
| 容量 `max_select_count` | 删除输入与「容量 / 剩余名额」全部展示 | 删除校验；`reserveOne` 去掉上限条件；`remaining` 从 DTO 移除 | **DROP COLUMN** |
| 状态 `status` | 删除页签、状态列、发布/归档按钮 | 删除枚举分支、发布/归档接口、学生端 `PUBLISHED` 过滤 | **DROP COLUMN** + 删除依赖它的索引 |
| 发布时间 `published_at` | 不再展示（列表改显示「更新于」） | 实体中移除，不再读写 | 保留列（历史可查，不做额外破坏性变更） |

### 2.4 难度模型

`Difficulty` 枚举：`code / label / sort`（`EASY 入门 10`、`MEDIUM 进阶 20`、`HARD 挑战 30`）。

- 写入：`Difficulty.require()` — 空值默认 `MEDIUM`，非法值 `40001`，接受编码或中文名；
- 读取：`Difficulty.labelOf()` — 无法识别显示「未分级」，**永不抛异常**；
- 筛选：`Difficulty.filterCode()` — 非法值视为「不筛选」；
- 下发：`GET /meta/difficulties`，前端页签与下拉都从字典渲染；
- 索引：`idx_topic_offering_difficulty(offering_id, difficulty)`。

---

## 3. V9 迁移

文件：`backend/src/main/resources/db/migration/V9__simplify_topic.sql`

```sql
ALTER TABLE topic DROP INDEX idx_topic_offering_status;

ALTER TABLE topic
    DROP COLUMN requirements,
    DROP COLUMN tags,
    DROP COLUMN expected_tech,
    DROP COLUMN max_select_count,
    DROP COLUMN status;

ALTER TABLE topic ADD KEY idx_topic_offering_difficulty (offering_id, difficulty);

ALTER TABLE topic
    MODIFY COLUMN selected_count INT NOT NULL DEFAULT 0 COMMENT '已选人数（仅统计，无上限）';
ALTER TABLE topic
    MODIFY COLUMN difficulty VARCHAR(16) NOT NULL DEFAULT 'MEDIUM'
        COMMENT '难度编码：EASY / MEDIUM / HARD，展示名见 Difficulty 枚举';
```

**存量数据**：五列内容随列删除永久丢失，执行前请备份 `mysqldump ... sss topic`。
`published_at` 保留，历史发布时间仍可查；历史非法 `difficulty` 不需要清洗（读取侧兜底）。

**回滚**：见迁移文件头部注释（`ADD COLUMN` + 重建索引 + 删除 `flyway_schema_history` 中 version=9 的记录）。数据不可恢复。

---

## 4. 接口契约表

| 方法 | 路径 | 入参 | 出参 | 错误码 |
|---|---|---|---|---|
| GET | `/teacher/offerings/{id}/topics` | `difficulty`（编码或中文，可选）`keyword` `page` `size` | `list[{id,title,difficulty,difficultyLabel,selectedCount,attachmentCount,createdAt,updatedAt}]` | 403/404 |
| POST | `/teacher/offerings/{id}/topics` | `{title,description,difficulty,attachments[]}` | `TopicVO` | 40001/40901/42201 |
| GET | `/topics/{id}` | — | `TopicVO`（含 `attachments[]`） | 403/404 |
| PUT | `/topics/{id}` | `{version,title?,description?,difficulty?}` | `TopicVO` | 40001/40901 |
| DELETE | `/topics/{id}` | — | `void` | 42201（已有选题） |
| POST | `/topics/{id}/attachments` | `{fileIds[]}` | `AttachmentVO[]` | 40001/40301/42201 |
| DELETE | `/topics/{id}/attachments/{fileId}` | — | `AttachmentVO[]` | 40901（不存在） |
| PUT | `/topics/{id}/attachments/order` | `{fileIds[]}` | `AttachmentVO[]` | 40001（集合不一致） |
| POST | `/files/batch` | multipart `files`（1~5） | `{succeeded[],failed[]}` | 40001（数量/总量）、40002（单文件，走 failed） |
| POST | `/files` | multipart `file` | `FileVO` | 40002 |
| GET | `/files/{id}/download?inline=` | — | 二进制流 | 401/403/404 |
| GET | `/meta/difficulties` | — | `[{code,label,sort}]` | — |
| GET | `/meta/upload-policy` | — | `{allowedExtensions,maxFileBytes,maxFilesPerRequest,maxTotalBytes,maxPerTopic}` | — |

`/api/v1/meta/**` 已加入 `SecurityConfig` 的放行白名单：字典是静态常量、不含用户数据，
放行后 token 过期时难度筛选条也能拿到真实字典，不会静默回退到前端兜底三档。

**删除的端点**：`POST /topics/{id}/publish`、`POST /topics/{id}/archive`、`POST /teacher/offerings/{id}/topics/publish`。

**字段变化**：请求/响应中不再出现 `requirements / tags / expectedTech / maxSelectCount / status / remaining`；新增 `difficultyLabel`、`version`（列表不含）。

---

## 5. 关键代码 diff

### 5.1 根因式修复：`reserveOne` 去掉容量条件

```diff
 UPDATE topic
    SET selected_count = selected_count + 1, version = version + 1, updated_at = NOW(3)
  WHERE id = #{topicId}
    AND deleted = 0
-   AND status = 'PUBLISHED'
-   AND selected_count < max_select_count
```

### 5.2 教学班发布前置校验改口径

```diff
 Long topics = topicMapper.selectCount(new LambdaQueryWrapper<Topic>()
-        .eq(Topic::getOfferingId, offering.getId())
-        .eq(Topic::getStatus, "PUBLISHED"));
+        .eq(Topic::getOfferingId, offering.getId()));
 if (topics == null || topics == 0) {
-    blockers.add(new Blocker("NO_TOPIC", "尚未发布任何题目"));
+    blockers.add(new Blocker("NO_TOPIC", "尚未创建任何题目"));
 }
```

### 5.3 前端表单（`TopicsTab.vue`）

```diff
-<div class="field"><label>交付要求（选填）</label><textarea v-model="form.requirements" .../></div>
-<div class="form-grid form-grid--3">
-  <div class="field"><label>难度</label><select .../></div>
-  <div class="field"><label>容量（最多几人选）</label><input type="number" v-model.number="form.maxSelectCount"/></div>
-  <div class="field"><label>状态</label><input :value="'DRAFT'" disabled/></div>
-</div>
-<div class="form-grid">
-  <div class="field"><label>标签（逗号分隔）</label><input v-model="form.tagsText"/></div>
-  <div class="field"><label>期望技术栈（逗号分隔）</label><input v-model="form.techText"/></div>
-</div>
+<div class="field" style="max-width: 220px">
+  <label for="topic-difficulty">难度</label>
+  <select id="topic-difficulty" v-model="form.difficulty">
+    <option v-for="d in difficulties" :key="d.code" :value="d.code">{{ d.label }}</option>
+  </select>
+</div>
+<div class="field">
+  <label>题目附件</label>
+  <AttachmentUploader :topic-id="editing?.id || ''" :existing="editing?.attachments || []" :policy="policy"
+                      @update:file-ids="attachmentFileIds = $event" @changed="attachmentsChanged" />
+</div>
```

### 5.4 上传校验（`FileTypeValidator`）

```java
String extension = extensionOf(filename);
if (!policy.getAllowedExtensions().contains(extension)) throw fileInvalid("格式不支持");
if (file.getSize() > policy.getMaxFileBytes())      throw fileInvalid("超过单文件上限");
if (!magicMatches(file, extension))                 throw fileInvalid("内容与扩展名不符");
// docx：ZIP 头 + 必须能解出 word/ 条目，仅改后缀的 zip 会被拒
```

### 5.5 请求级 vs 文件级（`FileUploadService`）

```java
BizAssert.param(present.size() <= policy.getMaxFilesPerRequest(), "单次最多上传 5 个文件…");
BizAssert.param(total <= policy.getMaxTotalBytes(), "本次文件合计… 超过单次总量上限…");
for (MultipartFile file : present) {
    try { ... } catch (BizException ex) { failed.add(new FailedUpload(rawName, ex.getMessage())); }
}
```

---

## 6. 验证记录（真实执行）

| 用例 | 结果 |
|---|---|
| 3 个混合文件（pdf+docx+doc）批量上传 | 200，`succeeded=3`，逐条返回 fileId / size / 归一 MIME |
| 文本改名 `.pdf` | 200，`failed[0]`「内容与扩展名不符，已拒绝（请勿修改文件后缀）」 |
| 只有 zip 头、无 `word/` 的假 `.docx` | 同上被拒 |
| `.exe` | 200，`failed`「格式不支持，仅允许 PDF / DOC / DOCX」 |
| 21MB `big.pdf` | 200，`failed`「大小 21.0MB 超过单文件上限 20MB」，磁盘无残留 |
| 一次 6 个文件 | **400**「单次最多上传 5 个文件，本次选择了 6 个」 |
| 带 3 个附件创建题目 | 200，`attachmentCount=3`，`difficultyLabel=挑战` |
| 难度筛选 `HARD` / `EASY` / 非法值 `WRONG` | 分别命中 3 条 / 1 条 / 不筛选（均 200） |
| 追加附件 → 倒序 → 删除 | 4 个 → `[t4,t3,t2,t1]` → 3 个，全部 200 |
| PDF `?inline=true` | `Content-Disposition: inline`、`Content-Type: application/pdf`、`nosniff` |
| 普通下载 / 无 token | `attachment` / 401 |
| 学生列表与详情 | 无状态过滤，返回 `selectedCount`、`attachmentCount`、`attachments[]` |
| 学生选题（无容量） | 200，`selectedCount` 变 1 |
| 学生下载题目附件 | 200（名单规则生效） |
| 新教学班发布（题目无“发布”动作） | 200，`status=PUBLISHED`，不再报 `NO_TOPIC` |
| 已被选题时删除 / 改标题 | 422 / 409；改难度 200 |
| 写入非法难度 | 400「仅支持 EASY / MEDIUM / HARD」 |
| 历史题目 `9403`（seed） | 200，字段正常，`attachments=[]` |
| 无 token 访问 `/meta/difficulties`、`/meta/upload-policy` | 200（已放行）；同为无 token 的 `/teacher/**`、`/student/**` 仍 401 |
| `./gradlew compileJava` / `test` | BUILD SUCCESSFUL；7 个测试类 55 个用例全绿（新增 `DifficultyTest` 5、`FileTypeValidatorTest` 9） |
| `npm run build` | ✓ built |
| Flyway | `Successfully applied 1 migration … now at version v9` |

回归数据已清理：测试教学班、学生、题目、选题、`file_object` 与磁盘文件全部按 id 精确删除（`offering` 3 条、`topic` 8 条均为 seed）。

---

## 7. 风险与未覆盖项

1. **不可逆**：五个字段的列被物理删除，历史题目的交付要求/标签/技术栈/容量/状态无法恢复（已按要求执行，备份语句写在 V9 头部）。
2. `published_at` 列保留但已停止读写；若要彻底清理需再起一次迁移（会失去历史发布时间）。
3. **AI 出题契约**：`topic.requirements` 不再下发，`langchain_service` 的 `defense_questions` 提示词里「要求」分支会落到「未填写」。若希望 AI 仍能读到要求，需要把要求并入 `description`（本次未做，属于产品决策）。
4. **附件数量上限**（单题 20）是我按经验补的护栏，配置项 `sss.upload.topic-attachment.max-per-topic` 可调；如需“不限”可调大。
5. 前端一文件一请求，网络差时并发 3 个之外的请求是串行的（组件逐条 `startUpload`），大批量（接近 20 个）上传总耗时较长，但换来逐文件可控。
6. 上一版遗留的 `POST /files` 兼容入口保留，但已改为走三重校验；若外部脚本按旧逻辑上传 `.zip` 会开始收到 400。
7. 状态删除后**没有**「下架题目」的能力：题目一旦创建，学生即可见；如需下架只能删除（有选题时删不掉，需先退选）。这是彻底取消状态方案的固有代价，请确认可接受。
