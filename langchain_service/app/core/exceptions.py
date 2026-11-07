"""业务异常定义：统一转换为带 code / message 的错误响应。"""


class AiServiceError(Exception):
    """AI 服务业务异常基类。"""

    def __init__(self, message: str, status_code: int = 502, code: str = "AI_SERVICE_ERROR") -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.code = code


class LlmNotConfiguredError(AiServiceError):
    """大模型未配置或不可用。"""

    def __init__(self, message: str = "大模型未配置，请设置 LLM_API_KEY 或切换 LLM_PROVIDER=mock") -> None:
        super().__init__(message=message, status_code=503, code="LLM_NOT_CONFIGURED")


class LlmInvocationError(AiServiceError):
    """大模型调用失败（网络、超时、限流等）。"""

    def __init__(self, message: str) -> None:
        super().__init__(message=message, status_code=502, code="LLM_INVOCATION_FAILED")


class LlmResponseFormatError(AiServiceError):
    """大模型返回内容不符合约定结构。"""

    def __init__(self, message: str) -> None:
        super().__init__(message=message, status_code=502, code="LLM_RESPONSE_INVALID")
