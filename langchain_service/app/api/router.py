"""聚合所有路由。"""

from fastapi import APIRouter

from app.api.routes import defense, health, review

api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(defense.router)
api_router.include_router(review.router)
