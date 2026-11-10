"""源码语义评审提示词。"""

from app.schemas.code import CodeReviewRequest

SYSTEM_PROMPT = """你是一名 Java 课程设计的代码评审教师。静态分析工具已经给出规范与缺陷问题，你只负责两项语义评价：

- DESIGN 设计合理性：分层、职责划分、命名、设计模式的使用是否合理；
- DOC 注释与文档：类与方法注释、关键逻辑说明是否充分。

要求：
1. 结合真实代码片段评价，不要空泛；
2. 不要重复静态工具已经指出的格式问题；
3. 给出 2~5 条可执行的改进建议；
4. 如果源码为空、没有 Java 文件、只有少量无关内容、或明显不是可运行的项目，design_score 与 doc_score 必须为 0~20，并在 summary 中明确说明「未提供有效 Java 源码」；
5. 不要因为“学生已经提交”就默认给 60 分；分数必须由代码质量决定；
6. 只输出 JSON，不要输出 Markdown 代码块或额外说明。

输出 JSON 结构：
{
  "design_score": 85,
  "design_comment": "评语",
  "doc_score": 78,
  "doc_comment": "评语",
  "suggestions": ["建议1", "建议2"],
  "summary": "总体评价"
}
"""


def build_user_prompt(request: CodeReviewRequest) -> str:
    """拼接源码评审的用户提示词。"""
    file_lines = [f"### {java_file.path}\n{java_file.content}" for java_file in request.java_files]
    issue_lines = [
        f"- [{issue.tool}] {issue.rule} {issue.file}:{issue.line or '?'} {issue.message}"
        for issue in request.issues[:30]
    ]
    metrics_text = "，".join(f"{key}={value}" for key, value in request.metrics.items()) or "未提供"

    return f"""源码评审对象：{request.submission_id}

【度量指标】{metrics_text}

【静态分析问题】
{chr(10).join(issue_lines) or '（无）'}

【源码文件】
{chr(10).join(file_lines) or '（未提供）'}

请只评价设计合理性与注释文档，并给出改进建议。"""
