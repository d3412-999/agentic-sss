"""报告与源码一致性检查：基于 RAG 检索 + 标识符比对。"""

import logging

from app.core.config import Settings
from app.rag.document_builder import build_rag_documents
from app.rag.local_index import LocalRagIndex
from app.rag.rag_service import RagService
from app.schemas.consistency import (
    ConsistencyIssue,
    ConsistencyRequest,
    ConsistencyResponse,
)
from app.utils.text_utils import extract_identifiers

logger = logging.getLogger(__name__)


class ConsistencyService:
    """检查报告中提到的类 / 方法是否真的存在于提交的源码中。"""

    def __init__(self, settings: Settings, rag: RagService) -> None:
        self._settings = settings
        self._rag = rag

    async def check(self, request: ConsistencyRequest) -> ConsistencyResponse:
        """执行一致性检查并返回得分与问题列表。"""
        documents = build_rag_documents(request.source, request.report)
        index = LocalRagIndex(documents)

        entities = self._collect_entities(request)
        issues: list[ConsistencyIssue] = []
        matched = 0

        for name, section_title in entities:
            if index.find_documents(name):
                matched += 1
            else:
                issues.append(
                    ConsistencyIssue(
                        type="REPORT_ENTITY_MISSING",
                        severity="MEDIUM",
                        message=f"报告中提到的「{name}」在提交的源码中未找到定义",
                        report_section=section_title,
                    )
                )

        total = len(entities)
        score = round(matched / total * 100, 1) if total else 100.0

        # 启用 LightRAG 时额外查询一次，作为教师复核的检索证据，并记录实际使用的方法
        method = "local-rag"
        if self._rag.lightrag_enabled:
            try:
                result = await self._rag.retrieve(
                    "报告中提到的类、方法、数据库表是否存在于源码实现中",
                    documents,
                    top_k=3,
                )
                method = result.method
            except Exception as exc:  # noqa: BLE001 - 检索失败不应影响检查结果
                logger.warning("一致性检查的 LightRAG 检索失败：%s", exc)

        if not request.source.java_files and entities:
            issues.insert(
                0,
                ConsistencyIssue(
                    type="SOURCE_MISSING",
                    severity="HIGH",
                    message="未提供源码文件，无法核对报告中的实现描述",
                ),
            )

        return ConsistencyResponse(
            submission_id=request.submission_id,
            score=score,
            issues=issues,
            checked_entities=total,
            method=method,
        )

    @staticmethod
    def _collect_entities(request: ConsistencyRequest) -> list[tuple[str, str]]:
        """按章节提取报告中出现的标识符，并限制检查数量。"""
        limit = max(1, min(request.max_entities, 200))
        result: list[tuple[str, str]] = []
        seen: set[str] = set()

        for section in request.report.sections:
            for name in extract_identifiers(section.content):
                if name in seen:
                    continue
                seen.add(name)
                result.append((name, section.title))
                if len(result) >= limit:
                    return result
        return result
