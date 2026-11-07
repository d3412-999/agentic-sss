"""Mock 模式下的确定性返回数据。

用于本地联调、接口冒烟测试和前端假数据演示；
真实环境请设置 LLM_PROVIDER=openai 并配置 API Key。
"""

from typing import Any

from app.schemas.code import CodeReviewRequest
from app.schemas.common import DimensionScore
from app.schemas.defense import DefenseEvaluateRequest, DefenseQuestionRequest
from app.schemas.report import ReportEvaluateRequest
from app.services.scoring import clamp_score, grade_of, weighted_total


def mock_defense_questions(request: DefenseQuestionRequest) -> dict[str, Any]:
    """生成一组模板化的答辩题（不调用大模型）。"""
    count = max(1, min(request.config.question_count, 12))
    topic = request.topics[0] if request.topics else None
    topic_title = topic.title if topic else "课程设计题目"
    file_path = request.source.java_files[0].path if request.source.java_files else "src/main/java/..."
    section_title = request.report.sections[0].title if request.report.sections else "系统设计"
    issue = request.source.issues[0] if request.source.issues else None

    question_types = ["DESIGN", "CODE", "REPORT", "CONCEPT", "EXTENSION"]
    questions: list[dict[str, Any]] = []

    for index in range(count):
        question_type = question_types[index % len(question_types)]
        if question_type == "DESIGN":
            text = f"你在「{topic_title}」中是如何划分模块职责的？如果某个类同时承担数据访问与业务校验，你会如何重构？"
            refs = [{"type": "FILE", "path": file_path, "line": 1}, {"type": "REPORT_SECTION", "section": section_title}]
            points = ["能说明分层与职责边界", "能指出具体类或方法", "能给出可执行的重构方案"]
        elif question_type == "CODE":
            if issue:
                text = f"静态分析在 {issue.file}:{issue.line or 1} 发现「{issue.rule}」，请说明这个问题的成因以及你的修改方案。"
                refs = [{"type": "FILE", "path": issue.file, "line": issue.line or 1}]
                points = ["能定位到具体代码", "能说明问题成因", "能给出修改方案"]
            else:
                text = f"请结合 {file_path} 中的实现，说明你如何处理异常与边界条件。"
                refs = [{"type": "FILE", "path": file_path, "line": 1}]
                points = ["能举出具体代码", "能说明异常处理策略", "能说明边界条件"]
        elif question_type == "REPORT":
            text = f"报告「{section_title}」中对系统设计的描述，与最终代码是否一致？请举例说明差异或对应关系。"
            refs = [{"type": "REPORT_SECTION", "section": section_title}]
            points = ["能引用报告具体内容", "能对应到代码实现", "能说明差异原因"]
        elif question_type == "CONCEPT":
            text = f"在「{topic_title}」的实现中，你使用了哪些面向对象设计原则或设计模式？请结合代码说明。"
            refs = [{"type": "FILE", "path": file_path, "line": 1}]
            points = ["能说出原则或模式名称", "能结合具体类说明", "能说明解决了什么问题"]
        else:
            text = f"如果要在「{topic_title}」中增加并发借阅/高并发访问能力，你会如何调整现有的类结构与数据库设计？"
            refs = [{"type": "REPORT_SECTION", "section": section_title}]
            points = ["能指出当前并发风险", "能给出类结构调整方案", "能说明数据库层面的配合"]

        questions.append(
            {
                "type": question_type,
                "difficulty": "MEDIUM" if index % 2 == 0 else "HARD",
                "question": text,
                "expected_points": points,
                "reference_answer": "参考要点：" + "；".join(points),
                "source_refs": refs,
                "max_answer_seconds": 180,
            }
        )

    return {"questions": questions}


