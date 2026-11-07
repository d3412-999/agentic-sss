"""轻量本地检索（BM25 简化版 + 标识符精确匹配）。

不依赖外部向量库，作为 LightRAG 不可用时的兜底方案，
用于在源码与报告之间做相关性检索和一致性核对。
"""

import math
import re
from collections import Counter
from dataclasses import dataclass

from app.rag.document_builder import RagDocument

# 英文标识符整体切分；连续中文按二元词切分
_TOKEN_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_\.]*|[\u4e00-\u9fff]+")


def tokenize(text: str) -> list[str]:
    """切分中英文混合文本。"""
    tokens: list[str] = []
    for match in _TOKEN_PATTERN.finditer(text or ""):
        raw = match.group(0)
        if raw[0].isascii():
            tokens.append(raw.lower())
        elif len(raw) == 1:
            tokens.append(raw)
        else:
            tokens.extend(raw[index : index + 2] for index in range(len(raw) - 1))
    return tokens


@dataclass
class SearchHit:
    """检索命中结果。"""

    document: RagDocument
    score: float


class LocalRagIndex:
    """基于词频与逆文档频率的本地检索索引。"""

    def __init__(self, documents: list[RagDocument]) -> None:
        self._documents = documents
        self._term_counts: list[Counter[str]] = []
        self._doc_freq: Counter[str] = Counter()
        self._idf: dict[str, float] = {}

        for document in documents:
            counts = Counter(tokenize(f"{document.title}\n{document.content}"))
            self._term_counts.append(counts)
            for term in counts:
                self._doc_freq[term] += 1

        total = max(len(documents), 1)
        self._idf = {
            term: math.log((total + 1) / (frequency + 1)) + 1.0
            for term, frequency in self._doc_freq.items()
        }

    @property
    def documents(self) -> list[RagDocument]:
        """返回全部文档。"""
        return self._documents

    def search(self, query: str, top_k: int = 5) -> list[SearchHit]:
        """按相关性返回前 top_k 条文档。"""
        query_terms = tokenize(query)
        if not query_terms:
            return []

        scored: list[SearchHit] = []
        for document, counts in zip(self._documents, self._term_counts):
            score = 0.0
            for term in query_terms:
                if term in counts:
                    score += counts[term] * self._idf.get(term, 1.0)
            if score > 0:
                scored.append(SearchHit(document=document, score=round(score, 4)))

        scored.sort(key=lambda hit: hit.score, reverse=True)
        return scored[: max(top_k, 1)]

    def contains_identifier(self, identifier: str) -> bool:
        """判断源码中是否存在该标识符（按词边界精确匹配）。"""
        return bool(self.find_documents(identifier))

    def find_documents(self, identifier: str) -> list[RagDocument]:
        """返回包含指定标识符的源码文档。"""
        if not identifier:
            return []
        pattern = re.compile(rf"(?<![A-Za-z0-9_]){re.escape(identifier)}(?![A-Za-z0-9_])")
        matched = []
        for document in self._documents:
            if document.kind == "code" and pattern.search(document.content):
                matched.append(document)
        return matched
