"""LightRAG HTTP 客户端（可选启用）。

本服务不直接依赖 LightRAG 进程；当 LIGHTRAG_ENABLED=true 时，
通过 lightrag-server 的 REST API 做文档写入与检索。
"""

import logging
from typing import Any

import httpx

from app.core.config import Settings

logger = logging.getLogger(__name__)


class LightRagError(Exception):
    """LightRAG 调用失败。"""


class LightRagClient:
    """lightrag-server 的轻量封装。"""

    def __init__(self, settings: Settings) -> None:
        headers = {"Content-Type": "application/json"}
        if settings.lightrag_api_key.strip():
            headers["X-API-Key"] = settings.lightrag_api_key
        self._settings = settings
        self._client = httpx.AsyncClient(
            base_url=settings.lightrag_base_url.rstrip("/"),
            headers=headers,
            timeout=settings.lightrag_timeout_seconds,
        )

    async def insert_text(self, text: str) -> None:
        """写入一段文本（由 LightRAG 负责实体抽取与建图）。"""
        try:
            response = await self._client.post("/insert", json={"text": text})
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise LightRagError(f"LightRAG 写入失败：{exc}") from exc

    async def query(self, question: str) -> str:
        """向 LightRAG 提问并返回回答文本。"""
        payload: dict[str, Any] = {
            "query": question,
            "mode": self._settings.lightrag_query_mode,
        }
        try:
            response = await self._client.post("/query", json=payload)
            response.raise_for_status()
            data = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise LightRagError(f"LightRAG 查询失败：{exc}") from exc

        # 兼容 lightrag-server 不同版本的返回结构
        if isinstance(data, str):
            return data
        if isinstance(data, dict):
            for key in ("response", "answer", "result"):
                value = data.get(key)
                if isinstance(value, str) and value.strip():
                    return value
            nested = data.get("data")
            if isinstance(nested, dict):
                for key in ("response", "answer", "result"):
                    value = nested.get(key)
                    if isinstance(value, str) and value.strip():
                        return value
        raise LightRagError("LightRAG 返回结构无法识别")

    async def delete_document(self, doc_id: str) -> None:
        """按文档 ID 删除 LightRAG 中的文档（尽力而为，不同版本接口可能不同）。"""
        try:
            response = await self._client.delete(f"/documents/{doc_id}")
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise LightRagError(f"LightRAG 删除失败：{exc}") from exc

    async def close(self) -> None:
        """关闭底层 HTTP 连接。"""
        await self._client.aclose()
