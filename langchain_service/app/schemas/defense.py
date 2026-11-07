"""答辩相关数据模型：出题请求、题目、评分请求与结果。"""

from typing import Any

from pydantic import Field

from app.schemas.common import CamelModel, DimensionScore, SourceRef
from app.schemas.materials import OfferingBrief, ReportMaterial, SourceMaterial, TopicBrief


class DefenseConfig(CamelModel):
    """出题参数。"""

    question_count: int = 6
    focus: list[str] = Field(default_factory=list)
    language: str = "zh-CN"


class DefenseQuestionRequest(CamelModel):
    """生成答辩题目的请求（对应 API 文档 21.1）。"""

    request_id: str = ""
    offering: OfferingBrief = Field(default_factory=OfferingBrief)
    topics: list[TopicBrief] = Field(default_factory=list)
    source: SourceMaterial = Field(default_factory=SourceMaterial)
    report: ReportMaterial = Field(default_factory=ReportMaterial)
    config: DefenseConfig = Field(default_factory=DefenseConfig)


class DefenseQuestion(CamelModel):
    """单道答辩题。"""

    type: str
    difficulty: str = "MEDIUM"
    question: str
    expected_points: list[str] = Field(default_factory=list)
    reference_answer: str = ""
    source_refs: list[SourceRef] = Field(default_factory=list)
    max_answer_seconds: int = 180


class DefenseQuestionList(CamelModel):
    """大模型输出的题目列表。"""

    questions: list[DefenseQuestion] = Field(default_factory=list)


class DefenseQuestionResponse(CamelModel):
    """出题接口响应。"""

    request_id: str = ""
    questions: list[DefenseQuestion] = Field(default_factory=list)
    model: str = ""
    tokens: dict[str, int] = Field(default_factory=dict)


class EvaluateQuestion(CamelModel):
    """评分时使用的题目信息。"""

    id: str
    question: str
    expected_points: list[str] = Field(default_factory=list)


class TranscriptItem(CamelModel):
    """单题转写文本。"""

    question_id: str
    text: str = ""
    duration_ms: int = 0


class AudioItem(CamelModel):
    """单题音频元信息。"""

    question_id: str
    url: str = ""
    asr_confidence: float | None = None


class DefenseEvaluateRequest(CamelModel):
    """答辩评分请求（对应 API 文档 21.2）。"""

    defense_id: str = ""
    questions: list[EvaluateQuestion] = Field(default_factory=list)
    transcript: list[TranscriptItem] = Field(default_factory=list)
    audio: list[AudioItem] = Field(default_factory=list)


class QuestionScore(CamelModel):
    """单题得分。"""

    question_id: str
    score: float
    comment: str = ""


class DefenseFlag(CamelModel):
    """答辩异常标记。"""

    type: str
    message: str
    confidence: float = 0.0


class DefenseEvaluationDraft(CamelModel):
    """大模型输出的答辩评分草稿。"""

    summary: str = ""
    dimensions: list[DimensionScore] = Field(default_factory=list)
    question_scores: list[QuestionScore] = Field(default_factory=list)
    flags: list[DefenseFlag] = Field(default_factory=list)


class DefenseEvaluationResponse(CamelModel):
    """答辩评分结果。"""

    defense_id: str = ""
    score: float
    grade: str
    summary: str
    dimensions: list[DimensionScore] = Field(default_factory=list)
    question_scores: list[QuestionScore] = Field(default_factory=list)
    flags: list[DefenseFlag] = Field(default_factory=list)
    asr: dict[str, Any] = Field(default_factory=dict)
