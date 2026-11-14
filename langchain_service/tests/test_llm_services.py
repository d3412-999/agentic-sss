"""服务级测试：用测试替身验证真实实现下的业务逻辑，不访问真实大模型。"""

import asyncio

from app.core.config import Settings
from app.schemas.code import CodeReviewDraft, CodeReviewRequest
from app.schemas.common import DimensionScore
from app.schemas.defense import (
    AudioItem,
    DefenseConfig,
    DefenseEvaluateRequest,
    DefenseEvaluationDraft,
    DefenseQuestion,
    DefenseQuestionList,
    DefenseQuestionRequest,
    EvaluateQuestion,
    QuestionScore,
    TranscriptItem,
)
from app.schemas.materials import ReportMaterial, ReportSection, SourceFile
from app.schemas.report import ReportEvaluateRequest, ReportEvaluationDraft
from app.services.code_service import CodeReviewService
from app.services.defense_service import DefenseService
from app.services.report_service import ReportService
from app.services.scoring import DEFENSE_DIMENSION_SPECS, REPORT_DIMENSION_SPECS
from tests.llm_doubles import FakeLlm, FakeRag, FakeTemplates

SETTINGS = Settings()


def _dimensions(specs, score: float = 80.0) -> list[DimensionScore]:
    return [DimensionScore(key=spec.key, name=spec.name, weight=spec.weight, score=score) for spec in specs]


def _question(index: int) -> DefenseQuestion:
    dimensions = ("P1_RETELL", "P2_REASON", "P3_MODIFY")
    return DefenseQuestion(
        type="DESIGN",
        question=f"第 {index} 题的追问",
        expected_points=["要点"],
        dimension=dimensions[index % 3],
    )


def _bonus_question() -> DefenseQuestion:
    return DefenseQuestion(
        type="EXTENSION",
        question="迁移到离线场景你怎么取舍？",
        expected_points=["取舍理由"],
        dimension="IS_BONUS",
        bonus=True,
    )


def _defense_request(with_bonus: bool = False) -> DefenseEvaluateRequest:
    questions = [
        EvaluateQuestion(id="q1", question="为什么这样分层？", expected_points=["职责边界"]),
        EvaluateQuestion(id="q2", question="如何处理空指针？", expected_points=["判空"]),
    ]
    transcript = [
        TranscriptItem(question_id="q1", text="我把业务放在 Service 层。" * 3, duration_ms=30000),
        TranscriptItem(question_id="q2", text="调用前判空。" * 3, duration_ms=30000),
    ]
    audio = [
        AudioItem(question_id="q1", asr_confidence=0.9),
        AudioItem(question_id="q2", asr_confidence=0.8),
    ]
    if with_bonus:
        questions.append(EvaluateQuestion(id="bonus", question="迁移到离线", expected_points=["取舍"], dimension="IS_BONUS", bonus=True))
        transcript.append(TranscriptItem(question_id="bonus", text="我会加一条出站队列。" * 5, duration_ms=20000))
        audio.append(AudioItem(question_id="bonus", asr_confidence=0.7))
    return DefenseEvaluateRequest(defense_id="d1", questions=questions, transcript=transcript, audio=audio)


def test_generate_defense_questions_with_test_double() -> None:
    fake = FakeLlm(DefenseQuestionList(questions=[_question(i) for i in range(6)] + [_bonus_question()]))
    service = DefenseService(SETTINGS, fake, FakeRag())
    response = asyncio.run(service.generate_questions(DefenseQuestionRequest(config=DefenseConfig(question_count=6))))

    scored = [item for item in response.questions if not item.bonus]
    bonus = [item for item in response.questions if item.bonus]
    assert len(scored) == 6
    assert len(bonus) == 1
    assert bonus[0].dimension == "IS_BONUS"
    assert {item.dimension for item in scored} == {"P1_RETELL", "P2_REASON", "P3_MODIFY"}
    assert all(item.expected_points and item.max_answer_seconds >= 60 for item in scored)
    assert response.model == "fake-llm"


def test_evaluate_defense_with_test_double() -> None:
    draft = DefenseEvaluationDraft(
        summary="整体不错",
        dimensions=_dimensions(DEFENSE_DIMENSION_SPECS, 80),
        question_scores=[QuestionScore(question_id="q1", score=85), QuestionScore(question_id="q2", score=75)],
    )
    service = DefenseService(SETTINGS, FakeLlm(draft), FakeRag())
    response = asyncio.run(service.evaluate(_defense_request()))

    assert len(response.dimensions) == 4
    assert len(response.question_scores) == 2
    assert 0 <= response.score <= 100
    assert response.grade in {"优秀", "良好", "中等", "及格", "不及格"}
    assert response.asr["averageConfidence"] == 0.85


def test_bonus_question_is_excluded_from_total() -> None:
    draft = DefenseEvaluationDraft(
        summary="整体不错",
        dimensions=_dimensions(DEFENSE_DIMENSION_SPECS, 80),
        question_scores=[
            QuestionScore(question_id="q1", score=85),
            QuestionScore(question_id="q2", score=75),
            QuestionScore(question_id="bonus", score=95),
        ],
    )
    service = DefenseService(SETTINGS, FakeLlm(draft), FakeRag())
    plain = asyncio.run(service.evaluate(_defense_request(False)))
    with_bonus = asyncio.run(service.evaluate(_defense_request(True)))

    assert plain.score == with_bonus.score
    bonus_row = next(item for item in with_bonus.question_scores if item.question_id == "bonus")
    assert bonus_row.score > 0


def test_review_code_with_test_double() -> None:
    fake = FakeLlm(
        CodeReviewDraft(
            design_score=80,
            design_comment="分层清晰",
            doc_score=60,
            doc_comment="注释偏少",
            suggestions=["补充关键方法注释"],
            summary="总体良好",
        )
    )
    service = CodeReviewService(SETTINGS, fake)
    request = CodeReviewRequest(
        submission_id="s1",
        java_files=[SourceFile(path="src/main/java/com/example/A.java", content="public class A {}")],
        metrics={"codeLines": 10},
    )
    response = asyncio.run(service.review(request))

    assert response.score == 72.0  # 80 * 0.6 + 60 * 0.4
    assert [dimension.key for dimension in response.dimensions] == ["DESIGN", "DOC"]
    assert response.suggestions == ["补充关键方法注释"]


def test_evaluate_report_with_test_double() -> None:
    fake = FakeLlm(
        ReportEvaluationDraft(
            summary="结构完整",
            dimensions=_dimensions(REPORT_DIMENSION_SPECS, 75),
            highlights=["分层清晰"],
            problems=[],
        )
    )
    service = ReportService(SETTINGS, fake, FakeTemplates())
    request = ReportEvaluateRequest(
        submission_id="s1",
        topic_title="图书管理系统",
        report=ReportMaterial(sections=[ReportSection(title="1 需求分析", content="系统支持借阅与归还。")], page_count=12),
    )
    response = asyncio.run(service.evaluate(request))

    assert len(response.dimensions) == 6
    assert 0 <= response.score <= 100
    assert response.page_count == 12
    assert response.highlights == ["分层清晰"]