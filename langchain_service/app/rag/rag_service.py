"""RAG 服务：优先 LightRAG，失败时回退本地检索。"""

import hashlib
import logging
from dataclasses import dataclass, field

from app.core.config import Settings
from app.rag.document_builder import RagDocument
from app.rag.lightrag_client import LightRagClient, LightRagError
from app.rag.local_index import LocalRagIndex, SearchHit

logger = logging.getLogger(__name__)


@dataclass
class RagResult:
    """一次检索的结果。"""

    method: str
    contexts: list[str] = field(default_factory=list)
    hits: list[SearchHit] = field(default_factory=list)


class RagService:
    """统一封装 LightRAG 与本地检索。"""

    def __init__(self, settings: Settings, lightrag_client: LightRagClient | None = None) -> None:
        self._settings = settings
        self._lightrag = lightrag_client
        # 记录已写入 LightRAG 的文档摘要，避免同一进程内重复写入
        self._indexed_digests: set[str] = set()

    @property
    def lightrag_enabled(self) -> bool:
        """是否启用了 LightRAG。"""
        return self._lightrag is not None

    def build_index(self, documents: list[RagDocument]) -> LocalRagIndex:
        """构建本地检索索引。"""
        return LocalRagIndex(documents)

    async def retrieve(self, question: str, documents: list[RagDocument], top_k: int | None = None) -> RagResult:
        """检索与问题相关的材料：LightRAG 回答 + 本地命中片段。"""
        index = self.build_index(documents)
        limit = top_k or self._settings.rag_top_k
        hits = index.search(question, top_k=limit)
        contexts = [hit.document.content for hit in hits]

        if self._lightrag is None:
            return RagResult(method="local-rag", contexts=contexts, hits=hits)

        try:
            await self._ensure_indexed(documents)
            answer = await self._lightrag.query(question)
            return RagResult(method="lightrag+local", contexts=[answer, *contexts], hits=hits)
        except LightRagError as exc:
            logger.warning("LightRAG 不可用，回退本地检索：%s", exc)
            return RagResult(method="local-rag", contexts=contexts, hits=hits)

    async def _ensure_indexed(self, documents: list[RagDocument]) -> None:
        """把尚未写入 LightRAG 的文档写入一次。"""
        if self._lightrag is None:
            return
        for document in documents:
            digest = hashlib.sha256(document.content.encode("utf-8")).hexdigest()
            if digest in self._indexed_digests:
                continue
            text = f"【{document.kind}】{document.title}\n{document.content}"
            await self._lightrag.insert_text(text)
            self._indexed_digests.add(digest)

    async def close(self) -> None:
        """释放 LightRAG 连接。"""
        if self._lightrag is not None:
            await self._lightrag.close()
