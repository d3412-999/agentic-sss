"""FastAPI 应用入口：注册路由、异常处理与生命周期。"""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app import __version__
from app.api.deps import get_rag_service, get_template_service
from app.api.router import api_router
from app.core.config import get_settings
from app.core.exceptions import AiServiceError

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """服务启动与关闭时的资源管理。"""
    settings = get_settings()
    logging.basicConfig(
        level=getattr(logging, settings.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )
    logger.info(
        "AI 服务启动：环境=%s，大模型=%s，LightRAG=%s",
        settings.environment,
        settings.llm_provider,
        "启用" if settings.lightrag_enabled else "关闭",
    )
    yield
    await get_template_service().close()
    await get_rag_service().close()
    logger.info("AI 服务已关闭")


def create_app() -> FastAPI:
    """创建 FastAPI 应用。"""
    settings = get_settings()
    app = FastAPI(
        title="课程设计智能答辩与评分系统 - AI 服务",
        description="答辩出题、答辩评分、报告评审、源码语义评审与一致性检查",
        version=__version__,
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.exception_handler(AiServiceError)
    async def handle_ai_service_error(request: Request, exc: AiServiceError) -> JSONResponse:
        """把业务异常转换为统一错误结构。"""
        logger.warning("请求 %s 处理失败：%s", request.url.path, exc.message)
        return JSONResponse(
            status_code=exc.status_code,
            content={"code": exc.code, "message": exc.message},
        )

    app.include_router(api_router)
    return app


app = create_app()
