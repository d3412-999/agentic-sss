"""源码语义评审的数据模型。

兼容两种入参：
1. 内部完整结构：javaFiles / metrics / issues；
2. Java 后端 AiServiceClient 的简化结构：codeSnippet / buildTool / topicTitle。
"""

from pydantic import Field

from app.schemas.common import CamelModel, DimensionScore
from app.schemas.materials import SourceMaterial


class CodeReviewRequest(SourceMaterial):
    """源码语义评审请求（对应 API 文档 21.4）。"""

    selection_id: str = ""
    topic_title: str = ""
    build_tool: str = "PLAIN"
    # Java 侧把源码 zip 中的 Java 文件拼成一段文本后通过 codeSnippet 传入
    code_snippet: str = ""


class CodeReviewDraft(CamelModel):
    """大模型输出的源码评审草稿。"""

    design_score: float = 0
    design_comment: str = ""
    doc_score: float = 0
    doc_comment: str = ""
    suggestions: list[str] = Field(default_factory=list)
    summary: str = ""


class CodeReviewResponse(CamelModel):
    """源码语义评审结果。

    score 是后端落库用的综合分（设计 60% + 注释 40%），
    dimensions 仍然保留两个维度的明细。
    """

    submission_id: str = ""
    score: float = 0
    model: str = ""
    dimensions: list[DimensionScore] = Field(default_factory=list)
    suggestions: list[str] = Field(default_factory=list)
    problems: list[str] = Field(default_factory=list)
    summary: str = ""
