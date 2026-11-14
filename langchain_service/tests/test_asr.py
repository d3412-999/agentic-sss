"""语音转写测试：真实实现（sherpa-onnx）的错误码、WAV 解析与识别器注入。"""

import io
import wave

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.core.config import Settings
from app.core.exceptions import AiServiceError
from app.main import app
from app.services.asr_service import AsrService, parse_wav, pcm16_to_samples

client = TestClient(app)
HEADERS = {"X-Internal-Token": "test-token"}


class _FakeStream:
    def __init__(self) -> None:
        self.result = type("Result", (), {"text": ""})()
        self.sample_rate = 0

    def accept_waveform(self, sample_rate: int, samples: list[float]) -> None:
        self.sample_rate = sample_rate


class _FakeRecognizer:
    text = "老师好，我完成了图书管理系统。"

    def create_stream(self) -> _FakeStream:
        return _FakeStream()

    def decode_stream(self, stream: _FakeStream) -> None:
        stream.result.text = self.text


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


def test_parse_wav_roundtrip() -> None:
    info = parse_wav(_make_wav(500))
    assert info is not None
    assert info.sample_rate == 16000
    assert info.channels == 1
    assert info.sample_width == 2
    assert 450 <= info.duration_ms <= 550
    assert parse_wav(b"not-a-wav") is None


def test_pcm16_to_samples_clamps_and_downmixes() -> None:
    samples = pcm16_to_samples(b"\xff\x7f\x00\x80", channels=1)
    assert len(samples) == 2
    assert samples[0] == pytest.approx(32767 / 32768, rel=1e-6)
    assert samples[1] == pytest.approx(-32768 / 32768, rel=1e-6)
    # 立体声只取第一个声道
    stereo = pcm16_to_samples(b"\x00\x10\x00\x20", channels=2)
    assert stereo == pytest.approx([0x1000 / 32768.0])


def test_transcribe_requires_internal_token() -> None:
    response = client.post(
        "/internal/ai/asr/transcribe",
        files={"file": ("answer.wav", _make_wav(100), "audio/wav")},
    )
    assert response.status_code == 401


def test_transcribe_empty_audio_rejected() -> None:
    response = client.post(
        "/internal/ai/asr/transcribe",
        files={"file": ("empty.wav", b"", "audio/wav")},
        headers=HEADERS,
    )
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_AUDIO"


def test_transcribe_non_wav_rejected() -> None:
    response = client.post(
        "/internal/ai/asr/transcribe",
        files={"file": ("answer.webm", b"not-a-real-webm", "audio/webm")},
        headers=HEADERS,
    )
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_AUDIO"


def test_transcribe_without_model_returns_503() -> None:
    """没有模型目录时必须返回 503，而不是返回假文本。"""
    response = client.post(
        "/internal/ai/asr/transcribe",
        files={"file": ("answer.wav", _make_wav(300), "audio/wav")},
        headers=HEADERS,
    )
    assert response.status_code == 503
    assert response.json()["code"] == "ASR_NOT_CONFIGURED"


def test_transcribe_with_injected_recognizer(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(AsrService, "_get_sherpa_recognizer", lambda self: _FakeRecognizer())
    response = client.post(
        "/internal/ai/asr/transcribe",
        files={"file": ("answer.wav", _make_wav(1000), "audio/wav")},
        headers=HEADERS,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["provider"] == "sherpa_onnx"
    assert data["text"] == _FakeRecognizer.text
    assert len(data["segments"]) == 1
    assert 900 <= data["durationMs"] <= 1100


def test_stream_without_audio_still_finalizes(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(AsrService, "_get_sherpa_recognizer", lambda self: _FakeRecognizer())
    with client.websocket_connect("/internal/ai/asr/stream", headers=HEADERS) as websocket:
        websocket.send_json({"type": "end"})
        final = websocket.receive_json()
    assert final["type"] == "final"
    assert final["text"] == ""
    assert final["durationMs"] == 0


def test_stream_with_pcm_returns_final_text(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(AsrService, "_get_sherpa_recognizer", lambda self: _FakeRecognizer())
    with client.websocket_connect("/internal/ai/asr/stream", headers=HEADERS) as websocket:
        websocket.send_json({"type": "config", "language": "zh-CN"})
        assert websocket.receive_json()["type"] == "config"
        websocket.send_bytes(b"\x00\x00" * 8000)  # 16k mono：0.5 秒
        websocket.send_json({"type": "end"})
        final = websocket.receive_json()
    assert final["type"] == "final"
    assert final["text"] == _FakeRecognizer.text
    assert final["durationMs"] == 500


def test_stream_rejects_bad_token_with_4401() -> None:
    with pytest.raises(WebSocketDisconnect) as exc_info:
        with client.websocket_connect(
            "/internal/ai/asr/stream",
            headers={"X-Internal-Token": "wrong-token"},
        ):
            pass
    assert exc_info.value.code == 4401


def test_service_reports_sherpa_provider_and_missing_model() -> None:
    service = AsrService(Settings(asr_model_dir="./.missing-sherpa-model"))
    assert service.provider == "sherpa_onnx"
    with pytest.raises(AiServiceError) as exc_info:
        service.transcribe(_make_wav(200), "answer.wav", "audio/wav", "zh-CN")
    assert exc_info.value.code == "ASR_NOT_CONFIGURED"