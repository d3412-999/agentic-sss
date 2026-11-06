"""应用配置。所有配置项均可通过环境变量或 .env 覆盖。"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "sss-ai-service"
    environment: str = "dev"
    host: str = "0.0.0.0"
    port: int = 8000
    log_level: str = "INFO"

    # Java 后端调用本服务时携带的内部令牌，对应 X-Internal-Token
    internal_token: str = "change-me"

    # LLM（答辩题生成、报告评审、答辩评分）
    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_api_key: str = ""
    llm_model: str = "deepseek-chat"

    # LightRAG 服务，用于报告-源码一致性检索
    lightrag_base_url: str = "http://127.0.0.1:9621"


@lru_cache
def get_settings() -> Settings:
    return Settings()
