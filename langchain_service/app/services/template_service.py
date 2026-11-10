"""优秀作品模板库服务。

存储使用 Python 标准库 sqlite3，检索复用本地 BM25 索引；
如果启用了 LightRAG，会同时把文档写入 LightRAG，并在删除时尽力清理。
"""

import json
import logging
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

from app.core.config import Settings
from app.rag.document_builder import RagDocument
from app.rag.lightrag_client import LightRagClient, LightRagError
from app.rag.local_index import LocalRagIndex
from app.schemas.template import (
    TemplateDeleteResponse,
    TemplateIndexRequest,
    TemplateIndexResponse,
    TemplateSearchHit,
    TemplateSearchResponse,
)

logger = logging.getLogger(__name__)


class TemplateService:
    """优秀作品模板库：本地持久化 + 可选 LightRAG 镜像。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._db_path = Path(settings.template_db_path)
        self._db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._init_db()
        self._lightrag = LightRagClient(settings) if settings.lightrag_enabled else None

    # ---------------------------------------------------------------- 索引

    async def index(self, request: TemplateIndexRequest) -> TemplateIndexResponse:
        """写入或更新一份模板文档。"""
        self._upsert(request)
        method = "local"
        message = "已写入本地模板索引"

        if self._lightrag is not None:
            text = f"【优秀作品模板】{request.title}\n{request.content}"
            try:
                await self._lightrag.insert_text(text)
                method = "lightrag+local"
                message = "已写入 LightRAG 与本地模板索引"
            except LightRagError as exc:
                logger.warning("LightRAG 写入失败，模板已保存在本地：%s", exc)
                message = f"LightRAG 暂不可用，已回退本地：{exc}"

        return TemplateIndexResponse(
            doc_id=request.doc_id,
            status="INDEXED",
            method=method,
            message=message,
        )

    async def delete(self, doc_id: str) -> TemplateDeleteResponse:
        """删除模板文档，并尽力清理 LightRAG 索引。"""
        deleted = self._delete_local(doc_id)
        if self._lightrag is not None:
            try:
                await self._lightrag.delete_document(doc_id)
            except LightRagError as exc:
                logger.warning("LightRAG 删除失败，本地模板已删除：%s", exc)
        return TemplateDeleteResponse(
            doc_id=doc_id,
            deleted=deleted,
            message="已从本地模板库删除",
        )

    # ---------------------------------------------------------------- 检索

    def search(self, query: str, top_k: int = 3) -> TemplateSearchResponse:
        """按相关性检索模板库。"""
        documents = self._load_documents()
        if not documents or not query.strip():
            return TemplateSearchResponse(hits=[], method="local")

        index = LocalRagIndex(documents)
        hits = index.search(query, top_k=max(1, min(top_k, 10)))
        return TemplateSearchResponse(
            hits=[
                TemplateSearchHit(
                    doc_id=hit.document.doc_id,
                    title=hit.document.title,
                    score=hit.score,
                    excerpt=_excerpt(hit.document.content),
                )
                for hit in hits
            ],
            method="local",
        )

    def search_contexts(self, query: str, top_k: int = 2, max_chars: int = 1500) -> list[str]:
        """返回可直接放入提示词的模板片段。"""
        result = self.search(query, top_k=top_k)
        contexts = []
        for hit in result.hits:
            content = self._load_content(hit.doc_id)
            if content:
                contexts.append(f"《{hit.title}》\n{content[:max_chars]}")
        return contexts

    async def close(self) -> None:
        """释放 LightRAG 连接。"""
        if self._lightrag is not None:
            await self._lightrag.close()

    # ---------------------------------------------------------------- 内部

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self._db_path, timeout=10)
        connection.row_factory = sqlite3.Row
        return connection

    def _init_db(self) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS template_document (
                    doc_id     TEXT PRIMARY KEY,
                    title      TEXT NOT NULL,
                    content    TEXT NOT NULL,
                    category   TEXT NOT NULL DEFAULT 'EXCELLENT_WORK',
                    metadata   TEXT,
                    created_at TEXT NOT NULL
                )
                """
            )

    def _upsert(self, request: TemplateIndexRequest) -> None:
        now = datetime.now(timezone.utc).isoformat()
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO template_document (doc_id, title, content, category, metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(doc_id) DO UPDATE SET
                    title = excluded.title,
                    content = excluded.content,
                    category = excluded.category,
                    metadata = excluded.metadata
                """,
                (
                    request.doc_id,
                    request.title,
                    request.content,
                    request.category,
                    json.dumps(request.metadata, ensure_ascii=False),
                    now,
                ),
            )

    def _delete_local(self, doc_id: str) -> bool:
        with self._lock, self._connect() as connection:
            cursor = connection.execute("DELETE FROM template_document WHERE doc_id = ?", (doc_id,))
            return cursor.rowcount > 0

    def _load_documents(self) -> list[RagDocument]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                "SELECT doc_id, title, content FROM template_document ORDER BY created_at DESC"
            ).fetchall()
        return [
            RagDocument(
                doc_id=row["doc_id"],
                kind="template",
                title=row["title"],
                content=row["content"],
            )
            for row in rows
        ]

    def _load_content(self, doc_id: str) -> str:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT content FROM template_document WHERE doc_id = ?", (doc_id,)
            ).fetchone()
        return "" if row is None else str(row["content"])


def _excerpt(content: str, limit: int = 300) -> str:
    """截取片段用于接口展示。"""
    text = (content or "").strip()
    return text if len(text) <= limit else text[:limit] + "……"
