# 批量添加学生报「服务端异常」：定位、修复与验证

> 结论先说：**不是业务代码抛异常，而是前端调用的导入接口在后端进程里根本不存在**——
> 运行中的后端进程是「加导入功能之前」的旧构建，请求打到未注册路径后 Spring 抛
> `NoResourceFoundException`，被全局兜底映射成了 `50000 服务端异常`。
> 修了两处：① 用最新代码重启后端（真因）；② 把「路由未匹配 / 方法不支持」从 500 改成 404 / 405
> 并带上方法与路径，让这类「版本不一致」不再伪装成服务崩溃（根因级护栏）。

---

## 1. 复现记录

### 1.1 等价请求（curl，与弹窗「下一步」完全同路径）

```
POST /api/v1/teacher/offerings/9201/students/import/parse-text
Authorization: Bearer <T2019008 的 accessToken>
Content-Type: application/json

{"text":"2023010104 赵六\n2023010105 钱七"}
```

**复现结果（改造前）**

```
HTTP/1.1 500
X-Trace-Id: 85e840fc32704a32b81527ff6b4dd64e
{"code":50000,"message":"服务端异常","data":null,"traceId":"85e840fc32704a32b81527ff6b4dd64e"}
```

同一控制器上的**所有**端点（`/import/policy`、`/import/template`、`/import/parse-file`、
`/import/confirm`）都是 500；`GET /api/v1/meta/student-import-policy` 也是 500。

### 1.2 后端堆栈（按 traceId 从日志取原文）

```
ERROR o.s.s.common.GlobalExceptionHandler : Unhandled exception, traceId=85e840fc32704a32b81527ff6b4dd64e

org.springframework.web.servlet.resource.NoResourceFoundException:
    No static resource api/v1/teacher/offerings/9201/students/import/parse-text.
    at org.springframework.web.servlet.resource.ResourceHttpRequestHandler.handleRequest(ResourceHttpRequestHandler.java:585)
    at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)
    ...
```

关键行：`ResourceHttpRequestHandler.java:585` —— 请求根本没进 `@RestController`，
而是被当成静态资源去磁盘找文件，找不到就抛 `NoResourceFoundException`。

### 1.3 为什么「什么都没改就好了」

| 证据 | 说明 |
|---|---|
| `TeacherStudentRosterController.java` mtime = 23:27 | 导入功能的源码是 23:27 落盘的 |
| 运行中的后端进程启动时间 = 23:23 | 进程比源码**早 4 分钟**，class 里没有这些映射 |
| 重启后同一请求返回 200 + 两行预览 | 证明代码本身没问题 |
| 日志里 23:43 还有 `No static resource api/v1/meta/student-import-policy` | 用户当时在页面上点的正是这一版前端 + 旧后端 |

**这是「前端已更新、后端没重启」的典型现场**，只是被 500 兜底掩盖成了业务异常。

---

## 2. 根因结论与证据

### 2.1 直接原因

`GlobalExceptionHandler`（`common/GlobalExceptionHandler.java:100`）只有一个
`@ExceptionHandler(Exception.class)` 兜底，Spring 6.1 的 `NoResourceFoundException`
落进去后被映射为 `ErrorCode.INTERNAL_ERROR`（50000，HTTP 500）。
→ 「路径不存在」被表述成「服务端异常」，message 与 traceId 都指向不了真正原因。

### 2.2 为什么不是任务里列的那些怀疑项

逐条用证据排除（不是「可能」「疑似」）：

| 怀疑项 | 实测证据 | 结论 |
|---|---|---|
| 学号已存在导致唯一索引冲突 | 用两条**从未出现**的学号（2023030001/0002）复现，同样 500 | 排除 |
| DTO 与前端请求体不一致 | 重启后**同一份**请求体返回 200，两行 `state=NEW` | 排除 |
| 必填列未赋值 | 重启后成功建号，`must_change_password=1 / token_version=0 / deleted=0` 均正常 | 排除 |
| 主键回填 / 事务配置 | 重启后 50 条分块提交成功，`created=50`，耗时 0.53s | 排除 |
| 两个 Service 两段事务 | 账号与名单关系在同一 `TransactionTemplate` 内，失败整批回滚后逐行重试 | 排除 |
| BCrypt 在循环里抛异常 | 重启后 50 条各自哈希成功（并行），全员可登录 | 排除 |
| 教师归属 / Long 转换 | 他人教学班返回 40301「无权访问该开课」，非法 id 返回 40001 | 排除（4xx 正常） |
| 审计日志写 null | 成功后 `audit_log` 里 `STUDENT_ADD` 记录数与新建人数一致 | 排除 |

