"""人脸在场检测服务：provider 可在 mock / opencv 之间切换。

只判断画面里“有没有人”，不做身份识别；图片只在内存中解码检测，
不写磁盘、不落库，满足答辩场景的隐私约定。
"""

from __future__ import annotations

import base64
import binascii
from pathlib import Path

from app.core.config import Settings
from app.core.exceptions import AiServiceError
from app.schemas.face import FaceBox, FacePresenceResponse

MOCK_PROVIDER = "mock"
MOCK_MODEL = "mock-face"
OPENCV_PROVIDER = "opencv"
OPENCV_MODEL = "haarcascade_frontalface_default"
# mock 模式：base64 长度超过该阈值视为“画面里有人”
MOCK_BASE64_THRESHOLD = 100
DEFAULT_FACE_MIN_SIZE = 60
MIN_FACE_MIN_SIZE = 10


def normalize_image_base64(image_base64: str) -> str:
    """去掉 data URL 前缀与空白，返回纯 base64 串。"""
    text = (image_base64 or "").strip()
    if text.startswith("data:") and "," in text:
        text = text.split(",", 1)[1]
    return "".join(text.split())


class FacePresenceService:
    """人脸在场检测服务。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._cascade: object | None = None

    def presence(self, image_base64: str, min_face_size: int | None = None) -> FacePresenceResponse:
        """检测一张摄像头画面里是否有人脸。"""
        image = normalize_image_base64(image_base64)
        if not image:
            raise AiServiceError("图像内容为空", status_code=400, code="INVALID_IMAGE")

        size = max(
            MIN_FACE_MIN_SIZE,
            int(min_face_size if min_face_size is not None else self._settings.face_min_size or DEFAULT_FACE_MIN_SIZE),
        )

        if self._settings.face_provider == "mock":
            present = len(image) > MOCK_BASE64_THRESHOLD
            return FacePresenceResponse(
                present=present,
                face_count=1 if present else 0,
                boxes=[],
                provider=MOCK_PROVIDER,
                model=MOCK_MODEL,
            )

        return self._detect_opencv(image, size)

    def _detect_opencv(self, image_base64: str, min_face_size: int) -> FacePresenceResponse:
        """OpenCV haarcascade 检测；cv2/numpy 懒加载，未安装返回 503 FACE_NOT_CONFIGURED。"""
        try:
            import cv2  # 懒加载可选依赖，mock 模式不需要
            import numpy as np
        except ImportError as exc:
            raise AiServiceError(
                "未安装 opencv-python-headless，请先执行 pip install -r requirements-optional.txt",
                status_code=503,
                code="FACE_NOT_CONFIGURED",
            ) from exc

        try:
            raw = base64.b64decode(image_base64, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise AiServiceError("图像 base64 解码失败，请上传 jpeg/png 的 base64", status_code=400, code="INVALID_IMAGE") from exc
        if not raw:
            raise AiServiceError("图像内容为空", status_code=400, code="INVALID_IMAGE")

        buffer = np.frombuffer(raw, dtype=np.uint8)
        image = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
        if image is None:
            raise AiServiceError("图像无法解码，请上传 jpeg/png 图片", status_code=400, code="INVALID_IMAGE")

        cascade = self._get_cascade(cv2)
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        faces = cascade.detectMultiScale(
            gray,
            scaleFactor=1.1,
            minNeighbors=5,
            minSize=(min_face_size, min_face_size),
        )
        # 检测完立即丢弃图像引用，不做任何落盘；FACE_MODEL_DIR 仅为后续自定义模型预留
        del image, gray
        boxes = [FaceBox(x=int(x), y=int(y), w=int(w), h=int(h)) for x, y, w, h in faces]
        return FacePresenceResponse(
            present=bool(boxes),
            face_count=len(boxes),
            boxes=boxes,
            provider=OPENCV_PROVIDER,
            model=OPENCV_MODEL,
        )

    def _get_cascade(self, cv2: object) -> object:
        """加载 OpenCV 内置 haarcascade（CAScade 对象缓存）。"""
        if self._cascade is not None:
            return self._cascade
        cascade_path = Path(cv2.data.haarcascades) / "haarcascade_frontalface_default.xml"  # type: ignore[attr-defined]
        cascade = cv2.CascadeClassifier(str(cascade_path))  # type: ignore[attr-defined]
        if cascade.empty():  # type: ignore[attr-defined]
            raise AiServiceError(
                f"OpenCV haarcascade 模型加载失败：{cascade_path}",
                status_code=503,
                code="FACE_NOT_CONFIGURED",
            )
        self._cascade = cascade
        return cascade
