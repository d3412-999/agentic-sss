# 源码评审链路测试记录（虚构 Java 项目）

> **这是一次人工构造的测试，不是真实学生提交。**
> 测试数据、上传文件、分析结果与成绩记录均保留在系统/仓库中，用于追溯“源码 → 解析 → AI 评审 → 成绩重算”整条链路。

## 1. 测试目的

验证以下行为是否正常：

1. 含 `.java` 源码的 ZIP 能正确统计文件数、代码行数和构建方式；
2. AI 语义评审能基于真实 Java 代码给出分数；
3. 成绩发布前会使用最新源码评分重算；
4. 与“空源码包（`javaFileCount=0`）”被拒绝的行为形成对照。

## 2. 测试数据

| 项 | 内容 |
|---|---|
| 项目名 | `book-service-demo` |
| 来源 | 人工虚构，仅用于测试 |
| 结构 | `Book` / `BookRepository` / `BookService` / `Main`，包名 `com.example.book` |
| 构建方式 | Maven（`pom.xml`） |
| 源码位置 | [`docs/test-fixtures/book-service-demo/`](test-fixtures/book-service-demo/) |
| 上传包 | [`docs/test-fixtures/book-service-demo.zip`](test-fixtures/book-service-demo.zip) |
| SHA-256 | `E449042E0B5B526BD15D36691AFB09B42B11F8E0CAF89D9EC3075E14DD53F41C` |
| 大小 | 5,155 字节 |

## 3. 上传与解析

| 项 | 值 |
|---|---|
| 测试账号 | 学生 `2023010101`（张三） |
| 选题 ID | `2120122370953179138` |
| 提交物 ID | `2120123514165260290`（SOURCE） |
| 版本 | `2` |
| 版本记录 ID | `2120153394875932673` |
| 文件记录 ID | `2120153394825601026` |
| 上传时间 | 2026-11-10 21:55:34 |
| 版本备注 | `AI评测验证样例：虚构 book-service-demo（2026-11-10）` |
| 解析任务 ID | `2120153394905292801` |
| 解析结果 | `VALID`，`javaFileCount=4`，`codeLines=251`，`buildTool=MAVEN`，`fileCount=10` |

## 4. AI 评审结果

| 项 | 值 |
|---|---|
| 分析记录 ID | `2120123524730712066` |
| 状态 | `SUCCEEDED` |
| 模型 | `deepseek-chat` |
| 总分 | **83.2** |
| DESIGN（设计合理性） | 82.0 |
| DOC（注释与文档） | 85.0 |
| 完成时间 | 2026-11-10 21:55:42 |

AI 总评（摘录）：

> 该项目提供了有效的 Java 源码，分层设计合理，领域对象内聚了借还状态校验，Service 层集中业务规则，整体符合课程设计要求。主要改进点在于仓储抽象层次与注释一致性：BookRepository 为具体类却声称依赖抽象，建议接口化；异常类可独立；部分方法注释可更详尽。

完整维度明细见 [`docs/test-fixtures/book-service-demo-analysis.txt`](test-fixtures/book-service-demo-analysis.txt)。

## 5. 成绩重算与发布

| 项 | 值 |
|---|---|
| 开课 ID | `9201` |
| 源码分 | **83.2** |
| 报告分 | 11.2 |
| 答辩分 | 0.0（尚未答辩） |
| 最终成绩 | **31.9** |
| 计算方式 | `83.2×35% + 11.2×25% + 0×40% = 31.9` |
| 状态 / 发布时间 | `LOCKED` / 2026-11-10 22:01:21 |

## 6. 对照：空源码包会被拒绝

同一提交物在测试前还上传过一个**不含 Java 文件**的 ZIP：

- `fileCount=2`、`javaFileCount=0`、`codeLines=0`
- 修复后重新分析会直接判 `INVALID`，任务失败：
  `源码包中未找到 Java 文件，无法评审`
- `code_analysis` 记为 `FAILED`、分数 `0.0`，不会再调用 AI，也不会有 60 分

这个无效版本仍然保留在版本历史（v1）中，作为对照证据。

## 7. 留痕位置

| 类型 | 位置 |
|---|---|
| 源码与 zip | `docs/test-fixtures/book-service-demo/`、`docs/test-fixtures/book-service-demo.zip` |
| AI 评审原始证据 | `docs/test-fixtures/book-service-demo-analysis.txt` |
| 系统数据 | MySQL：`submission`、`submission_version`、`file_object`、`code_analysis`、`async_task`、`grade` |
| 上传文件 | 服务器 `data/objects` 下对应 `file_object.object_key` |
| 本记录 | `docs/test-record-source-eval.md` |

## 8. 复核 SQL

```sql
-- 解析结果
SELECT id, version, status, java_file_count, code_lines, build_tool, parse_result
FROM submission_version
WHERE submission_id = 2120123514165260290
ORDER BY version DESC;

-- AI 评审
SELECT id, status, score, model, summary
FROM code_analysis
WHERE submission_id = 2120123514165260290;

-- 成绩
SELECT student_id, code_score, report_score, defense_score, final_score, status, published_at
FROM grade
WHERE offering_id = 9201 AND student_id = 9003;
```

## 9. 备注

- 该测试数据会改变学生 `2023010101` 的源码提交与成绩，仅用于本项目本地演示/验证；
- 若要恢复真实数据，重新上传真实源码并重新分析、发布即可；
- 测试记录与 fixture 已提交到仓库，避免后续排查时“无据可查”。
