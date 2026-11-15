"""测试替身：不依赖真实大模型/ASR 模型，只替换外部调用。"""

from typing import Any, Callable

from app.services.llm_service import LlmResult


class FakeLlm:
    """假的 LlmService：直接返回预先构造的 Pydantic 草稿，并记录提示词。"""

    def __init__(self, data: Any) -> None:
        self._data = data
        self.tasks: list[str] = []
        self.prompts: list[str] = []
        self.systems: list[str] = []

    async def generate_json(
        self,
        *,
        task: str,
        schema: Any,
        system_prompt: str,
        user_prompt: str,
    ) -> LlmResult:
        self.tasks.append(task)
        self.prompts.append(user_prompt)
        self.systems.append(system_prompt)
        data = self._data(task) if callable(self._data) else self._data
        if not isinstance(data, schema):
            data = schema.model_validate(data)
        return LlmResult(data=data, model="fake-llm", usage={"total_tokens": 1})


class FakeRag:
    """假的 RagService：返回空上下文，避免依赖本地索引。"""

    async def retrieve(self, question: str, documents: list[Any], top_k: int | None = None) -> Any:
        from app.rag.rag_service import RagResult

        return RagResult(method="local-rag", contexts=[], hits=[])


class FakeTemplates:
    """假的 TemplateService：不检索优秀作品模板。"""

    def search_contexts(self, query: str, top_k: int = 2, max_chars: int = 1500) -> list[str]:
        return []