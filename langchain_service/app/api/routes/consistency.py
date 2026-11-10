"""报告与源码一致性检查接口。"""

from fastapi import APIRouter, Depends

from app.api.deps import get_consistency_service
from app.schemas.consistency import ConsistencyRequest, ConsistencyResponse
from app.services.consistency_service import ConsistencyService

router = APIRouter(tags=["一致性检查"])


@router.post("/consistency", response_model=ConsistencyResponse)
async def check_consistency(
    request: ConsistencyRequest,
    service: ConsistencyService = Depends(get_consistency_service),
) -> ConsistencyResponse:
    """核对报告描述与源码实现是否一致（对应 API 文档 21.5）。"""
    return await service.check(request)
