"""答辩评分提示词。"""

from app.schemas.defense import DefenseEvaluateRequest

SYSTEM_PROMPT = """你是一名严谨的课程答辩评分教师。请根据答辩转写稿，对照每道题的评分要点，给出客观、可解释的评分。

评分维度与权重（合计 100）：
- ACCURACY 内容准确性 40%
- DEPTH 理解深度 25%
- EXPRESSION 表达流畅度 20%
- INTERACTION 应变与追问表现 15%

要求：
1. 只依据转写内容评分，不要臆测学生没说过的话；
2. 转写为空或明显答非所问时，如实给低分并标记；
3. 每道题给出 question_id、分数与简短评语；
4. 如发现疑似照读、答非所问、回答与题目无关等情况，写入 flags；
5. 只输出 JSON，不要输出 Markdown 代码块或额外说明。

输出 JSON 结构：
{
  "summary": "总体评价",
  "dimensions": [
    {"key": "ACCURACY", "name": "内容准确性", "weight": 40, "score": 85, "comment": "评语"}
  ],
  "question_scores": [
    {"question_id": "dq_001", "score": 88, "comment": "评语"}
  ],
  "flags": [
    {"type": "SUSPECTED_READING", "message": "说明", "confidence": 0.7}
  ]
}
"""


def build_user_prompt(request: DefenseEvaluateRequest) -> str:
    """拼接答辩评分的用户提示词。"""
    question_lines = []
    for question in request.questions:
        points = "；".join(question.expected_points) or "未提供"
        question_lines.append(f"- {question.id}：{question.question}\n  评分要点：{points}")

    transcript_map = {item.question_id: item for item in request.transcript}
    answer_lines = []
    for question in request.questions:
        item = transcript_map.get(question.id)
        text = item.text if item and item.text.strip() else "（未作答）"
        duration = item.duration_ms if item else 0
        answer_lines.append(f"### {question.id}（时长 {duration} ms）\n{text}")

    return f"""答辩评分对象：{request.defense_id}

【题目与评分要点】
{chr(10).join(question_lines) or '（未提供）'}

【学生作答转写稿】
{chr(10).join(answer_lines)}

请严格按评分维度打分，并给出逐题得分与异常标记。"""
