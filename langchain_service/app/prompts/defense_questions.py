"""答辩题目生成提示词。"""

from app.schemas.defense import DefenseQuestionRequest

SYSTEM_PROMPT = """你是一名《面向对象程序设计》课程设计的答辩教师，负责根据学生选择的题目、提交的源码和课程设计报告，生成有针对性的答辩题目。

要求：
1. 题目必须紧扣学生选择的题目与要求，不得超纲；
2. 优先考察学生自己写过的代码：设计取舍、实现细节、异常与并发、设计模式的使用；
3. 结合报告内容提问，检查报告与实现是否一致；
4. 题目要能区分“真正做过”和“只写了报告”的学生；
5. 每道题给出评分要点（expected_points）与参考答案（reference_answer）；
6. 引用源码时给出真实存在的文件路径与行号；引用报告时给出章节名称；
7. 只输出 JSON，不要输出 Markdown 代码块或额外说明。

输出 JSON 结构：
{
  "questions": [
    {
      "type": "DESIGN|CODE|REPORT|CONCEPT|EXTENSION",
      "difficulty": "EASY|MEDIUM|HARD",
      "question": "题干",
      "expected_points": ["要点1", "要点2"],
      "reference_answer": "参考答案",
      "source_refs": [
        {"type": "FILE", "path": "src/main/java/.../BookService.java", "line": 45},
        {"type": "REPORT_SECTION", "section": "4.2 业务逻辑设计"}
      ],
      "max_answer_seconds": 180
    }
  ]
}
"""


def build_user_prompt(
    request: DefenseQuestionRequest,
    code_context: list[str],
    report_context: list[str],
) -> str:
    """拼接答辩出题的用户提示词。"""
    topic_lines = []
    for topic in request.topics:
        topic_lines.append(
            f"- 题目：{topic.title}（难度：{topic.difficulty}）\n"
            f"  要求：{topic.requirements or '未填写'}"
        )

    file_lines = []
    for java_file in request.source.java_files:
        file_lines.append(f"### {java_file.path}\n{java_file.content}")

    section_lines = []
    for section in request.report.sections:
        section_lines.append(f"### {section.title}\n{section.content}")

    issue_lines = [
        f"- [{issue.tool}] {issue.rule} {issue.file}:{issue.line or '?'} {issue.message}"
        for issue in request.source.issues[:20]
    ]

    focus = "、".join(request.config.focus) if request.config.focus else "设计与代码细节并重"
    retrieval_lines = [*code_context, *report_context]

    return f"""请为以下学生生成 {request.config.question_count} 道答辩题。

【课程】{request.offering.course_name or '面向对象程序设计课程设计'}（{request.offering.semester or '本学期'}）
【考察重点】{focus}
【语言】{request.config.language}

【学生选择的题目】
{chr(10).join(topic_lines) or '（未提供）'}

【源码文件】
{chr(10).join(file_lines) or '（未提供）'}

【静态分析问题】
{chr(10).join(issue_lines) or '（无）'}

【课程设计报告章节】
{chr(10).join(section_lines) or '（未提供）'}

【检索到的相关片段】
{chr(10).join(retrieval_lines) or '（无）'}

请覆盖设计决策、代码细节、报告内容、课程知识点、扩展思考等类型，输出 JSON。"""
