"""应用配置。所有配置项均可通过环境变量或同目录 .env 覆盖。

变量命名与仓库根目录 .env.example 对齐：
    AI_HOST / AI_PORT / AI_INTERNAL_TOKEN
其余字段用 pydantic-settings 默认的大写下划线命名（LLM_API_KEY 等）。
"""

from functools import lru_cache

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    app_name: str = "sss-ai-service"
    environment: str = "dev"
    host: str = Field("127.0.0.1", validation_alias=AliasChoices("AI_HOST", "HOST"))
    port: int = Field(8000, validation_alias=AliasChoices("AI_PORT", "PORT"))
    log_level: str = "INFO"

    # Java 后端调用本服务时携带的内部令牌，对应 X-Internal-Token。
    # 必须与 application.yaml 的 sss.ai.internal-token 一致。
    internal_token: str = Field(
        "change-me",
        validation_alias=AliasChoices("AI_INTERNAL_TOKEN", "INTERNAL_TOKEN"),
    )

    # LLM（答辩题生成、报告评审、答辩评分）——骨架阶段尚未使用
    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_api_key: str = ""
    llm_model: str = "deepseek-chat"

    # LightRAG 服务，用于报告-源码一致性检索——骨架阶段尚未接入
    lightrag_base_url: str = "http://127.0.0.1:9621"


@lru_cache
def get_settings() -> Settings:
    return Settings()
