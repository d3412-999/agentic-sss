"""源码语义评审能力：只负责设计合理性与注释文档两个维度。"""

from app.core.config import Settings
from app.prompts.code_review import (
    AGGREGATE_SYSTEM_PROMPT,
    SYSTEM_PROMPT,
    build_aggregate_user_prompt,
    build_user_prompt,
)
from app.schemas.code import (
    CodeAggregateRequest,
    CodeReviewDraft,
    CodeReviewRequest,
    CodeReviewResponse,
)
from app.schemas.common import DimensionScore
from app.schemas.materials import SourceFile
from app.services.llm_service import LlmService
from app.services.mock_responses import mock_code_aggregate, mock_code_review
from app.services.scoring import CODE_DIMENSION_SPECS, clamp_score
from app.utils.prompt_guard import append_warning, detect_injection
from app.utils.text_utils import clip_source_material


class CodeReviewService:
    """源码语义评审服务。"""

    def __init__(self, settings: Settings, llm: LlmService) -> None:
        self._settings = settings
        self._llm = llm

    async def review(self, request: CodeReviewRequest) -> CodeReviewResponse:
        """评审源码的设计与注释，返回两个维度的得分与建议。"""
        material = request
        # Java 后端只传一段拼接好的 codeSnippet 时，包装成单个虚拟文件
        if not request.java_files and request.code_snippet.strip():
            material = request.model_copy(
                update={
                    "java_files": [
                        SourceFile(
                            path=request.topic_title or "提交的源码",
                            content=request.code_snippet,
                        )
                    ]
                }
            )

        source = clip_source_material(
            material,
            total_limit=self._settings.rag_max_code_chars,
            per_file_limit=6000,
        )
        clipped_request = material.model_copy(update={"java_files": source.java_files})

        # 防御性兜底：没有任何 Java 源码时不允许大模型“凭感觉”给及格分
        has_code = any(file.content.strip() for file in clipped_request.java_files)
        if not has_code:
            zero_dimensions = [
                DimensionScore(
                    key=spec.key,
                    name=spec.name,
                    weight=spec.weight,
                    score=0,
                    comment="未提供可评审的 Java 源码",
                )
                for spec in CODE_DIMENSION_SPECS
            ]
            return CodeReviewResponse(
                submission_id=request.submission_id,
                score=0,
                model="guard",
                dimensions=zero_dimensions,
                suggestions=["请提交包含 .java 源文件的 ZIP 包后重新分析"],
                summary="源码包中没有可评审的 Java 文件，本次源码语义分为 0。",
            )

        # 检测学生材料中的评审操纵语句，仅用于在 summary 末尾追加一行提示
        materials_text = "\n".join(
            [file.content for file in clipped_request.java_files]
            + [f"{issue.rule} {issue.message}" for issue in clipped_request.issues]
            + [str(value) for value in clipped_request.metrics.values()]
        )
        detections = detect_injection(materials_text)

        result = await self._llm.generate_json(
            task="code_review",
            schema=CodeReviewDraft,
            system_prompt=SYSTEM_PROMPT,
            user_prompt=build_user_prompt(clipped_request),
            mock_factory=lambda: mock_code_review(material),
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

        # 后端按 score 落库，这里用设计 60% + 注释 40% 折算成百分制；
        # 两个分项先各自 clamp 再合成，避免 LLM 返回 150/-20 之类的越界值污染成绩
        score = clamp_score(clamp_score(draft.design_score) * 0.6 + clamp_score(draft.doc_score) * 0.4)

        return CodeReviewResponse(
            submission_id=request.submission_id,
            score=score,
            model=result.model,
            dimensions=dimensions,
            suggestions=draft.suggestions,
            summary=append_warning(draft.summary, detections),
        )

    async def aggregate(self, request: CodeAggregateRequest) -> CodeReviewResponse:
        """多份源码包的汇总评审（map-reduce 的 reduce 阶段）。

        输入只有各份的摘要与得分，不含原始代码——9 份工程的原文拼起来必然爆上下文，
        而汇总真正需要的是「每份是什么水平、有哪些问题」。
        """
        if not request.files:
            zero_dimensions = [
                DimensionScore(
                    key=spec.key,
                    name=spec.name,
                    weight=spec.weight,
                    score=0,
                    comment="本次提交没有可汇总的源码包",
                )
                for spec in CODE_DIMENSION_SPECS
            ]
            return CodeReviewResponse(
                submission_id=request.submission_id,
                score=0,
                model="guard",
                dimensions=zero_dimensions,
                suggestions=["请提交包含 .java 源文件的 ZIP 包后重新分析"],
                summary="本次提交没有可汇总的源码包。",
            )

        result = await self._llm.generate_json(
            task="code_aggregate",
            schema=CodeReviewDraft,
            system_prompt=AGGREGATE_SYSTEM_PROMPT,
            user_prompt=build_aggregate_user_prompt(request),
            mock_factory=lambda: mock_code_aggregate(request),
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
        score = clamp_score(clamp_score(draft.design_score) * 0.6 + clamp_score(draft.doc_score) * 0.4)

        return CodeReviewResponse(
            submission_id=request.submission_id,
            score=score,
            model=result.model,
            dimensions=dimensions,
            suggestions=draft.suggestions,
            summary=draft.summary,
        )
