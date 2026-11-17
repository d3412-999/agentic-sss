"""人脸在场检测服务：基于 OpenCV haarcascade。

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

PROVIDER = "opencv"
MODEL = "haarcascade_frontalface_default"
# 摄像头画面里人脸通常只占 10%~25% 宽度；60px 下限在 320 宽截图上会让正常坐姿的人脸漏检。
DEFAULT_FACE_MIN_SIZE = 40
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
        return self._detect_opencv(image, size)

    def _detect_opencv(self, image_base64: str, min_face_size: int) -> FacePresenceResponse:
        """OpenCV haarcascade 检测；解码失败/模型缺失返回明确错误码。"""
        try:
            import cv2
            import numpy as np
        except ImportError as exc:
            raise AiServiceError(
                "未安装 opencv-python-headless，请先执行 pip install -r requirements.txt",
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
        faces = self._detect_faces(cv2, cascade, gray, min_face_size)
        # 检测完立即丢弃图像引用，不做任何落盘
        del image, gray
        boxes = [FaceBox(x=int(x), y=int(y), w=int(w), h=int(h)) for x, y, w, h in faces]
        return FacePresenceResponse(
            present=bool(boxes),
            face_count=len(boxes),
            boxes=boxes,
            provider=PROVIDER,
            model=MODEL,
        )

    @staticmethod
    def _detect_faces(cv2: object, cascade: object, gray: object, min_size: int) -> list[tuple]:
        """多路检测：原图 → CLAHE 均衡 → 2 倍放大，降低背光/远距离漏检。

        真实摄像头场景里，学生坐着正常露出脸但只占画面 10% 左右时，固定 60px 的
        单路 haarcascade 会大量漏检；这里把下限降到 40px 并在失败时补两路预处理。
        """

        def detect(image, scale: float = 1.0) -> list[tuple]:
            pixel = max(MIN_FACE_MIN_SIZE, int(round(min_size * scale)))
            found = cascade.detectMultiScale(  # type: ignore[attr-defined]
                image,
                scaleFactor=1.1,
                minNeighbors=5,
                minSize=(pixel, pixel),
            )
            return [
                (int(x / scale), int(y / scale), int(w / scale), int(h / scale))
                for x, y, w, h in found
            ]

        faces = detect(gray)
        if faces:
            return faces

        # 背光/低对比：直方图均衡后再试一次
        equalized = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)  # type: ignore[attr-defined]
        faces = detect(equalized)
        if faces:
            return faces

        # 人脸在画面里太小：放大 2 倍后按 2 倍尺寸再找，坐标缩回原始画面
        enlarged = cv2.resize(gray, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)  # type: ignore[attr-defined]
        return detect(enlarged, scale=2.0)

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