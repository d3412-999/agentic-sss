# 教学班学生名单：批量导入改造

> 目标：把「一次只能加一个学生 + 展示一次性初始密码」换成 PTA 式的批量导入，
> 并让账号模型回到「学号即账号 + 统一默认密码 + 首次登录强制改密」。

## 1. 现状盘点（改造前）

| 项 | 位置 | 结论 |
| --- | --- | --- |
| 单条添加 | `POST /api/v1/teacher/offerings/{offeringId}/students` | 表单含学号 / 姓名 / 班级 / 邮箱，一次一人 |
| 一次性密码 | `OfferingStudentService.initialPassword()` | 取学号后 6 位（不足 6 位则随机 6 位数字），随 `StudentVO.initialPassword` 返回，前端弹窗展示一次 |
| 重置密码 | `POST .../students/{studentId}/reset-password` | 同样生成新的一次性密码并回传 |
| 账号表 | `sys_user` | `UNIQUE(username)`、`UNIQUE(student_no)`、`must_change_password`、`token_version` |
| 班级关联 | `offering_student` | `UNIQUE(offering_id, student_id)`，天然支持「一个学生多个教学班」 |
| 名单查询 | `OfferingStudentService.list()` | 关键字是「先分页再在内存过滤」，total 与实际结果对不上 |
| 迁移版本 | `V1`~`V9` | 代码里 V8（移除开课限制）、V9（精简题目）已存在，本次只能往后排 |

## 2. 交互方案

### 2.1 工具栏（学生页）

`搜索学号 / 姓名` + `状态` + `查询` ｜ `共 N 人` + **添加学生**（主按钮）+ **移除学生**（多选后可用）+ **下载名单**。

### 2.2 添加弹窗（宽弹窗，三步）

1. **输入**：`文本输入` / `电子表格` 双 Tab，默认文本；
   - 文本：格式说明 + 「复制格式示例」+ 大文本框，提示支持空格 / 制表符 / 英文逗号 / 中文逗号 / 短横线；
   - 表格：样例文件下载 + 点击选择 / 拖拽上传 + 文件名、大小、进度；
   - 弹窗左下角保留「改为单个添加」次要入口（学号 / 姓名 / 邮箱，无班级、无密码）。
2. **预览确认**：`共 N / 可导入 X / 已在本班 Y / 异常 Z`，逐行展示 行号 / 学号 / 姓名 / 状态 / 说明；
   支持「只看异常」「下载错误清单」「返回修改」。
3. **导入结果**：新建 / 加入 / 已在本班 / 失败 四格汇总 + 失败行原因清单。

### 2.3 逐行状态与错误分类

| 状态 | 含义 | 处理 |
| --- | --- | --- |
| `NEW` | 账号不存在 | 建号（默认密码、强制改密）+ 建关联 |
| `LINK` | 账号存在、不在本班 | 只建关联，**不覆盖姓名**；姓名不一致时提示库中姓名 |
| `IN_ROSTER` | 已在本班 | 跳过，不报错、不重复插入 |
| `INVALID` | 学号为空 / 格式非法 / 姓名为空或超长 / 批内重号 / 学号被教师账号占用 | 阻止提交，逐行给原因 |

提交时只发送 `NEW` + `LINK` 行；服务端会重新校验，不信任前端预览结果。

## 3. 接口契约

