"""优秀作品模板库的数据模型。"""

from typing import Any

from pydantic import Field

from app.schemas.common import CamelModel


class TemplateIndexRequest(CamelModel):
    """把一份优秀作品写入模板库。"""

    doc_id: str
    title: str
    content: str
    category: str = "EXCELLENT_WORK"
    metadata: dict[str, Any] = Field(default_factory=dict)


class TemplateIndexResponse(CamelModel):
    """模板库写入结果。"""

    doc_id: str
    status: str
    method: str
    message: str = ""


class TemplateDeleteResponse(CamelModel):
    """模板库删除结果。"""

    doc_id: str
    deleted: bool
    message: str = ""


class TemplateSearchRequest(CamelModel):
    """模板库检索请求。"""

    query: str
    top_k: int = 3


class TemplateSearchHit(CamelModel):
    """模板库检索命中。"""

    doc_id: str
    title: str
    score: float
    excerpt: str


class TemplateSearchResponse(CamelModel):
    """模板库检索结果。"""

    hits: list[TemplateSearchHit] = Field(default_factory=list)
    method: str = "local"