### 2.3 异常发生在哪个阶段

**「解析预览」阶段**（`parse-text` / `parse-file`）——`confirm` 也一起 500，
因为整个控制器都没注册。两阶段的设计本身没问题，不需要改业务流程。

---

## 3. 改动清单与 diff

### 3.1 `common/GlobalExceptionHandler.java`（根因级护栏）

```diff
+    /**
+     * 路由没匹配上（路径写错、前后端版本不一致、后端没重启）。
+     * Spring 6.1 起未匹配路径抛 NoResourceFoundException，若不单独接住会被 Exception 兜成 50000。
+     */
+    @ExceptionHandler(NoResourceFoundException.class)
+    public ResponseEntity<Result<Object>> handleNoResourceFound(NoResourceFoundException ex) {
+        String path = ex.getResourcePath() == null ? "" : "/" + ex.getResourcePath().replaceFirst("^/+", "");
+        String method = ex.getHttpMethod() == null ? "" : ex.getHttpMethod().name();
+        log.warn("No handler mapped, traceId={}, {} {}", Result.currentTraceId(), method, path);
+        Result<Object> body = Result.fail(ErrorCode.NOT_FOUND,
+                "接口不存在：" + method + " " + path,
+                Map.of("hint", "前端与后端版本可能不一致：确认后端已用最新代码重启，且路径拼写正确"));
+        return ResponseEntity.status(ErrorCode.NOT_FOUND.getHttpStatus()).body(body);
+    }
+
+    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
+    public ResponseEntity<Result<Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
+        ... 40501「接口不支持 GET 方法」+ data.supportedMethods
+    }
```

`common/ErrorCode.java` 新增一个错误码：

```diff
     NOT_FOUND(40401, HttpStatus.NOT_FOUND, "资源不存在"),
+    METHOD_NOT_ALLOWED(40501, HttpStatus.METHOD_NOT_ALLOWED, "接口不支持该请求方法"),
```

### 3.2 `vue/src/stores/toast.js`（错误提示带 message + traceId）

```diff
-export function toastError(err, fallback = '操作失败') {
-  const msg = err?.message || fallback
-  const trace = err?.traceId ? `（traceId: ${String(err.traceId).slice(0, 12)}）` : ''
-  toast.error(fallback, msg + trace)
+export function toastError(err, fallback = '操作失败') {
+  toast.error(fallback, errorText(err, fallback))
   return err
 }
+
+/** 行内错误文案：字段级错误 > 后端 message > 兜底文案，末尾附**完整** traceId（截断后日志搜不到）。 */
+export function errorText(err, fallback = '操作失败') { ... }
```

### 3.3 `vue/src/components/offering/StudentImportModal.vue`

```diff
-    parseError.value = err?.message || '解析失败'
+    parseError.value = errorText(err, '解析失败')          // 「服务端异常」→「…（traceId: xxx）」
-    parseError.value = err?.message || '解析失败，请确认文件格式与表头'
+    parseError.value = errorText(err, '解析失败，请确认文件格式与表头')
-    singleError.value = err?.message || '加入失败'
+    singleError.value = errorText(err, '加入失败')          // 40001 时展示字段级中文原因
```

### 3.4 学生初始密码改为「密码 = 学号」（按你确认的策略）

```diff
  config/SssProperties.java
+        /** 初始密码策略：STUDENT_NO（密码=学号）或 FIXED（统一默认密码）。 */
+        private PasswordMode defaultPasswordMode = PasswordMode.STUDENT_NO;
+    public enum PasswordMode { STUDENT_NO, FIXED }

  service/OfferingStudentService.java
-    public String hashDefaultPassword() { return passwordEncoder.encode(defaultPassword()); }
+    public String initialPassword(String studentNo) {
+        if (mode == FIXED) return properties.getStudent().getDefaultPassword();
+        return studentNo;                      // 默认：密码即学号
+    }
+    public Map<String, String> hashInitialPasswords(Collection<String> studentNos) {
+        // 密码逐人不同 → 去重后 **并行** 算哈希，50 行 0.53s（串行约 3s、500 行约 30s）
+    }

  service/StudentRosterImportService.java
-    String passwordHash = studentService.hashDefaultPassword();   // 整批复用一个哈希
+    Map<String, String> passwordHashes = studentService.hashInitialPasswords(...);  // 学号 → 哈希
```