def mock_defense_evaluation(request: DefenseEvaluateRequest) -> dict[str, Any]:
    """按回答长度与覆盖情况给出确定性的答辩评分。"""
    transcript_map = {item.question_id: item for item in request.transcript}
    question_scores: list[dict[str, Any]] = []
    flags: list[dict[str, Any]] = []

    for question in request.questions:
        item = transcript_map.get(question.id)
        text = item.text.strip() if item else ""
        if not text:
            score = 0.0
            comment = "未检测到有效作答"
            flags.append(
                {
                    "type": "NO_ANSWER",
                    "message": f"题目 {question.id} 未检测到有效作答",
                    "confidence": 1.0,
                }
            )
        else:
            score = clamp_score(55 + min(len(text) / 12, 35))
            comment = "回答完整，能结合项目说明" if score >= 80 else "回答偏短，论据不足"
        question_scores.append({"question_id": question.id, "score": score, "comment": comment})

    answered = [item for item in question_scores if item["score"] > 0]
    accuracy = sum(item["score"] for item in answered) / len(answered) if answered else 0
    avg_length = (
        sum(len(transcript_map[item["question_id"]].text) for item in answered) / len(answered)
        if answered
        else 0
    )

    dimensions = [
        {"key": "ACCURACY", "name": "内容准确性", "weight": 40, "score": clamp_score(accuracy), "comment": "依据逐题得分折算"},
        {"key": "DEPTH", "name": "理解深度", "weight": 25, "score": clamp_score(accuracy - 3), "comment": "是否说明设计取舍"},
        {"key": "EXPRESSION", "name": "表达流畅度", "weight": 20, "score": clamp_score(60 + min(avg_length / 10, 30)), "comment": "依据回答长度估计"},
        {"key": "INTERACTION", "name": "应变与追问表现", "weight": 15, "score": clamp_score(80), "comment": "Mock 模式固定分"},
    ]
    total = weighted_total([DimensionScore(**item) for item in dimensions])
    answered_count = len(answered)

    return {
        "summary": f"共 {len(request.questions)} 题，有效作答 {answered_count} 题。"
        if request.questions
        else "未提供题目，无法评分。",
        "dimensions": dimensions,
        "question_scores": question_scores,
        "flags": flags,
        "score": total,
        "grade": grade_of(total),
    }


def mock_report_evaluation(request: ReportEvaluateRequest) -> dict[str, Any]:
    """按报告章节数量给出确定性的报告评分。"""
    section_count = len(request.report.sections)
    page_count = request.report.page_count or 0
    structure = clamp_score(70 + min(section_count * 3, 20))
    testing = clamp_score(65 + (5 if any("测试" in section.title for section in request.report.sections) else 0))

    dimensions = [
        {"key": "STRUCTURE", "name": "结构完整性", "weight": 15, "score": structure, "comment": f"共 {section_count} 个章节"},
        {"key": "REQUIREMENT", "name": "需求分析", "weight": 20, "score": 78, "comment": "Mock 模式估计"},
        {"key": "DESIGN", "name": "系统设计", "weight": 25, "score": 80, "comment": "Mock 模式估计"},
        {"key": "IMPLEMENTATION", "name": "实现说明", "weight": 20, "score": 76, "comment": "Mock 模式估计"},
        {"key": "TESTING", "name": "测试与验证", "weight": 10, "score": testing, "comment": "检查测试章节"},
        {"key": "FORMAT", "name": "格式规范", "weight": 10, "score": 82, "comment": "Mock 模式估计"},
    ]
    total = weighted_total([DimensionScore(**item) for item in dimensions])

    problems = []
    if section_count < 5:
        problems.append({"section": "整体结构", "message": "章节偏少，缺少测试或总结章节", "suggestion": "补充测试与总结章节"})
    if page_count and page_count < 15:
        problems.append({"section": "篇幅", "message": f"报告仅 {page_count} 页，内容可能不够充实", "suggestion": "补充设计过程与测试记录"})

    return {
        "summary": f"报告共 {section_count} 个章节、{page_count or '未知'} 页，结构与内容基本完整。",
        "dimensions": dimensions,
        "highlights": ["章节层次清晰", "设计部分有图示说明"] if section_count else [],
        "problems": problems,
        "score": total,
        "grade": grade_of(total),
    }


def mock_code_review(request: CodeReviewRequest) -> dict[str, Any]:
    """按静态问题数量给出确定性的源码语义评分。"""
    issue_count = len(request.issues)
    design = clamp_score(85 - min(issue_count * 2, 20))
    doc = clamp_score(78 - min(issue_count, 10))

    return {
        "design_score": design,
        "design_comment": "分层基本清晰，部分类职责偏重",
        "doc_score": doc,
        "doc_comment": "公共方法注释不足",
        "suggestions": [
            "为公共类与方法补充 Javadoc",
            "拆分职责过多的 Service 类",
            "为关键业务逻辑补充异常处理说明",
        ],
        "summary": f"共检查 {len(request.java_files)} 个源码文件、{issue_count} 条静态问题。",
    }
