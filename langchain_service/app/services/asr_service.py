"""语音转写（ASR）服务：provider 可在 mock / sherpa_onnx 之间切换。

- mock：离线可用，任意非空音频返回固定中文文本与 2 段 segments，保证联调与测试不依赖模型；
- sherpa_onnx：懒加载可选依赖（见 requirements-optional.txt），只接受 16k mono WAV；
  依赖未安装或模型目录缺失时返回 503 ASR_NOT_CONFIGURED，音频非法返回 400 INVALID_AUDIO。

设计约束：sherpa_onnx 只在 provider 命中时 import，缺包不会影响 mock 模式与既有接口。
"""

from __future__ import annotations

import array
import io
import sys
import wave
from dataclasses import dataclass
from pathlib import Path

from app.core.config import Settings
from app.core.exceptions import AiServiceError
from app.schemas.asr import AsrSegment, AsrTranscribeResponse

# 实时流默认音频格式：16k mono PCM16（前端 ScriptProcessor 采集后拼 WAV）
DEFAULT_SAMPLE_RATE = 16000
DEFAULT_SAMPLE_WIDTH = 2
DEFAULT_CHANNELS = 1
# mock 模式下无法从音频头算出时长时的兜底值
MOCK_FALLBACK_DURATION_MS = 3000
MOCK_CONFIDENCE = 0.9
MOCK_MODEL = "mock-asr"

MOCK_TEXT = "老师好，我的课程设计实现了图书管理系统的借阅功能，使用 JavaFX 和 JDBC 完成界面与数据访问。"
MOCK_SEGMENT_TEXTS = (
    "老师好，我的课程设计实现了图书管理系统的借阅功能，",
    "使用 JavaFX 和 JDBC 完成界面与数据访问。",
)


@dataclass(frozen=True)
class WavInfo:
    """标准库 wave 解析出的 WAV 元信息。"""

    frames: int
    sample_rate: int
    channels: int
    sample_width: int
    data: bytes

    @property
    def duration_ms(self) -> int:
        if self.sample_rate <= 0:
            return 0
        return int(round(self.frames * 1000 / self.sample_rate))


def parse_wav(audio_bytes: bytes) -> WavInfo | None:
    """用标准库解析 WAV；不是合法 WAV 时返回 None（不抛异常，便于 mock 容忍任意格式）。"""
    if not audio_bytes:
        return None
    try:
        with wave.open(io.BytesIO(audio_bytes), "rb") as handle:
            frames = handle.getnframes()
            return WavInfo(
                frames=frames,
                sample_rate=handle.getframerate(),
                channels=handle.getnchannels(),
                sample_width=handle.getsampwidth(),
                data=handle.readframes(frames),
            )
    except (wave.Error, EOFError, ValueError):
        return None


