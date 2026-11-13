"""报告评审能力。"""

from app.core.config import Settings
from app.prompts.report_evaluation import (
    AGGREGATE_SYSTEM_PROMPT,
    SYSTEM_PROMPT,
    build_aggregate_user_prompt,
    build_user_prompt,
)
from app.schemas.common import DimensionScore
from app.schemas.materials import TopicBrief
from app.schemas.report import (
    ReportAggregateRequest,
    ReportEvaluateRequest,
    ReportEvaluationDraft,
    ReportEvaluationResponse,
)
from app.services.llm_service import LlmService
from app.services.mock_responses import mock_report_aggregate, mock_report_evaluation
from app.services.scoring import (
    REPORT_DIMENSION_SPECS,
    grade_of,
    normalize_dimensions,
    weighted_total,
)
from app.services.template_service import TemplateService
from app.utils.prompt_guard import append_warning, detect_injection
from app.utils.text_utils import clip_report_material, split_report_text


class ReportService:
    """课程设计报告评审服务。"""

    def __init__(self, settings: Settings, llm: LlmService, templates: TemplateService) -> None:
        self._settings = settings
        self._llm = llm
        self._templates = templates

    async def evaluate(self, request: ReportEvaluateRequest) -> ReportEvaluationResponse:
        """评审报告内容，返回维度分、亮点与问题。"""
        report = request.report
        # Java 后端尚未抽取 PDF 正文时 reportText 为空；接入后可直接切成章节
        if not report.sections and request.report_text.strip():
            report = report.model_copy(update={"sections": split_report_text(request.report_text)})
        if not report.page_count and request.page_count:
            report = report.model_copy(update={"page_count": request.page_count})

        topic = request.topic
        if topic is None and request.topic_title:
            topic = TopicBrief(title=request.topic_title, requirements=request.requirements)

        report = clip_report_material(
            report,
            section_limit=self._settings.rag_max_section_chars,
            total_limit=self._settings.rag_max_code_chars,
        )
        clipped_request = request.model_copy(update={"report": report, "topic": topic})

        # 从优秀作品模板库检索相似案例，作为评分参考；没有模板时返回空列表
        query_parts = [topic.title if topic else "", report.sections[0].content[:600] if report.sections else ""]
        reference_contexts = self._templates.search_contexts(" ".join(query_parts).strip(), top_k=2)

        # 检测报告正文中的评审操纵语句，仅用于在 summary 末尾追加一行提示
        detections = detect_injection("\n".join(section.content for section in report.sections))

        result = await self._llm.generate_json(
            task="report_evaluation",
            schema=ReportEvaluationDraft,
            system_prompt=SYSTEM_PROMPT,
            user_prompt=build_user_prompt(clipped_request, reference_contexts),
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
            summary=append_warning(draft.summary, detections),
            dimensions=dimensions,
            highlights=draft.highlights,
            problems=draft.problems,
            page_count=report.page_count,
        )

    async def aggregate(self, request: ReportAggregateRequest) -> ReportEvaluationResponse:
        """多份报告的汇总评审（map-reduce 的 reduce 阶段）。"""
        if not request.files:
            # 兜底分支必须和正常分支产出同样的字段形状：
            #   * problems 是 list[ReportProblem]，传字符串会 500（2026-11 实测）；
            #   * dimensions 补齐 6 个零分维度，前端表格才不会因为空数组错行；
            #   * grade 用 grade_of(0) 得到「不及格」，与全链路的中文等级保持一致。
            zero_dimensions = [
                DimensionScore(
                    key=spec.key,
                    name=spec.name,
                    weight=spec.weight,
                    score=0,
                    comment="本次提交没有可汇总的报告",
                )
                for spec in REPORT_DIMENSION_SPECS
            ]
            return ReportEvaluationResponse(
                submission_id=request.submission_id,
                score=0,
                grade=grade_of(0),
                model="guard",
                summary="本次提交没有可汇总的报告。",
                dimensions=zero_dimensions,
                highlights=[],
                problems=[
                    {
                        "section": "材料",
                        "message": "本次提交没有可汇总的报告",
                        "suggestion": "请提交 PDF 或 DOCX 报告后重新评审",
                    }
                ],
                page_count=0,
            )

        result = await self._llm.generate_json(
            task="report_aggregate",
            schema=ReportEvaluationDraft,
            system_prompt=AGGREGATE_SYSTEM_PROMPT,
            user_prompt=build_aggregate_user_prompt(request),
            mock_factory=lambda: mock_report_aggregate(request),
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
            page_count=sum(item.page_count or 0 for item in request.files),
        )
