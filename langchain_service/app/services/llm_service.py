"""大模型调用服务：统一封装 OpenAI 兼容接口、JSON 解析与校验重试。"""

import logging
from dataclasses import dataclass, field
from typing import Any, Callable, TypeVar

from pydantic import BaseModel, ValidationError
from starlette.concurrency import run_in_threadpool

from app.core.config import Settings
from app.core.exceptions import (
    LlmInvocationError,
    LlmNotConfiguredError,
    LlmResponseFormatError,
)
from app.utils.json_utils import extract_json_object

logger = logging.getLogger(__name__)

SchemaType = TypeVar("SchemaType", bound=BaseModel)


@dataclass
class LlmResult:
    """一次大模型调用的结果。"""

    data: BaseModel
    model: str
    usage: dict[str, int] = field(default_factory=dict)


class LlmService:
    """负责提示词调用、JSON 提取与结构校验。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def generate_json(
        self,
        *,
        task: str,
        schema: type[SchemaType],
        system_prompt: str,
        user_prompt: str,
        mock_factory: Callable[[], dict[str, Any]] | None = None,
    ) -> LlmResult:
        """调用大模型并返回经过 Pydantic 校验的结构化结果。"""
        if self._settings.llm_provider == "mock":
            if mock_factory is None:
                raise LlmResponseFormatError(f"任务 {task} 缺少 mock 数据构造器")
            return LlmResult(
                data=schema.model_validate(mock_factory()),
                model="mock",
                usage={},
            )

        if not self._settings.llm_ready:
            raise LlmNotConfiguredError()

        current_prompt = user_prompt
        attempts = max(0, self._settings.llm_max_retries) + 1
        last_error: Exception | None = None

        for attempt in range(1, attempts + 1):
            try:
                raw_text, usage, model = await run_in_threadpool(
                    self._invoke, system_prompt, current_prompt
                )
                data = schema.model_validate(extract_json_object(raw_text))
                return LlmResult(data=data, model=model, usage=usage)
            except (ValueError, ValidationError) as exc:
                last_error = exc
                logger.warning("任务 %s 第 %s 次输出不符合 JSON 结构：%s", task, attempt, exc)
                current_prompt = (
                    user_prompt
                    + f"\n\n上一次输出无法通过校验：{exc}。"
                    + "\n请只返回修正后的 JSON，不要包含任何解释文字。"
                )

        raise LlmResponseFormatError(f"任务 {task} 的大模型输出无法解析：{last_error}")

    def _invoke(self, system_prompt: str, user_prompt: str) -> tuple[str, dict[str, int], str]:
        """在同步线程中调用 LangChain 的 ChatOpenAI。"""
        from langchain_core.messages import HumanMessage, SystemMessage
        from langchain_openai import ChatOpenAI

        settings = self._settings
        try:
            model = ChatOpenAI(
                model=settings.llm_model,
                api_key=settings.llm_api_key,
                base_url=settings.llm_base_url,
                temperature=settings.llm_temperature,
                timeout=settings.llm_timeout_seconds,
                max_retries=0,
            )
            response = model.invoke(
                [SystemMessage(content=system_prompt), HumanMessage(content=user_prompt)]
            )
        except Exception as exc:  # noqa: BLE001 - 统一转换为业务异常
            raise LlmInvocationError(f"调用大模型失败：{exc}") from exc

        content = response.content
        if not isinstance(content, str):
            content = str(content)

        usage = {}
        if getattr(response, "usage_metadata", None):
            usage = {
                str(key): int(value)
                for key, value in response.usage_metadata.items()
                if isinstance(value, int)
            }

        return content, usage, settings.llm_model
