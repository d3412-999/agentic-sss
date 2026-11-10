"""通用工具：JSON 提取。"""

import json
import re
from typing import Any

# 匹配 ```json ... ``` 或 ``` ... ``` 代码块
_CODE_FENCE_PATTERN = re.compile(r"```(?:json)?\s*(.*?)```", re.DOTALL | re.IGNORECASE)


def extract_json_object(raw_text: str) -> dict[str, Any]:
    """从大模型输出中提取 JSON 对象。

    大模型经常在 JSON 前后附加说明文字或 Markdown 代码块，这里做容错处理：
    先尝试去掉代码块，再截取第一个 { 到最后一个 } 之间的内容。
    """
    if not raw_text or not raw_text.strip():
        raise ValueError("大模型返回内容为空")

    text = raw_text.strip()

    # 优先提取 ```json 代码块中的内容
    fence_match = _CODE_FENCE_PATTERN.search(text)
    if fence_match:
        text = fence_match.group(1).strip()

    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        raise ValueError("大模型返回内容中未找到 JSON 对象")

    json_text = text[start : end + 1]
    try:
        data = json.loads(json_text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"JSON 解析失败：{exc}") from exc

    if not isinstance(data, dict):
        raise ValueError("大模型返回的 JSON 顶层必须是对象")
    return data
