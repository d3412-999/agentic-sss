"""源码语义评审的数据模型。

兼容两种入参：
1. 内部完整结构：javaFiles / metrics / issues；
2. Java 后端 AiServiceClient 的简化结构：codeSnippet / buildTool / topicTitle。
"""

from pydantic import Field

from app.schemas.common import CamelModel, DimensionScore
from app.schemas.materials import SourceMaterial, SubmissionFileBrief


class CodeReviewRequest(SourceMaterial):
    """源码语义评审请求（对应 API 文档 21.4）。"""

    selection_id: str = ""
    topic_title: str = ""
    build_tool: str = "PLAIN"
    # Java 侧把源码 zip 中的 Java 文件拼成一段文本后通过 codeSnippet 传入
    code_snippet: str = ""
    # 多文件提交时的文件清单（1~9 份源码包）。旧调用方不传，保持空列表即可。
    source_files: list[SubmissionFileBrief] = Field(default_factory=list)


class CodeReviewDraft(CamelModel):
    """大模型输出的源码评审草稿。"""

    design_score: float = 0
    design_comment: str = ""
    doc_score: float = 0
    doc_comment: str = ""
    suggestions: list[str] = Field(default_factory=list)
    summary: str = ""


class FileReviewBrief(CamelModel):
    """单个文件的评审摘要，作为汇总（reduce）阶段的输入。

    只送摘要不送原始代码：9 份工程拼原文必然爆上下文，
    而汇总阶段真正需要的是「每份是什么水平、有哪些问题」。
    """

    name: str = ""
    score: float | None = None
    summary: str = ""
    problems: list[str] = Field(default_factory=list)
    java_files: int = 0
    code_lines: int = 0
    # 该文件的分片评审是否失败/被截断，汇总时要据此降低置信度
    degraded: bool = False


class CodeAggregateRequest(CamelModel):
    """多文件源码评审的汇总请求（map-reduce 的 reduce 阶段）。"""

    submission_id: str = ""
    selection_id: str = ""
    topic_title: str = ""
    build_tool: str = "PLAIN"
    files: list[FileReviewBrief] = Field(default_factory=list)


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
