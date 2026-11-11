"""语音转写（ASR）数据模型。"""

from pydantic import Field

from app.schemas.common import CamelModel


class AsrSegment(CamelModel):
    """一段转写文本及其在音频中的时间范围（秒）。"""

    start: float = 0.0
    end: float = 0.0
    text: str = ""


class AsrTranscribeResponse(CamelModel):
    """整段音频转写结果（对应答辩契约 A1/A2 的响应体）。"""

    text: str = ""
    segments: list[AsrSegment] = Field(default_factory=list)
    duration_ms: int = 0
    confidence: float = 0.0
    provider: str = "mock"
    model: str = ""
