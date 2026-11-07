"""聚合所有路由。"""

from fastapi import APIRouter

from app.api.routes import code, consistency, defense, health, report

api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(defense.router)
api_router.include_router(report.router)
api_router.include_router(code.router)
api_router.include_router(consistency.router)