| 方法 | 路径 | 入参 | 出参 | 错误码 |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/meta/student-import-policy` | — | `{allowedExtensions,maxFileBytes,maxRows}` | — |
| GET | `/teacher/offerings/{id}/students/import/policy` | — | 同上 | 40301 |
| POST | `.../students/import/parse-text` | `{text}` | `PreviewVO`（只读） | 40001 |
| POST | `.../students/import/parse-file` | multipart `file` | `PreviewVO`（只读） | 40001 / 40002 |
| POST | `.../students/import/confirm` | `{rows:[{studentNo,name,email,rowNo}]}` | `ConfirmVO` | 40001 / 40301 |
| GET | `.../students/import/template` | — | xlsx 二进制 | 40301 |
| GET | `.../students/export` | — | CSV（UTF-8 BOM） | 40301 |
| POST | `.../students/batch-remove` | `{studentIds:[]}` | `{total,removed,failed,rows[]}` | 40001 |
| POST | `.../students`（变更） | `{studentNo,name,email?}` | `StudentVO`（**不再含密码**） | 40001 / 40301 |
| POST | `.../students/{sid}/reset-password`（变更） | — | 空 | 40301 / 40401 |

`PreviewVO` = `{rows:[{rowNo,studentNo,name,email,state,message}],total,importable,skipped,invalid,headerDetected,hint,source}`

`ConfirmVO` = `{total,created,linked,skipped,failed,rows:[{rowNo,studentNo,name,result,message}]}`

### 解析规则

- 分隔符优先级：制表符 → 英文/中文逗号 → 空白（含全角空格、连续空格）→ 短横线（仅当左侧是合法学号且右侧非数字）；
- 去除 BOM、首尾空白、空行；行号保留原文件行号；
- 首行识别为表头（学号 / 姓名 / 邮箱 等别名）时跳过，并回 `headerDetected=true`；
  识别不到且首行不是合法数据时回 `hint`，提示表头写法；
- Excel：数值型学号用 `BigDecimal.toPlainString()`，不会变成 `2.023010104E9`；
- Word：取第一个两列以上的表格；没有表格时按段落逐行解析；
- CSV：UTF-8（含 BOM）优先，失败回退 GBK；
- `.doc`：直接 400 提示「另存为 .docx 或 .xlsx」。

## 4. 数据库迁移

**本次不需要新增迁移。** `sys_user`（`UNIQUE(username)`、`UNIQUE(student_no)`、`must_change_password`、`token_version`）与
`offering_student`（`UNIQUE(offering_id, student_id)`）已经能表达全部需求；导入只写这两张表。

注意：仓库里 `V8__remove_offering_limits.sql`、`V9__simplify_topic.sql` 已被占用，若后续确实需要改表，
新迁移应从 **V10** 开始，且不得修改既有迁移的校验和。

## 5. 账号规则

- 登录账号 = 学号，姓名是显示名；
- 新建账号密码 = `sss.student.default-password`（默认跟随 `TEACHER_DEFAULT_PASSWORD`，即 `123456`），并置 `must_change_password=1`；
- 前端不保存、不展示、不硬编码默认密码；名单接口也不再返回密码字段；
- 教师「重置密码」= 重置回统一默认密码 + 递增 `token_version`（旧会话立即失效）；
- 首次登录由 `ForcePasswordChange.vue` 以不可关闭弹窗强制改密，改完清会话重新登录。

## 6. 关键实现

| 文件 | 作用 |
| --- | --- |
| `config/SssProperties.java` | 新增 `sss.student.*`：默认密码、文件上限、条数上限、扩展名、分批大小 |
| `service/StudentRosterParser.java` | 文本 / xlsx / xls / docx / csv 解析（纯函数，可单测） |
| `service/StudentRosterImportService.java` | 预览、确认导入、样例、导出、批量移除 |
| `service/OfferingStudentService.java` | 抽出 `enroll()` 供单条与批量复用；去掉一次性密码；关键字改到 SQL 过滤 |
| `controller/TeacherStudentRosterController.java` | 导入 / 导出 / 批量移除端点 |
| `dto/StudentImportDtos.java` | 导入预览与结果的契约 |
| `vue/.../StudentImportModal.vue` | 三步弹窗（输入 → 预览 → 结果）+ 单个添加兜底 |
| `vue/.../StudentsTab.vue` | 工具栏、多选、批量移除、下载名单 |
| `vue/.../ForcePasswordChange.vue` | 首次登录强制改密 |

### 事务与性能

- 确认导入按 `sss.student.import-chunk-size`（默认 200）分批，每批一个短事务；
- 某批抛异常时整批回滚，再退回「逐行事务」定位具体失败行，因此对外的语义仍是**逐行成功 / 失败**；
- 整批只计算一次 BCrypt（默认密码相同），否则 100 行会花掉 20 秒以上。

## 7. 验证记录（隔离库 `sss_verify`，验证后已删除）

| 场景 | 结果 |
| --- | --- |
| 文本 8 行（含表头、重复行、空姓名、制表符 / 逗号 / 中文逗号 / 短横线 / 多空格） | `total=8 importable=6 invalid=2`，原因逐行正确 |
| 确认导入 | `created=4 failed=2`，失败原因为「与第 2 行重复」「学号或姓名为空」 |
| 100 行 xlsx（学号为数值） | 全部解析正确，100 条全部创建 |
| 500 行文本导入 | `created=500 failed=0`，耗时约 6.7s，无重复插入 |
| 同一批再次导入 | `skipped=100`（已在本班），不重复建号 |
| docx 表格 / docx 纯段落 | 2 条 / 2 条，均正确 |
| GBK 编码 csv | 2 条，中文正常 |
| 表头不一致 xlsx | 200 + 逐行「学号格式不合法」+ 整批 `hint`（不是 500） |
| `.doc` 文件 | 400 / 40002「请另存为 .docx 或 .xlsx」 |
| 超 2MB 文件 | 400 / 40002「文件超过 2 MB 上限」 |
| 501 行 | 400 / 40001「单次最多导入 500 条」 |
| 名单导出 | CSV + BOM，列：学号 / 姓名 / 班级 / 状态 / 选题数 / 最近登录 |
| 批量移除 2 人 | `removed=2`，账号仍在：重新解析显示 `LINK`（已有账号）而非 `NEW` |
| 移除后学生登录 | 仍可登录（只解除班级关联） |
| 新学生登录 | `学号 + 123456` 成功，`mustChangePassword=true` |
| 重置密码 | 200，返回体为空（不再回传密码） |
| 他人教学班 | 403「无权访问该开课」 |
| 后端 | `gradlew compileJava test` 通过（含 13 个解析用例） |
| 前端 | `npm run build` 通过 |

## 8. 风险与未覆盖

1. **默认密码是弱口令**：`123456` 只在首次登录有效，但登录前存在被撞库窗口；部署时务必用 `STUDENT_DEFAULT_PASSWORD` 覆盖。
2. **强制改密只在前端拦截**：后端没有对 `must_change_password=1` 的账号做接口级封禁，直接调 API 可绕过。若需要硬约束，可在 `JwtAuthenticationFilter` 按 `LoginUser` 加白名单校验（需扩展 `LoginUser` 字段）。
3. **CSV 解析不支持引号内的换行 / 逗号**：名单场景足够，复杂 CSV 请用 xlsx。
4. **导入耗时**：500 条约 6~7 秒（逐行查询 + 审计日志），在常见网关 30s 超时内；更大的班级建议分批。
5. **导出格式是 CSV 而非 xlsx**：与成绩导出保持一致，Excel 双击可开；需要多 sheet 时再换 POI。
6. **前端未做浏览器级验收**：本次只用 `vite build` 与接口实测验证，未在真实浏览器里点完整流程（本地未起 dev server）。
7. **历史数据**：`offering_student` 里若有 `deleted=1` 的历史行，唯一索引仍会占用；导入走的是「先查后插」，遇到这种情况会返回「已在本班」而不是报错。