`application.yaml` 增加 `sss.student.default-password-mode: ${STUDENT_PASSWORD_MODE:STUDENT_NO}`
（`FIXED` 时回退到 `default-password`，演示环境可用）。
前端三处「统一默认密码」文案同步改为「初始密码是学号」。

**没有改动的部分**：两阶段预览/确认流程、逐行事务策略、导入限额、单条添加、移除学生、
发布教学班、审计日志、`@Idempotent` 幂等键。也没有删除任何学生/关联数据来「修复」。

---

## 4. 接口契约

| 方法 | 路径 | 入参 | 出参 | 错误码 |
|---|---|---|---|---|
| GET | `/meta/student-import-policy` | — | `{allowedExtensions,maxFileBytes,maxRows}` | — |
| POST | `/teacher/offerings/{offeringId}/students/import/parse-text` | `{text}` | `PreviewVO` | 40001（空/超限）、40301、40401 |
| POST | `.../import/parse-file` | multipart `file` | `PreviewVO` | 40002（格式/损坏/超 2MB）、40001（超条数） |
| POST | `.../import/confirm` | `{rows:[{studentNo,name?,email?,rowNo}]}` | `ConfirmVO` | 40001、40301、40902（幂等重复） |
| POST | `.../batch-remove` | `{studentIds[]}` | `{total,removed,failed,rows[]}` | 40001、40301 |
| GET | `.../import/template` | — | xlsx 二进制 | 40301 |

**PreviewVO**（逐行状态，HTTP 200——行级问题不是 4xx，这是有意为之）

```json
{"rows":[{"rowNo":3,"studentNo":"2023010104","name":"赵六","state":"NEW","message":"将创建账号并加入本班"}],
 "total":2,"importable":2,"skipped":0,"invalid":0,"headerDetected":false,"hint":null,"source":"TEXT"}
```

`state`：`NEW`（建号+入班）/ `LINK`（复用账号入班）/ `IN_ROSTER`（已在本班，跳过）/ `INVALID`（该行有错）。

**ConfirmVO**：`{total,created,linked,skipped,failed,rows:[{rowNo,studentNo,name,result,message}]}`。

**错误码语义**

| 场景 | HTTP | code | message 示例 |
|---|---|---|---|
| 学号在批内重复 | 200（预览逐行） | — | `与第 3 行学号重复` |
| 学号已存在（他人账号） | 200（预览逐行 `LINK`） | — | `已有账号，将直接加入本班` |
| 已在本班 | 200（预览 `IN_ROSTER` / 确认 `SKIPPED`） | — | `已在本班，将跳过` |
| 学号被教师账号占用 | 200（预览 `INVALID`） | — | `该学号已被教师或管理员账号占用` |
| 空文本 / 空 rows / 超 500 条 | 400 | 40001 | `没有可导入的数据` |
| 文件格式不支持 / 损坏 / 超 2MB | 400 | 40002 | `不支持旧版 .doc，请另存为 .docx 或 .xlsx` |
| 非本人教学班 | 403 | 40301 | `无权访问该开课` |
| 同 Idempotency-Key 重复提交 | 409 | 40902 | `相同请求已受理，请勿重复提交` |
| **路由不存在（本次 bug）** | **404** | **40401** | `接口不存在：POST /api/v1/…/parse-text` + `data.hint` |
| 方法不对 | 405 | 40501 | `接口不支持 GET 方法` + `data.supportedMethods` |

> 行级问题走「预览逐行标注 + 确认逐行结果」，不是 4xx：一次导入里只要有一行学号重复，
> 就把整批 200 行判 4xx 会让老师没法继续。这与前端提示一致（表格逐行展示 state/message）。
> 只有**请求级**问题（空、超限、越权、幂等重复、路由不存在）才是 4xx。

---

## 5. 验证记录（全部为真实请求，非模拟）

