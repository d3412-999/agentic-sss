"""源码语义评审能力：只负责设计合理性与注释文档两个维度。"""

import re

from app.core.config import Settings
from app.prompts.code_review import (
    AGGREGATE_SYSTEM_PROMPT,
    SYSTEM_PROMPT,
    build_aggregate_user_prompt,
    build_user_prompt,
)
from app.review.java_rules import merge_findings, scan_java_files, summarize, tool_findings
from app.schemas.code import (
    CodeAggregateRequest,
    CodeFinding,
    CodeReviewDraft,
    CodeReviewRequest,
    CodeReviewResponse,
    RuleSummary,
)
from app.schemas.common import DimensionScore
from app.schemas.materials import SourceFile
from app.services.llm_service import LlmService
from app.services.scoring import CODE_DIMENSION_SPECS, clamp_score
from app.utils.prompt_guard import append_warning, detect_injection
from app.utils.text_utils import clip_source_material

# 后端把 zip 里多个 Java 文件拼成一段 codeSnippet，这里按内嵌标记拆回去：
#   * ArchiveGuard 的格式：`// src/main/java/A.java`
#   * 多份合并的格式：`===== 文件：a.zip =====`
_SNIPPET_HEADER = re.compile(
    r"^\s*(?:={5,}\s*文件[：:]\s*(?P<merged>.+?)\s*={5,}|//\s*(?P<plain>\S+\.java))\s*$"
)


def split_snippet_files(text: str, fallback_path: str) -> list[SourceFile]:
    """把拼接的 codeSnippet 拆回按文件分组的 SourceFile，保证 findings 有真实文件路径与行号。"""
    files: list[SourceFile] = []
    path = fallback_path or "提交的源码"
    buffer: list[str] = []

    def flush() -> None:
        nonlocal buffer
        if buffer and any(line.strip() for line in buffer):
            files.append(SourceFile(path=path, content="\n".join(buffer)))
        buffer = []

    for line in text.splitlines():
        match = _SNIPPET_HEADER.match(line)
        if match:
            flush()
            path = (match.group("merged") or match.group("plain") or path).strip()
            continue
        buffer.append(line)
    flush()
    if not files:
        return [SourceFile(path=path, content=text)]
    return files


class CodeReviewService:
    """源码语义评审服务。"""

    def __init__(self, settings: Settings, llm: LlmService) -> None:
        self._settings = settings
        self._llm = llm

    async def review(self, request: CodeReviewRequest) -> CodeReviewResponse:
        """评审源码的设计与注释，返回两个维度的得分与建议。"""
        material = request
        # Java 后端只传一段拼接好的 codeSnippet 时，按文件标记拆成多个虚拟文件；
        # 不拆的话规则 findings 的 file 会落成题目标题、行号也是拼接后的全局行号。
        if not request.java_files and request.code_snippet.strip():
            material = request.model_copy(
                update={"java_files": split_snippet_files(request.code_snippet, request.topic_title)}
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

        # ---- 混合架构的确定性一侧：规则引擎 + 工具问题 ----
        rule_hits = scan_java_files(clipped_request.java_files)
        tool_hits = tool_findings(clipped_request.issues)
        deterministic_findings = merge_findings(rule_hits, tool_hits)

        result = await self._llm.generate_json(
            task="code_review",
            schema=CodeReviewDraft,
            system_prompt=SYSTEM_PROMPT,
            user_prompt=build_user_prompt(clipped_request, deterministic_findings),
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

        # ---- 混合架构的 LLM 补充 findings：只保留规则未覆盖的语义问题 ----
        llm_findings: list[CodeFinding] = []
        for item in draft.findings:
            severity = (item.severity or "").upper()
            if severity not in {"CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"}:
                severity = "MEDIUM"
            rule_id = (item.rule_id or "SEMANTIC").strip()
            if not rule_id.upper().startswith("SEMANTIC"):
                rule_id = f"SEMANTIC_{rule_id.upper()}"
            llm_findings.append(
                item.model_copy(
                    update={
                        "rule_id": rule_id,
                        "severity": severity,
                        "source": "LLM",
                        "confidence": min(max(item.confidence or 0.7, 0.1), 1.0),
                    }
                )
            )
        findings = merge_findings(deterministic_findings, llm_findings)
        rule_summary: RuleSummary = summarize(findings)

        # ---- 混合评分：确定性高严重问题给综合分封顶，规则不只停留在提示 ----
        summary_text = draft.summary
        cap = rule_summary.score_cap
        if cap is not None and score > cap:
            score = clamp_score(cap)
            summary_text += (
                f"\n\n确定性规则命中 {rule_summary.critical} 个 CRITICAL、{rule_summary.high} 个 HIGH 问题，"
                f"综合分按规则上限封顶为 {cap:.0f} 分。"
            )

        problem_lines = [
            f"{item.file}:{item.line or '?'} [{item.severity}] {item.message}"
            for item in findings[:10]
        ]

        return CodeReviewResponse(
            submission_id=request.submission_id,
            score=score,
            model=result.model,
            dimensions=dimensions,
            suggestions=draft.suggestions,
            problems=problem_lines,
            findings=findings[:50],
            rule_summary=rule_summary,
            verdict=rule_summary.verdict,
            summary=append_warning(summary_text, detections),
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
