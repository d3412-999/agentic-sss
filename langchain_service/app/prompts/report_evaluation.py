"""课程设计报告评审提示词。"""

from app.schemas.report import ReportEvaluateRequest

SYSTEM_PROMPT = """你是一名《面向对象程序设计》课程设计报告的评审教师。请根据题目要求与报告内容，给出结构化评审结果。

评分维度与权重（合计 100）：
- STRUCTURE 结构完整性 15%
- REQUIREMENT 需求分析 20%
- DESIGN 系统设计 25%
- IMPLEMENTATION 实现说明 20%
- TESTING 测试与验证 10%
- FORMAT 格式规范 10%

要求：
1. 只依据报告内容评分，不要虚构报告中没有的内容；
2. 指出具体章节中的问题，并给出可执行的改进建议；
3. 评价设计图、类图、数据库设计是否与文字描述一致；
4. 只输出 JSON，不要输出 Markdown 代码块或额外说明。

输出 JSON 结构：
{
  "summary": "总体评价",
  "dimensions": [
    {"key": "STRUCTURE", "name": "结构完整性", "weight": 15, "score": 85, "comment": "评语"}
  ],
  "highlights": ["亮点1", "亮点2"],
  "problems": [
    {"section": "5.2 测试", "message": "问题描述", "suggestion": "改进建议"}
  ]
}
"""


def build_user_prompt(request: ReportEvaluateRequest) -> str:
    """拼接报告评审的用户提示词。"""
    topic_title = request.topic.title if request.topic else "未提供"
    topic_requirements = ""
    if request.topic:
        topic_requirements = request.topic.requirements
    if request.requirements:
        topic_requirements = f"{topic_requirements}\n{request.requirements}".strip()

    section_lines = [
        f"### {section.title}\n{section.content}" for section in request.report.sections
    ]

    return f"""报告评审对象：{request.submission_id}
【题目】{topic_title}
【题目要求】{topic_requirements or '未提供'}
【页数】{request.report.page_count or '未知'}

【报告内容】
{chr(10).join(section_lines) or '（未提供）'}

请按六个维度评分，并给出亮点、问题与改进建议。"""
