"""优秀作品模板库接口。"""

from fastapi import APIRouter, Depends

from app.api.deps import get_template_service
from app.schemas.template import (
    TemplateDeleteResponse,
    TemplateIndexRequest,
    TemplateIndexResponse,
    TemplateSearchRequest,
    TemplateSearchResponse,
)
from app.services.template_service import TemplateService

router = APIRouter(prefix="/template", tags=["优秀作品模板库"])


@router.post("/index", response_model=TemplateIndexResponse)
async def index_template(
    request: TemplateIndexRequest,
    service: TemplateService = Depends(get_template_service),
) -> TemplateIndexResponse:
    """把优秀作品写入模板库（LightRAG 优先，本地兜底）。"""
    return await service.index(request)


@router.post("/search", response_model=TemplateSearchResponse)
async def search_template(
    request: TemplateSearchRequest,
    service: TemplateService = Depends(get_template_service),
) -> TemplateSearchResponse:
    """按关键词检索模板库，用于教师端预览与报告评审 RAG 参考。"""
    return service.search(request.query, request.top_k)


@router.delete("/{doc_id}", response_model=TemplateDeleteResponse)
async def delete_template(
    doc_id: str,
    service: TemplateService = Depends(get_template_service),
) -> TemplateDeleteResponse:
    """从模板库删除文档。"""
    return await service.delete(doc_id)
