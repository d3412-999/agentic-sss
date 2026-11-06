"""FastAPI 应用入口。"""

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app import __version__
from app.api.router import api_router
from app.core.config import get_settings


@asynccontextmanager
async def lifespan(app: FastAPI):
    # TODO: 初始化 LLM 客户端、LightRAG 客户端、日志与链路追踪
    yield


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title="SSS AI Service",
        description="课程设计智能答辩与评分系统 - AI 能力服务（LangChain + LightRAG）",
        version=__version__,
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.include_router(api_router)
    return app


app = create_app()
