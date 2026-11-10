"""答辩能力：生成答辩题、答辩评分。"""

from app.core.config import Settings
from app.core.exceptions import AiServiceError, LlmResponseFormatError
from app.prompts.defense_evaluation import (
    SYSTEM_PROMPT as EVALUATION_SYSTEM_PROMPT,
    build_user_prompt as build_evaluation_prompt,
)
from app.prompts.defense_questions import (
    SYSTEM_PROMPT as QUESTION_SYSTEM_PROMPT,
    build_user_prompt as build_question_prompt,
)
from app.rag.document_builder import build_rag_documents
from app.rag.rag_service import RagService
from app.schemas.defense import (
    DefenseEvaluateRequest,
    DefenseEvaluationDraft,
    DefenseEvaluationResponse,
    DefenseFlag,
    DefenseQuestion,
    DefenseQuestionList,
    DefenseQuestionRequest,
    DefenseQuestionResponse,
    QuestionScore,
)
from app.services.llm_service import LlmService
from app.services.mock_responses import mock_defense_evaluation, mock_defense_questions
from app.services.scoring import (
    DEFENSE_DIMENSION_SPECS,
    grade_of,
    normalize_dimensions,
    weighted_total,
)
from app.utils.text_utils import clip_report_material, clip_source_material


class DefenseService:
    """答辩业务服务。"""

    def __init__(self, settings: Settings, llm: LlmService, rag: RagService) -> None:
        self._settings = settings
        self._llm = llm
        self._rag = rag

    async def generate_questions(self, request: DefenseQuestionRequest) -> DefenseQuestionResponse:
        """根据题目、源码与报告生成答辩题。"""
        count = max(1, min(request.config.question_count, 12))

        source = clip_source_material(
            request.source,
            total_limit=self._settings.rag_max_code_chars,
            per_file_limit=6000,
        )
        report = clip_report_material(
            request.report,
            section_limit=self._settings.rag_max_section_chars,
            total_limit=self._settings.rag_max_code_chars,
        )
        clipped_request = request.model_copy(update={"source": source, "report": report})

        # 用题目要求做一次检索，把最相关的源码 / 报告片段放进提示词
        documents = build_rag_documents(source, report)
        query = " ".join(
            [topic.title + " " + topic.requirements for topic in request.topics]
        ) or "课程设计 源码 报告 设计"
        rag_result = await self._rag.retrieve(query, documents, top_k=3)

        system_prompt = QUESTION_SYSTEM_PROMPT
        user_prompt = build_question_prompt(clipped_request, rag_result.contexts[:3], [])

        result = await self._llm.generate_json(
            task="defense_questions",
            schema=DefenseQuestionList,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            mock_factory=lambda: mock_defense_questions(request),
        )
        questions = list(result.data.questions)[:count]
        if not questions:
            raise LlmResponseFormatError("大模型没有生成任何答辩题目")

        normalized_questions = [self._normalize_question(question) for question in questions]
        return DefenseQuestionResponse(
            request_id=request.request_id,
            questions=normalized_questions,
            model=result.model,
            tokens=result.usage,
        )

    async def evaluate(self, request: DefenseEvaluateRequest) -> DefenseEvaluationResponse:
        """根据转写稿给出答辩评分。"""
        if not request.questions:
            raise AiServiceError("答辩题目不能为空", status_code=400, code="INVALID_REQUEST")

        system_prompt = EVALUATION_SYSTEM_PROMPT
        user_prompt = build_evaluation_prompt(request)
        result = await self._llm.generate_json(
            task="defense_evaluation",
            schema=DefenseEvaluationDraft,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            mock_factory=lambda: mock_defense_evaluation(request),
        )

        draft = result.data
        dimensions = normalize_dimensions(draft.dimensions, DEFENSE_DIMENSION_SPECS)

        provided_scores = {item.question_id: item for item in draft.question_scores}
        question_scores: list[QuestionScore] = []
        for question in request.questions:
            item = provided_scores.get(question.id)
            question_scores.append(
                QuestionScore(
                    question_id=question.id,
                    score=max(0.0, min(100.0, round(item.score, 1))) if item else 0.0,
                    comment=item.comment if item else "未给出评分",
                )
            )

        score = weighted_total(dimensions)
        asr = self._build_asr_summary(request)
        flags = [
            DefenseFlag(type=flag.type, message=flag.message, confidence=flag.confidence)
            for flag in draft.flags
        ]

        return DefenseEvaluationResponse(
            defense_id=request.defense_id,
            score=score,
            grade=grade_of(score),
            summary=draft.summary,
            dimensions=dimensions,
            question_scores=question_scores,
            flags=flags,
            asr=asr,
        )

    @staticmethod
    def _normalize_question(question: DefenseQuestion) -> DefenseQuestion:
        """限制题目类型、难度与作答时限。"""
        allowed_types = {"DESIGN", "CODE", "REPORT", "CONCEPT", "EXTENSION"}
        allowed_difficulties = {"EASY", "MEDIUM", "HARD"}
        return question.model_copy(
            update={
                "type": question.type if question.type in allowed_types else "DESIGN",
                "difficulty": (
                    question.difficulty
                    if question.difficulty in allowed_difficulties
                    else "MEDIUM"
                ),
                "max_answer_seconds": max(60, min(question.max_answer_seconds, 600)),
            }
        )

    @staticmethod
    def _build_asr_summary(request: DefenseEvaluateRequest) -> dict[str, float | list[str]]:
        """汇总 ASR 置信度，供教师端参考。"""
        confidences = [
            item.asr_confidence for item in request.audio if item.asr_confidence is not None
        ]
        if not confidences:
            return {}
        average = round(sum(confidences) / len(confidences), 3)
        low_segments = [
            item.question_id for item in request.audio if (item.asr_confidence or 1.0) < 0.6
        ]
        return {"averageConfidence": average, "lowConfidenceSegments": low_segments}