| 用例 | 结果 |
|---|---|
| 截图两行 `2023010104 赵六 / 2023010105 钱七` 解析 | 200，两行 `NEW`，`importable=2` |
| 同上确认导入 | 200，`created=2`；「完成」后列表可见，`sys_user` 与 `offering_student` 各 +2 |
| 混合文本（表头 + 已在本班 + 批内重号 + 缺姓名 + 中文逗号 + 教师学号） | 200：`IN_ROSTER`/`NEW`/`与第 3 行学号重复`/`姓名为空`/`NEW`/`该学号已被教师或管理员账号占用` |
| 同 Idempotency-Key 再提交 | **409** `40902 相同请求已受理，请勿重复提交`；库内无新增 |
| 换 key 再提交同一批 | 200，`skipped=2`；库内账号与关联各仍为 1 条 |
| 50 条批量导入（首次） | 200，`created=50`，耗时 0.53s；库内 `sys_user=50`、`offering_student=50`、`audit_log STUDENT_ADD=50` |
| 50 条重复提交 | 200，`skipped=50`，库内数量不变 |
| xlsx 文件导入（样例模板 + 自造 3 行表格） | 200，表头自动识别，3 行状态正确 |
| csv（UTF-8 BOM）导入 | 200，2 行 `NEW` |
| docx（两列表格）导入 | 200，2 行 `NEW` |
| 伪造 .xlsx（非表格内容） | **400** `40002 表格解析失败，请确认文件未损坏…` |
| 旧版 .doc | **400** `40002 不支持旧版 .doc，请另存为 .docx 或 .xlsx` |
| 2.2MB csv | **400** `40002 文件超过 2 MB 上限，请精简后重试` |
| 空文本 / 空 rows | 400 `40001` + `errors[0].message` 中文原因 |
| 非本人教学班（T2019009 操作 9201） | 403 `40301 无权访问该开课` |
| **不存在的路径（本次 bug 的护栏）** | **404** `40401 接口不存在：POST /api/v1/…/parse-txt` + `hint`（改造前是 500） |
| GET 打 POST 接口 | 405 `40501 接口不支持 GET 方法` + `supportedMethods:["POST"]` |
| 初始密码 = 学号 | 新导入的 2023030001/2023030002 用「学号」登录 200，`must_change_password=1` |
| 重置密码后再登录 | 2023010104 重置后用「2023010104」登录 200，用旧密码 123456 登录 401 `40102` |
| 单条添加 / 移除 | 200 / 200，名单数回到 7 |
| 创建教学班 → 建题 → 发布 | 发布返回 200 `status=PUBLISHED`（缺清单/时间线时是 422 + blockers，语义正确） |
| `./gradlew test` | BUILD SUCCESSFUL，**71 用例 0 失败**（新增 `GlobalExceptionHandlerTest` 3 例） |
| `npm run build` | ✓ built |

测试数据已清理：压测的 102 个账号与关联、发布回归用的教学班与题目全部删除；
`offering_student(9201)` 回到 7 人（5 个 seed + 2023010104/2023010105），`sys_user` 学生 7 个。

---

## 6. 回归风险与未覆盖项

1. **这次真正的「修复」是重启后端**。代码改的是「同类问题不再伪装成 500」。
   如果你本地再遇到「接口不存在」，先确认 `gradlew bootRun` 是**最新**进程——
   新加的 404 提示已经把这句话写进 `data.hint`。
2. **初始密码改成学号是不可逆的语义变化**：老账号（seed 的 5 个学生）仍是旧密码，
   教师端「重置密码」现在会重置成学号；`sss.student.default-password-mode=FIXED` 可回退，
   回退后新建账号恢复统一密码。
3. 「密码=学号」让 BCrypt 无法整批复用，500 人导入的哈希成本从 1 次变成 500 次；
   已用并行去重压到 0.53s/50 条，但**低配机器**上会慢，必要时调小 `import-max-rows`。
4. 预览里的重复学号/已存在账号**不是 4xx**（见第 4 节说明）。如果你要「整批 4xx 拒绝」，
   需要改前端交互（先给一个「有 N 行异常，是否继续导入」的二次确认），本次没有动。
5. `NoResourceFoundException` 现在返回 404 JSON；后端不托管前端静态资源，因此不影响页面访问。
6. 未覆盖：Excel 的 `.xls`（老二进制格式）只做了扩展名与 POI 分支，没有真实样本文件验证；
   并发同一批（两个浏览器同时点确认）只验证了幂等键路径，没做真实双端并发压测。
