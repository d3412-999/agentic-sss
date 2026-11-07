"""本地开发启动入口：python main.py

端口/主机取自 .env 的 AI_PORT / AI_HOST，默认值与根 .env.example 一致（8000）。
"""

import uvicorn

from app.core.config import get_settings

if __name__ == "__main__":
    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level.lower(),
        reload=True,
    )
