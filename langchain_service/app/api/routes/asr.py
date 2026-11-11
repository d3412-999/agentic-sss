"""语音转写接口：整段音频上传 + 实时流式 WebSocket。

HTTP 路由挂在内部路由 `/internal/ai/asr`（统一 X-Internal-Token 校验）；
WebSocket 单独挂载：握手不走 HTTP 依赖链，令牌在握手阶段手动校验，失败以 4401 关闭。
"""

import json
import logging

from fastapi import APIRouter, Depends, File, Form, UploadFile, WebSocket, WebSocketDisconnect
from starlette.concurrency import run_in_threadpool

from app.api.deps import get_asr_service
from app.core.config import get_settings
from app.schemas.asr import AsrTranscribeResponse
from app.services.asr_service import AsrService

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/asr", tags=["语音转写"])
# 注意：WebSocket 的 path 自带 /internal/ai 前缀，由 router.py 直接挂到 api_router（不经内部依赖链）
ws_router = APIRouter(tags=["语音转写"])

WS_AUTH_FAILED_CODE = 4401


@router.post("/transcribe", response_model=AsrTranscribeResponse)
async def transcribe_audio(
    file: UploadFile = File(...),
    language: str | None = Form(default=None),
    service: AsrService = Depends(get_asr_service),
) -> AsrTranscribeResponse:
    """上传整段音频转写（对应答辩契约 A1）。"""
    content = await file.read()
    return await run_in_threadpool(
        service.transcribe,
        content,
        file.filename or "",
        file.content_type or "",
        language,
    )


@ws_router.websocket("/internal/ai/asr/stream")
async def stream_transcribe(websocket: WebSocket) -> None:
    """实时流式转写：PCM16LE 16k mono 二进制帧 → partial/final 文本事件（契约 A2）。"""
    settings = get_settings()
    # WebSocket 不能用 Header 依赖，这里手动校验握手令牌；失败按契约以 4401 关闭
    token = websocket.headers.get("x-internal-token")
    if settings.internal_token and token != settings.internal_token:
        await websocket.close(code=WS_AUTH_FAILED_CODE)
        return

    await websocket.accept()
    service = get_asr_service()
    language = settings.asr_language
    pcm = bytearray()

    try:
        while True:
            message = await websocket.receive()
            if message["type"] == "websocket.disconnect":
                return

            text = message.get("text")
            if text is not None:
                payload = _parse_json_object(text)
                if payload is None:
                    continue
                message_type = payload.get("type")
                if message_type == "config":
                    language = str(payload.get("language") or language)
                    await websocket.send_json({"type": "config", "language": language})
                elif message_type == "end":
                    break
                continue

            chunk = message.get("bytes")
            if chunk:
                pcm.extend(chunk)
                partial = service.partial_text(len(pcm), language)
                if partial:
                    await websocket.send_json({"type": "partial", "text": partial})
    except WebSocketDisconnect:
        return

    result = await run_in_threadpool(service.transcribe_pcm, bytes(pcm), 16000, language)
    payload = result.model_dump(by_alias=True)
    await websocket.send_json(
        {
            "type": "final",
            "text": payload.get("text", ""),
            "segments": payload.get("segments", []),
            "durationMs": payload.get("durationMs", 0),
        }
    )
    await websocket.close()


def _parse_json_object(text: str) -> dict[str, object] | None:
    """解析 JSON 文本帧；非法 JSON / 非对象返回 None（忽略该帧，不断连接）。"""
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None
