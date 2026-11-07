"""课程设计报告评审的数据模型。"""

from pydantic import Field

from app.schemas.common import CamelModel, DimensionScore
from app.schemas.materials import ReportMaterial, TopicBrief


class ReportEvaluateRequest(CamelModel):
    """报告评审请求（对应 API 文档 21.3）。"""

    submission_id: str = ""
    topic: TopicBrief | None = None
    report: ReportMaterial = Field(default_factory=ReportMaterial)
    requirements: str = ""


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
    summary: str
    dimensions: list[DimensionScore] = Field(default_factory=list)
    highlights: list[str] = Field(default_factory=list)
    problems: list[ReportProblem] = Field(default_factory=list)
    page_count: int = 0
