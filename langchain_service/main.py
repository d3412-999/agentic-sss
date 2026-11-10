"""本地开发启动入口：python main.py"""

import uvicorn

from app.core.config import get_settings

if __name__ == "__main__":
    settings = get_settings()
    # 只监听 app 目录，避免 .venv / data 变化触发无意义的重载
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.environment == "dev",
        reload_dirs=["app"],
    )
