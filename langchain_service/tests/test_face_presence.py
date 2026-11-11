"""人脸在场检测接口测试：mock provider + 错误码 + opencv 未安装降级。"""

import base64

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.core.exceptions import AiServiceError
from app.main import app
from app.services.face_presence_service import FacePresenceService

client = TestClient(app)
HEADERS = {"X-Internal-Token": "test-token"}


def _base64_payload(size: int) -> str:
    """生成指定长度的合法 base64 串（mock 模式按长度判定是否有人）。"""
    return base64.b64encode(b"x" * size).decode("ascii")


def test_presence_mock_detects_face() -> None:
    """mock 模式：base64 长度 > 100 视为有人。"""
    response = client.post(
        "/internal/ai/face/presence",
        json={"imageBase64": _base64_payload(200)},
        headers=HEADERS,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["present"] is True
    assert data["faceCount"] == 1
    assert data["boxes"] == []
    assert data["provider"] == "mock"
    assert data["model"]


def test_presence_mock_reports_no_face_for_short_payload() -> None:
    """mock 模式：短 base64 视为无人。"""
    response = client.post(
        "/internal/ai/face/presence",
        json={"imageBase64": "abc"},
        headers=HEADERS,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["present"] is False
    assert data["faceCount"] == 0


def test_presence_requires_internal_token() -> None:
    """缺少 X-Internal-Token 时 401。"""
    response = client.post(
        "/internal/ai/face/presence",
        json={"imageBase64": _base64_payload(200)},
    )
    assert response.status_code == 401


def test_presence_empty_image_rejected() -> None:
    """空图片返回 400 INVALID_IMAGE。"""
    response = client.post(
        "/internal/ai/face/presence",
        json={"imageBase64": "   "},
        headers=HEADERS,
    )
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "INVALID_IMAGE"


def test_presence_accepts_optional_min_face_size() -> None:
    """minFaceSize 可选，透传后仍按 mock 规则返回。"""
    response = client.post(
        "/internal/ai/face/presence",
        json={"imageBase64": _base64_payload(200), "minFaceSize": 80},
        headers=HEADERS,
    )
    assert response.status_code == 200
    assert response.json()["present"] is True


def test_opencv_provider_without_cv2_is_not_configured() -> None:
    """opencv 模式：可选依赖未安装时返回 503 FACE_NOT_CONFIGURED。"""
    try:
        import cv2  # noqa: F401
    except ImportError:
        pass
    else:  # pragma: no cover - 本测试环境预期未安装 opencv
        pytest.skip("当前环境已安装 opencv，跳过未配置用例")

    service = FacePresenceService(Settings(face_provider="opencv"))
    with pytest.raises(AiServiceError) as exc_info:
        service.presence(_base64_payload(200), None)
    assert exc_info.value.code == "FACE_NOT_CONFIGURED"
