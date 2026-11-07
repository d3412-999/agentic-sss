"""源码语义评审能力：只负责设计合理性与注释文档两个维度。"""

from app.core.config import Settings
from app.prompts.code_review import (
    SYSTEM_PROMPT,
    build_user_prompt,
)
from app.schemas.code import CodeReviewDraft, CodeReviewRequest, CodeReviewResponse
from app.schemas.common import DimensionScore
from app.services.llm_service import LlmService
from app.services.mock_responses import mock_code_review
from app.services.scoring import CODE_DIMENSION_SPECS, clamp_score
from app.utils.text_utils import clip_source_material


class CodeReviewService:
    """源码语义评审服务。"""

    def __init__(self, settings: Settings, llm: LlmService) -> None:
        self._settings = settings
        self._llm = llm

    async def review(self, request: CodeReviewRequest) -> CodeReviewResponse:
        """评审源码的设计与注释，返回两个维度的得分与建议。"""
        source = clip_source_material(
            request,
            total_limit=self._settings.rag_max_code_chars,
            per_file_limit=6000,
        )
        clipped_request = request.model_copy(update={"java_files": source.java_files})

        result = await self._llm.generate_json(
            task="code_review",
            schema=CodeReviewDraft,
            system_prompt=SYSTEM_PROMPT,
            user_prompt=build_user_prompt(clipped_request),
            mock_factory=lambda: mock_code_review(request),
        )
        draft = result.data

        dimensions = [
            DimensionScore(
                key=spec.key,
                name=spec.name,
                weight=spec.weight,
                score=clamp_score(draft.design_score if spec.key == "DESIGN" else draft.doc_score),
                comment=draft.design_comment if spec.key == "DESIGN" else draft.doc_comment,
            )
            for spec in CODE_DIMENSION_SPECS
        ]

        return CodeReviewResponse(
            submission_id=request.submission_id,
            dimensions=dimensions,
            suggestions=draft.suggestions,
            summary=draft.summary,
        )
