"""服务配置：所有配置项均支持环境变量或 .env 文件覆盖。"""

from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """AI 服务的运行时配置。"""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ---- 基础信息 ----
    app_name: str = "sss-ai-service"
    environment: str = "dev"
    host: str = "0.0.0.0"
    port: int = 8000
    log_level: str = "INFO"

    # Java 后端调用本服务时携带的内部令牌，对应请求头 X-Internal-Token
    internal_token: str = "change-me"

    # ---- 大模型 ----
    # openai：调用 OpenAI 兼容接口；mock：返回确定性数据，便于本地联调与测试
    llm_provider: Literal["openai", "mock"] = "openai"
    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_api_key: str = ""
    llm_model: str = "deepseek-chat"
    llm_temperature: float = 0.2
    llm_timeout_seconds: int = 120
    llm_max_retries: int = 1

    # ---- LightRAG（可选）----
    # 开启后优先使用 LightRAG 做检索，不可用时自动回退到本地检索
    lightrag_enabled: bool = False
    lightrag_base_url: str = "http://127.0.0.1:9621"
    lightrag_api_key: str = ""
    lightrag_query_mode: str = "mix"
    lightrag_timeout_seconds: int = 30

    # ---- 检索与文本长度限制 ----
    rag_top_k: int = 5
    rag_max_code_chars: int = 30000
    rag_max_section_chars: int = 8000

    @property
    def llm_ready(self) -> bool:
        """大模型是否可用：mock 模式始终可用，openai 模式必须配置密钥。"""
        return self.llm_provider == "mock" or bool(self.llm_api_key.strip())


@lru_cache
def get_settings() -> Settings:
    """获取全局配置单例。"""
    return Settings()
