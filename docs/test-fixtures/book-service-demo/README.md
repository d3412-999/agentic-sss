# book-service-demo（虚构测试项目）

这是为验证「源码上传 → 解析 → AI 语义评审 → 成绩重算」链路而人工编写的示例项目，
**不是真实学生的课程设计作品**，仅用于系统测试与留痕。

- 分层：`Book`（实体）/ `BookRepository`（仓储）/ `BookService`（业务）/ `Main`（入口）
- 构建方式：Maven（`pom.xml`）
- 包名：`com.example.book`
- 用途：上传到系统后，验证 `javaFileCount`、`codeLines`、`code_analysis.score` 与成绩重算是否正常。

对应的测试记录见 `docs/test-record-source-eval.md`。
