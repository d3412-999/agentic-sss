"""服务配置：所有配置项均支持环境变量或 .env 文件覆盖。

变量命名与仓库根目录 .env.example 对齐（AI_HOST / AI_PORT / AI_INTERNAL_TOKEN），
同时兼容旧的 HOST / PORT / INTERNAL_TOKEN 写法。
"""

from functools import lru_cache
from typing import Literal

from pydantic import AliasChoices, Field
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
    host: str = Field("127.0.0.1", validation_alias=AliasChoices("AI_HOST", "HOST"))
    port: int = Field(8000, validation_alias=AliasChoices("AI_PORT", "PORT"))
    log_level: str = "INFO"

    # Java 后端调用本服务时携带的内部令牌，对应请求头 X-Internal-Token。
    # 必须与后端 application.yaml 的 sss.ai.internal-token（AI_INTERNAL_TOKEN）一致。
    internal_token: str = Field(
        "change-me",
        validation_alias=AliasChoices("AI_INTERNAL_TOKEN", "INTERNAL_TOKEN"),
    )

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

    # ---- 优秀作品模板库 ----
    # 使用 Python 标准库 sqlite3 持久化；LightRAG 可用时同时写入 LightRAG
    template_db_path: str = "./data/template_library.db"

    # ---- 语音转写（ASR，可选）----
    # mock：离线返回固定中文文本；sherpa_onnx：需安装 requirements-optional.txt 并准备本地模型
    asr_provider: Literal["mock", "sherpa_onnx"] = "mock"
    asr_model_dir: str = "./models/sherpa-onnx-paraformer-zh"
    asr_language: str = "zh-CN"

    # ---- 人脸在场检测（可选）----
    # mock：按 base64 长度模拟；opencv：用内置 haarcascade 检测，只判“有没有人”，图像不落盘
    face_provider: Literal["mock", "opencv"] = "mock"
    face_min_size: int = 60
    # 预留给后续自定义人脸模型；OpenCV 内置 haarcascade 当前不使用该目录
    face_model_dir: str = "./models/face"

    @property
    def llm_ready(self) -> bool:
        """大模型是否可用：mock 模式始终可用，openai 模式必须配置密钥。"""
        return self.llm_provider == "mock" or bool(self.llm_api_key.strip())


@lru_cache
def get_settings() -> Settings:
    """获取全局配置单例。"""
    return Settings()
