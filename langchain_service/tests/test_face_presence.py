"""人脸在场检测测试：真实 OpenCV 实现 + 错误码（不再有 mock 分支）。"""

import base64

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.services.face_presence_service import FacePresenceService, normalize_image_base64

client = TestClient(app)
HEADERS = {"X-Internal-Token": "test-token"}


def _blank_png_base64(width: int = 64, height: int = 48) -> str:
    """生成一张纯色 PNG（无人脸），base64 编码。"""
    import cv2
    import numpy as np

    image = np.zeros((height, width, 3), dtype=np.uint8)
    ok, buffer = cv2.imencode(".png", image)
    assert ok
    return base64.b64encode(buffer.tobytes()).decode("ascii")


def test_normalize_image_base64_strips_data_url() -> None:
    assert normalize_image_base64("data:image/png;base64, ABC ") == "ABC"
    assert normalize_image_base64(" A B ") == "AB"


def test_presence_blank_image_reports_no_face() -> None:
    response = client.post(
        "/internal/ai/face/presence",
        json={"imageBase64": _blank_png_base64()},
        headers=HEADERS,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["present"] is False
    assert data["faceCount"] == 0
    assert data["boxes"] == []
    assert data["provider"] == "opencv"
    assert data["model"] == "haarcascade_frontalface_default"


def test_presence_requires_internal_token() -> None:
    response = client.post(
        "/internal/ai/face/presence",
        json={"imageBase64": _blank_png_base64()},
    )
    assert response.status_code == 401


def test_presence_empty_image_rejected() -> None:
    response = client.post(
        "/internal/ai/face/presence",
        json={"imageBase64": "   "},
        headers=HEADERS,
    )
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_IMAGE"


def test_presence_invalid_base64_rejected() -> None:
    response = client.post(
        "/internal/ai/face/presence",
        json={"imageBase64": "not-base64!!"},
        headers=HEADERS,
    )
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_IMAGE"


def test_presence_accepts_optional_min_face_size() -> None:
    response = client.post(
        "/internal/ai/face/presence",
        json={"imageBase64": _blank_png_base64(), "minFaceSize": 80},
        headers=HEADERS,
    )
    assert response.status_code == 200
    assert response.json()["present"] is False


def test_service_uses_opencv_detector() -> None:
    from app.core.config import Settings

    service = FacePresenceService(Settings())
    result = service.presence(_blank_png_base64())
    assert result.provider == "opencv"
    assert result.present is False