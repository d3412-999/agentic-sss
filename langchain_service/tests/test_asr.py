"""语音转写接口测试：mock provider（离线）+ 整段上传 + WebSocket 流式 + 错误码。"""

import io
import wave

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.core.config import Settings
from app.core.exceptions import AiServiceError
from app.main import app
from app.services.asr_service import AsrService

client = TestClient(app)
HEADERS = {"X-Internal-Token": "test-token"}


def _make_wav(duration_ms: int = 1000, sample_rate: int = 16000) -> bytes:
    """在内存里生成 16k mono PCM16 WAV，避免测试依赖外部音频文件。"""
    frames = int(sample_rate * duration_ms / 1000)
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(sample_rate)
        handle.writeframes(b"\x00\x00" * frames)
    return buffer.getvalue()


def test_transcribe_mock_returns_fixed_text_and_segments() -> None:
    """mock 模式：WAV 上传返回固定中文文本、2 段 segments 与真实时长。"""
    response = client.post(
        "/internal/ai/asr/transcribe",
        files={"file": ("answer.wav", _make_wav(1000), "audio/wav")},
        headers=HEADERS,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["text"]
    assert len(data["segments"]) == 2
    assert 900 <= data["durationMs"] <= 1100
    assert 0 < data["confidence"] <= 1
    assert data["provider"] == "mock"
    assert data["model"]
    # 对外字段必须是 camelCase（key 契约见答辩契约 A1）
    assert {"durationMs", "faceCount"} - set(data) == {"faceCount"}


def test_transcribe_requires_internal_token() -> None:
    """缺少 X-Internal-Token 时 401。"""
    response = client.post(
        "/internal/ai/asr/transcribe",
        files={"file": ("answer.wav", _make_wav(100), "audio/wav")},
    )
    assert response.status_code == 401


def test_transcribe_empty_audio_rejected() -> None:
    """空音频返回 400 INVALID_AUDIO。"""
    response = client.post(
        "/internal/ai/asr/transcribe",
        files={"file": ("empty.wav", b"", "audio/wav")},
        headers=HEADERS,
    )
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "INVALID_AUDIO"


def test_transcribe_mock_accepts_non_wav() -> None:
    """mock 模式对非 WAV（webm/ogg）也能返回结果，兜底时长 3000ms。"""
    response = client.post(
        "/internal/ai/asr/transcribe",
        files={"file": ("answer.webm", b"not-a-real-webm", "audio/webm")},
        headers=HEADERS,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["provider"] == "mock"
    assert data["durationMs"] == 3000
    assert len(data["segments"]) == 2


def test_stream_mock_partial_and_final() -> None:
    """WebSocket 流式：二进制 PCM 帧产生 partial，end 后返回 final。"""
    with client.websocket_connect("/internal/ai/asr/stream", headers=HEADERS) as websocket:
        websocket.send_json({"type": "config", "language": "zh-CN"})
        websocket.send_bytes(b"\x00\x00" * 8000)  # 16k mono：0.5 秒

        messages = [websocket.receive_json(), websocket.receive_json()]
        assert "partial" in [message["type"] for message in messages]

        websocket.send_json({"type": "end"})
        final = websocket.receive_json()

    assert final["type"] == "final"
    assert final["text"]
    assert len(final["segments"]) == 2
    assert final["durationMs"] == 500


def test_stream_without_audio_still_finalizes() -> None:
    """没有音频帧直接 end：返回空文本 final 并正常关闭。"""
    with client.websocket_connect("/internal/ai/asr/stream", headers=HEADERS) as websocket:
        websocket.send_json({"type": "end"})
        final = websocket.receive_json()

    assert final["type"] == "final"
    assert final["text"] == ""
    assert final["durationMs"] == 0


def test_stream_rejects_bad_token_with_4401() -> None:
    """握手令牌错误时按契约以 4401 关闭。"""
    with pytest.raises(WebSocketDisconnect) as exc_info:
        with client.websocket_connect(
            "/internal/ai/asr/stream",
            headers={"X-Internal-Token": "wrong-token"},
        ):
            pass
    assert exc_info.value.code == 4401


def test_sherpa_provider_without_model_is_not_configured() -> None:
    """sherpa_onnx 模式：模型目录缺失返回 503 ASR_NOT_CONFIGURED（无需安装依赖）。"""
    settings = Settings(asr_provider="sherpa_onnx", asr_model_dir="./.missing-sherpa-model")
    service = AsrService(settings)
    with pytest.raises(AiServiceError) as exc_info:
        service.transcribe(_make_wav(200), "answer.wav", "audio/wav", "zh-CN")
    assert exc_info.value.code == "ASR_NOT_CONFIGURED"


def test_sherpa_provider_rejects_non_wav() -> None:
    """sherpa_onnx 模式：非 WAV 返回 400 INVALID_AUDIO，避免静默跑 mock。"""
    settings = Settings(asr_provider="sherpa_onnx")
    service = AsrService(settings)
    with pytest.raises(AiServiceError) as exc_info:
        service.transcribe(b"not-a-wav", "answer.webm", "audio/webm", None)
    assert exc_info.value.code == "INVALID_AUDIO"
