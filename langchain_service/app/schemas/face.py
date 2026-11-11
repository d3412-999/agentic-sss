"""人脸在场检测数据模型：只判断画面里有没有人，不做身份识别。"""

from pydantic import Field

from app.schemas.common import CamelModel


class FaceBox(CamelModel):
    """一个人脸框（像素坐标，左上角 + 宽高）。"""

    x: int = 0
    y: int = 0
    w: int = 0
    h: int = 0


class FacePresenceRequest(CamelModel):
    """在场检测请求：图片以 base64（jpeg/png）传入。"""

    image_base64: str = ""
    min_face_size: int | None = None


class FacePresenceResponse(CamelModel):
    """在场检测结果（对应答辩契约 A3）。"""

    present: bool = False
    face_count: int = 0
    boxes: list[FaceBox] = Field(default_factory=list)
    provider: str = "mock"
    model: str = ""
