"""源码语义评审的数据模型。"""

from pydantic import Field

from app.schemas.common import CamelModel, DimensionScore
from app.schemas.materials import SourceMaterial


class CodeReviewRequest(SourceMaterial):
    """源码语义评审请求（对应 API 文档 21.4）。"""


class CodeReviewDraft(CamelModel):
    """大模型输出的源码评审草稿。"""

    design_score: float = 0
    design_comment: str = ""
    doc_score: float = 0
    doc_comment: str = ""
    suggestions: list[str] = Field(default_factory=list)
    summary: str = ""


class CodeReviewResponse(CamelModel):
    """源码语义评审结果。"""

    submission_id: str = ""
    dimensions: list[DimensionScore] = Field(default_factory=list)
    suggestions: list[str] = Field(default_factory=list)
    summary: str = ""
