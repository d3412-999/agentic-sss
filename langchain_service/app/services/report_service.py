"""报告评审能力。"""

from app.core.config import Settings
from app.prompts.report_evaluation import (
    SYSTEM_PROMPT,
    build_user_prompt,
)
from app.schemas.materials import TopicBrief
from app.schemas.report import (
    ReportEvaluateRequest,
    ReportEvaluationDraft,
    ReportEvaluationResponse,
)
from app.services.llm_service import LlmService
from app.services.mock_responses import mock_report_evaluation
from app.services.scoring import (
    REPORT_DIMENSION_SPECS,
    grade_of,
    normalize_dimensions,
    weighted_total,
)
from app.utils.text_utils import clip_report_material, split_report_text


class ReportService:
    """课程设计报告评审服务。"""

    def __init__(self, settings: Settings, llm: LlmService) -> None:
        self._settings = settings
        self._llm = llm

    async def evaluate(self, request: ReportEvaluateRequest) -> ReportEvaluationResponse:
        """评审报告内容，返回维度分、亮点与问题。"""
        report = request.report
        # Java 后端尚未抽取 PDF 正文时 reportText 为空；接入后可直接切成章节
        if not report.sections and request.report_text.strip():
            report = report.model_copy(update={"sections": split_report_text(request.report_text)})

        topic = request.topic
        if topic is None and request.topic_title:
            topic = TopicBrief(title=request.topic_title, requirements=request.requirements)

        report = clip_report_material(
            report,
            section_limit=self._settings.rag_max_section_chars,
            total_limit=self._settings.rag_max_code_chars,
        )
        clipped_request = request.model_copy(update={"report": report, "topic": topic})

        result = await self._llm.generate_json(
            task="report_evaluation",
            schema=ReportEvaluationDraft,
            system_prompt=SYSTEM_PROMPT,
            user_prompt=build_user_prompt(clipped_request),
            mock_factory=lambda: mock_report_evaluation(clipped_request),
        )

        draft = result.data
        dimensions = normalize_dimensions(draft.dimensions, REPORT_DIMENSION_SPECS)
        score = weighted_total(dimensions)

        return ReportEvaluationResponse(
            submission_id=request.submission_id,
            score=score,
            grade=grade_of(score),
            model=result.model,
            summary=draft.summary,
            dimensions=dimensions,
            highlights=draft.highlights,
            problems=draft.problems,
            page_count=request.report.page_count,
        )
