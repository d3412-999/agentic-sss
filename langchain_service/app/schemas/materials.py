"""课程设计材料模型：题目、源码、报告。

这些模型既是 Java 后端调用本服务的入参，也是内部提示词的数据来源。
"""

from typing import Any

from pydantic import Field

from app.schemas.common import CamelModel


class OfferingBrief(CamelModel):
    """开课信息摘要。"""

    course_name: str = ""
    semester: str = ""


class TopicBrief(CamelModel):
    """题目摘要。"""

    topic_id: str = ""
    title: str
    requirements: str = ""
    difficulty: str = "MEDIUM"


class SourceFile(CamelModel):
    """单个 Java 源文件。"""

    path: str
    content: str = ""


class SourceIssue(CamelModel):
    """静态分析工具发现的源码问题。"""

    tool: str = ""
    rule: str = ""
    severity: str = ""
    file: str = ""
    line: int | None = None
    message: str = ""


class SourceMaterial(CamelModel):
    """源码材料：文件、度量指标与静态分析问题。"""

    submission_id: str = ""
    java_files: list[SourceFile] = Field(default_factory=list)
    metrics: dict[str, Any] = Field(default_factory=dict)
    issues: list[SourceIssue] = Field(default_factory=list)


class ReportSection(CamelModel):
    """报告章节。"""

    title: str
    content: str = ""


class ReportMaterial(CamelModel):
    """课程设计报告材料：章节结构与页数。"""

    submission_id: str = ""
    sections: list[ReportSection] = Field(default_factory=list)
    page_count: int = 0


class SubmissionFileBrief(CamelModel):
    """提交物里的单个文件摘要。

    一次提交可以有 1~9 个源码包 / 1~9 份报告。Java 侧把正文合并成一段送进来，
    同时用这个结构告诉模型「这段文字由哪几份文件组成」，否则模型看到的是
    一整块代码，无法判断「这是三个模块」还是「一个巨型文件」。
    """

    name: str = ""
    size: int = 0
    sha256: str = ""
    java_files: int = 0
    code_lines: int = 0
    page_count: int | None = None