def pcm16_to_samples(
    pcm_bytes: bytes,
    sample_rate: int = DEFAULT_SAMPLE_RATE,
    channels: int = DEFAULT_CHANNELS,
) -> list[float]:
    """把 16bit PCM（小端，可多声道）转成 sherpa-onnx 需要的 [-1, 1] float 采样。

    多声道时只取第一个声道（答辩音频以 16k mono 为准）。
    """
    if len(pcm_bytes) < 2:
        return []
    pcm = array.array("h")
    pcm.frombytes(pcm_bytes[: len(pcm_bytes) // 2 * 2])
    if sys.byteorder == "big":
        pcm.byteswap()
    if channels > 1:
        pcm = pcm[::channels]
    return [max(-1.0, min(1.0, value / 32768.0)) for value in pcm]


class AsrService:
    """语音转写服务：整段上传 + 流式 PCM 共用同一套识别逻辑。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._recognizer: object | None = None
        self._model_name: str = ""

    @property
    def provider(self) -> str:
        """当前 provider（mock / sherpa_onnx）。"""
        return self._settings.asr_provider

    # ---------------------------------------------------------------- 对外能力

    def transcribe(
        self,
        audio_bytes: bytes,
        filename: str = "",
        content_type: str = "",
        language: str | None = None,
    ) -> AsrTranscribeResponse:
        """整段音频转写（HTTP 上传路径）。"""
        if not audio_bytes:
            raise AiServiceError("音频内容为空", status_code=400, code="INVALID_AUDIO")
        language = language or self._settings.asr_language

        if self._settings.asr_provider == "mock":
            wav = parse_wav(audio_bytes)
            duration_ms = wav.duration_ms if wav else MOCK_FALLBACK_DURATION_MS
            return self._mock_response(duration_ms)

        return self._transcribe_sherpa(audio_bytes, language)

    def transcribe_pcm(
        self,
        pcm_bytes: bytes,
        sample_rate: int = DEFAULT_SAMPLE_RATE,
        language: str | None = None,
    ) -> AsrTranscribeResponse:
        """实时流结束后的一次性识别（mock 与整段识别共用固定文本）。"""
        language = language or self._settings.asr_language
        duration_ms = int(round(len(pcm_bytes) / DEFAULT_SAMPLE_WIDTH / max(sample_rate, 1) * 1000))

        if self._settings.asr_provider == "mock":
            if not pcm_bytes:
                return AsrTranscribeResponse(
                    text="",
                    segments=[],
                    duration_ms=0,
                    confidence=0.0,
                    provider="mock",
                    model=MOCK_MODEL,
                )
            return self._mock_response(duration_ms)

        if not pcm_bytes:
            return AsrTranscribeResponse(
                text="",
                segments=[],
                duration_ms=0,
                confidence=0.0,
                provider="sherpa_onnx",
                model=Path(self._settings.asr_model_dir).name,
            )
        return self._decode_sherpa(pcm16_to_samples(pcm_bytes), sample_rate)

    def partial_text(self, received_bytes: int, language: str | None = None) -> str:
        """流式过程中的 partial 文本；mock 返回确定性占位，sherpa 暂不产出中间结果。"""
        if self._settings.asr_provider != "mock" or received_bytes <= 0:
            return ""
        seconds = received_bytes / DEFAULT_SAMPLE_WIDTH / DEFAULT_SAMPLE_RATE
        return f"正在识别…（已接收 {seconds:.1f} 秒音频）"

    # ---------------------------------------------------------------- mock

    def _mock_response(self, duration_ms: int) -> AsrTranscribeResponse:
        """确定性的 mock 结果：固定文本 + 均分的 2 段 segments。"""
        duration_ms = max(int(duration_ms), 0)
        half_ms = duration_ms // 2
        segments = [
            AsrSegment(
                start=0.0,
                end=round(half_ms / 1000, 3),
                text=MOCK_SEGMENT_TEXTS[0],
            ),
            AsrSegment(
                start=round(half_ms / 1000, 3),
                end=round(duration_ms / 1000, 3),
                text=MOCK_SEGMENT_TEXTS[1],
            ),
        ]
        return AsrTranscribeResponse(
            text=MOCK_TEXT,
            segments=segments,
            duration_ms=duration_ms,
            confidence=MOCK_CONFIDENCE,
            provider="mock",
            model=MOCK_MODEL,
        )

    # ---------------------------------------------------------------- sherpa_onnx

    def _transcribe_sherpa(self, audio_bytes: bytes, language: str) -> AsrTranscribeResponse:
        wav = parse_wav(audio_bytes)
        if wav is None:
            raise AiServiceError(
                "sherpa_onnx 模式仅支持 WAV（16k mono PCM16），请先在前端转成 WAV 后重试",
                status_code=400,
                code="INVALID_AUDIO",
            )
        if wav.sample_width != DEFAULT_SAMPLE_WIDTH:
            raise AiServiceError(
                "sherpa_onnx 模式仅支持 16bit PCM WAV",
                status_code=400,
                code="INVALID_AUDIO",
            )
        recognizer = self._get_sherpa_recognizer()
        return self._decode_with(
            recognizer,
            pcm16_to_samples(wav.data, wav.sample_rate or DEFAULT_SAMPLE_RATE, wav.channels),
            wav.sample_rate or DEFAULT_SAMPLE_RATE,
        )

    def _decode_sherpa(self, samples: list[float], sample_rate: int) -> AsrTranscribeResponse:
        recognizer = self._get_sherpa_recognizer()
        return self._decode_with(recognizer, samples, sample_rate)

    def _decode_with(self, recognizer: object, samples: list[float], sample_rate: int) -> AsrTranscribeResponse:
        try:
            stream = recognizer.create_stream()  # type: ignore[attr-defined]
            stream.accept_waveform(sample_rate, samples)
            recognizer.decode_stream(stream)  # type: ignore[attr-defined]
            result = stream.result
            text = (getattr(result, "text", "") or "").strip()
        except Exception as exc:  # noqa: BLE001 - 统一转换为业务异常
            raise AiServiceError(f"语音识别失败：{exc}", status_code=500, code="ASR_FAILED") from exc

        duration_ms = int(round(len(samples) * 1000 / max(sample_rate, 1)))
        segments = self._build_sherpa_segments(text, duration_ms)
        return AsrTranscribeResponse(
            text=text,
            segments=segments,
            duration_ms=duration_ms,
            # paraformer 不提供整体置信度，这里按 0 返回，不伪造
            confidence=0.0,
            provider="sherpa_onnx",
            model=self._model_name or Path(self._settings.asr_model_dir).name,
        )

    @staticmethod
    def _build_sherpa_segments(text: str, duration_ms: int) -> list[AsrSegment]:
        """sherpa-onnx 结果没有开箱可用的分句信息，整段作为 1 条 segment 返回。"""
        if not text:
            return []
        return [AsrSegment(start=0.0, end=round(duration_ms / 1000, 3), text=text)]

    def _get_sherpa_recognizer(self) -> object:
        """懒加载 sherpa_onnx 与本地模型；不可用时返回 503 ASR_NOT_CONFIGURED。"""
        if self._recognizer is not None:
            return self._recognizer

        model_dir = Path(self._settings.asr_model_dir)
        tokens_path = model_dir / "tokens.txt"
        if not model_dir.is_dir() or not tokens_path.is_file():
            raise AiServiceError(
                f"sherpa-onnx 模型目录不存在或缺少 tokens.txt：{model_dir}",
                status_code=503,
                code="ASR_NOT_CONFIGURED",
            )
        try:
            import sherpa_onnx  # 懒加载可选依赖，mock 模式不需要
        except ImportError as exc:
            raise AiServiceError(
                "未安装 sherpa-onnx，请先执行 pip install -r requirements-optional.txt",
                status_code=503,
                code="ASR_NOT_CONFIGURED",
            ) from exc

        try:
            self._recognizer = self._build_recognizer(sherpa_onnx, model_dir, tokens_path)
        except AiServiceError:
            raise
        except Exception as exc:  # noqa: BLE001 - 模型结构/版本差异统一报未配置
            raise AiServiceError(
                f"sherpa-onnx 识别器初始化失败：{exc}",
                status_code=503,
                code="ASR_NOT_CONFIGURED",
            ) from exc
        return self._recognizer

    def _build_recognizer(self, sherpa_onnx: object, model_dir: Path, tokens_path: Path) -> object:
        """按模型目录结构构建 paraformer（优先）或 transducer 识别器。"""
        onnx_files = sorted(model_dir.glob("*.onnx"))
        if not onnx_files:
            raise AiServiceError(
                f"模型目录中没有 .onnx 文件：{model_dir}",
                status_code=503,
                code="ASR_NOT_CONFIGURED",
            )

        paraformer = next((path for path in onnx_files if "paraformer" in path.name.lower()), None)
        if paraformer is None and len(onnx_files) == 1:
            paraformer = onnx_files[0]
        if paraformer is not None:
            self._model_name = paraformer.stem
            return sherpa_onnx.OfflineRecognizer.from_paraformer(  # type: ignore[attr-defined]
                paraformer=str(paraformer),
                tokens=str(tokens_path),
                num_threads=2,
                sample_rate=DEFAULT_SAMPLE_RATE,
                feature_dim=80,
                decoding_method="greedy_search",
                debug=False,
            )

        encoder = next((path for path in onnx_files if "encoder" in path.name.lower()), None)
        decoder = next((path for path in onnx_files if "decoder" in path.name.lower()), None)
        joiner = next((path for path in onnx_files if "joiner" in path.name.lower()), None)
        if encoder is not None and decoder is not None and joiner is not None:
            self._model_name = encoder.stem
            return sherpa_onnx.OfflineRecognizer.from_transducer(  # type: ignore[attr-defined]
                encoder=str(encoder),
                decoder=str(decoder),
                joiner=str(joiner),
                tokens=str(tokens_path),
                num_threads=2,
                sample_rate=DEFAULT_SAMPLE_RATE,
                feature_dim=80,
                decoding_method="greedy_search",
                debug=False,
            )

        raise AiServiceError(
            "无法识别模型结构：应为 paraformer 单 onnx，或 encoder/decoder/joiner 组合",
            status_code=503,
            code="ASR_NOT_CONFIGURED",
        )
