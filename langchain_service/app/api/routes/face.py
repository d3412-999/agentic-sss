"""人脸在场检测接口：只判“有没有人”，不做身份识别、不保存图片。"""

from fastapi import APIRouter, Depends
from starlette.concurrency import run_in_threadpool

from app.api.deps import get_face_presence_service
from app.schemas.face import FacePresenceRequest, FacePresenceResponse
from app.services.face_presence_service import FacePresenceService

router = APIRouter(prefix="/face", tags=["人脸在场检测"])


@router.post("/presence", response_model=FacePresenceResponse)
async def face_presence(
    request: FacePresenceRequest,
    service: FacePresenceService = Depends(get_face_presence_service),
) -> FacePresenceResponse:
    """检查一张摄像头画面里是否有人脸（对应答辩契约 A3）。"""
    return await run_in_threadpool(service.presence, request.image_base64, request.min_face_size)
