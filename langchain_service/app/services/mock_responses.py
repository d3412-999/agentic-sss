"""Mock 模式下的确定性返回数据。

用于本地联调、接口冒烟测试和前端假数据演示；
真实环境请设置 LLM_PROVIDER=openai 并配置 API Key。
"""

from typing import Any

from app.schemas.code import CodeReviewRequest
from app.schemas.common import DimensionScore
from app.schemas.defense import DefenseEvaluateRequest, DefenseQuestionRequest
from app.schemas.report import ReportEvaluateRequest
from app.services.scoring import (
    REPORT_DIMENSION_SPECS,
    clamp_score,
    grade_of,
    weighted_total,
)


def mock_defense_questions(request: DefenseQuestionRequest) -> dict[str, Any]:
    """生成一组模板化的答辩题（不调用大模型）。"""
    count = max(1, min(request.config.question_count, 12))
    topic = request.topics[0] if request.topics else None
    topic_title = topic.title if topic else "课程设计题目"
    file_path = request.source.java_files[0].path if request.source.java_files else "src/main/java/..."
    section_title = request.report.sections[0].title if request.report.sections else "系统设计"
    issue = request.source.issues[0] if request.source.issues else None

    question_types = ["DESIGN", "CODE", "REPORT", "CONCEPT", "EXTENSION"]
    dimensions = request.config.dimensions or ["P1_RETELL", "P2_REASON", "P3_MODIFY"]
    difficulties = _mock_difficulty_plan(request.config.difficulty_mix, count)
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
                "difficulty": difficulties[index],
                "dimension": dimensions[index % len(dimensions)],
                "bonus": False,
                "question": text,
                "expected_points": points,
                "reference_answer": "参考要点：" + "；".join(points),
                "source_refs": refs,
                "max_answer_seconds": 180,
            }
        )

    # 加分题：恒定 1 道（按配置数量），排在最后，不计入总分
    for index in range(max(0, request.config.bonus_count)):
        points = ["能把已有能力迁移到新场景", "能说明取舍与风险"]
        questions.append(
            {
                "type": "EXTENSION",
                "difficulty": "MEDIUM",
                "dimension": "IS_BONUS",
                "bonus": True,
                "question": f"如果给「{topic_title}」再加一个你从未实现过的能力（例如离线同步或审计回放），你会怎么设计？说出最关键的两个取舍。",
                "expected_points": points,
                "reference_answer": "参考要点：" + "；".join(points),
                "source_refs": [{"type": "REPORT_SECTION", "section": section_title}],
                "max_answer_seconds": max(60, request.config.bonus_max_answer_seconds),
            }
        )

    return {"questions": questions}


def _mock_difficulty_plan(mix: dict[str, int], count: int) -> list[str]:
    """按难度配额展开成逐题难度列表；配额缺失时按 MEDIUM/HARD 交替兜底。"""
    if not mix:
        return ["MEDIUM" if index % 2 == 0 else "HARD" for index in range(count)]
    plan: list[str] = []
    for key in ("EASY", "MEDIUM", "HARD"):
        plan.extend([key] * max(0, int(mix.get(key, 0))))
    plan = (plan + ["MEDIUM"] * count)[:count]
    return plan if len(plan) == count else (plan + ["MEDIUM"] * (count - len(plan)))


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
        question_scores.append(
            {
                "question_id": question.id,
                "score": score,
                "comment": comment,
                "dimension": question.dimension,
                "evidence": text[:120],
                "passed": score >= 60,
            }
        )

    bonus_ids = {question.id for question in request.questions if question.bonus}
    # 加分题不计入总分：只影响自己那一行的展示
    answered = [
        item for item in question_scores if item["score"] > 0 and item["question_id"] not in bonus_ids
    ]
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


def mock_code_aggregate(request) -> dict[str, Any]:
    """多份源码包的汇总 mock：按各份得分加权，取平均作为总体。"""
    scores = [f.score for f in request.files if f.score is not None]
    average = sum(scores) / len(scores) if scores else 0
    degraded = sum(1 for f in request.files if f.degraded)
    return {
        "design_score": clamp_score(average),
        "design_comment": f"共 {len(request.files)} 份源码包，整体设计水平接近各份平均",
        "doc_score": clamp_score(max(0, average - 4)),
        "doc_comment": "整体注释覆盖一般，跨包说明偏少",
        "suggestions": [
            "为多包工程补充统一的 README 与模块划分说明",
            "把重复的工具类抽到公共模块，避免每个包各写一份",
        ],
        "summary": (
            f"本次提交共 {len(request.files)} 份源码包，"
            f"平均分 {average:.1f}，其中 {degraded} 份评审未完整完成。"
        ),
    }


def mock_report_aggregate(request) -> dict[str, Any]:
    """多份报告的汇总 mock：各维度取平均。"""
    scores = [f.score for f in request.files if f.score is not None]
    average = sum(scores) / len(scores) if scores else 0
    degraded = sum(1 for f in request.files if f.degraded)
    # 必须与 ReportEvaluationDraft.dimensions 的字段完全对齐：
    # DimensionScore 要求 key / name / weight / score 齐全，只给 key+score 会在
    # pydantic 校验阶段直接 500（2026-11 实测踩过），所以 name 与 weight 从
    # REPORT_DIMENSION_SPECS 取，和单份评审的 mock 保持同一份定义。
    spec = {item.key: item for item in REPORT_DIMENSION_SPECS}
    raw = [
        ("STRUCTURE", 2, "多份材料合并后章节基本完整"),
        ("REQUIREMENT", 0, "需求描述与题目基本对应"),
        ("DESIGN", 0, "设计章节有图示但深度一般"),
        ("IMPLEMENTATION", -3, "实现说明偏少"),
        ("TESTING", -5, "缺少系统化测试数据"),
        ("FORMAT", 3, "排版规范"),
    ]
    dimensions = [
        {
            "key": key,
            "name": spec[key].name,
            "weight": spec[key].weight,
            "score": clamp_score(average + delta),
            "comment": comment,
        }
        for key, delta, comment in raw
    ]
    return {
        "dimensions": dimensions,
        # problems 是 list[ReportProblem]，不是字符串数组：
        # 字符串会触发 Input should be a valid dictionary 校验错误。
        "problems": [
            {
                "section": "整体",
                "message": "多份报告之间内容有重复",
                "suggestion": "合并重复章节，按设计/实现/测试重新分工",
            }
        ],
        "highlights": ["多份材料覆盖了不同阶段的工作"],
        "summary": (
            f"本次提交共 {len(request.files)} 份报告，平均分 {average:.1f}，"
            f"其中 {degraded} 份评审未完整完成。"
        ),
    }
