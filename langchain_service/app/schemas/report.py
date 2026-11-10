"""课程设计报告评审的数据模型。

兼容两种入参：
1. 内部完整结构：report.sections；
2. Java 后端 AiServiceClient 的简化结构：reportText / topicTitle。
"""

from pydantic import Field

from app.schemas.common import CamelModel, DimensionScore
from app.schemas.materials import ReportMaterial, TopicBrief


class ReportEvaluateRequest(CamelModel):
    """报告评审请求（对应 API 文档 21.3）。"""

    submission_id: str = ""
    selection_id: str = ""
    topic: TopicBrief | None = None
    topic_title: str = ""
    report: ReportMaterial = Field(default_factory=ReportMaterial)
    requirements: str = ""
    # Java 侧抽取的 PDF/DOCX 正文；未抽取时为空字符串
    report_text: str = ""
    # Java 侧解析出的 PDF 页数；DOCX 或解析失败时为 null
    page_count: int | None = None


class ReportProblem(CamelModel):
    """报告中的问题与改进建议。"""

    section: str = ""
    message: str
    suggestion: str = ""


class ReportEvaluationDraft(CamelModel):
    """大模型输出的报告评审草稿。"""

    summary: str = ""
    dimensions: list[DimensionScore] = Field(default_factory=list)
    highlights: list[str] = Field(default_factory=list)
    problems: list[ReportProblem] = Field(default_factory=list)


class ReportEvaluationResponse(CamelModel):
    """报告评审结果。"""

    submission_id: str = ""
    score: float
    grade: str
    model: str = ""
    summary: str
    dimensions: list[DimensionScore] = Field(default_factory=list)
    highlights: list[str] = Field(default_factory=list)
    problems: list[ReportProblem] = Field(default_factory=list)
    page_count: int = 0
