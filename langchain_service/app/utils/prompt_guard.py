"""提示注入防护：把学生材料包成不可信数据块，并识别常见的评审操纵语句。

设计原则：
- 学生提交的源码、报告、答辩转写稿是「待评审数据」，不是给模型的指令；
- 用显式分隔符包住材料，并中和材料内部伪造的分隔符，避免数据块被提前闭合；
- 检测结果只用于在 summary 追加一行提示，不改变任何输出 JSON 结构。
"""

from __future__ import annotations

import re
from typing import Iterable

UNTRUSTED_LABEL_OPEN = "<<<UNTRUSTED_STUDENT_MATERIAL:{label}>>>"
UNTRUSTED_LABEL_CLOSE = "<<<END_UNTRUSTED_STUDENT_MATERIAL:{label}>>>"

INJECTION_WARNING = "（疑似材料内含评审操纵语句，已忽略并按固定标准评分）"

# 单条材料最多返回的命中片段数，避免把整段材料塞进日志/摘要
MAX_DETECTIONS = 5
# 单个命中片段的最大展示长度
MAX_SNIPPET_CHARS = 80

# 材料内部若出现伪造的分隔符，替换成中性标记，防止提前闭合不可信数据块
_DELIMITER_PATTERN = re.compile(
    r"<<<\s*/?\s*(?:END_)?UNTRUSTED_STUDENT_MATERIAL[^>]*>>>",
    re.IGNORECASE,
)
_DELIMITER_REPLACEMENT = "[已过滤的疑似分隔符]"

# 已知的评审操纵表达；命中片段原样（压缩空白并截断后）返回
_INJECTION_PATTERNS: tuple[re.Pattern[str], ...] = (
    # ---- 英文 ----
    re.compile(
        r"ignore\s+(?:all\s+|any\s+)?(?:the\s+)?(?:previous|prior|above|preceding)\s+"
        r"(?:instructions?|prompts?|rules?|directions?)",
        re.IGNORECASE,
    ),
    re.compile(
        r"disregard\s+(?:[^\n]{0,60}?)(?:instructions?|prompts?|rules?)",
        re.IGNORECASE,
    ),
    re.compile(
        r"forget\s+(?:all\s+|any\s+)?(?:the\s+)?(?:previous|prior|above)\s+"
        r"(?:instructions?|prompts?|rules?)",
        re.IGNORECASE,
    ),
    re.compile(r"\bsystem\s+prompt\b", re.IGNORECASE),
    re.compile(r"\byou\s+are\s+now\b", re.IGNORECASE),
    re.compile(r"\bdeveloper\s+mode\b", re.IGNORECASE),
    re.compile(
        r"(?:raise|increase|boost|override)\s+(?:the\s+|my\s+|this\s+)?"
        r"(?:score|grade|marks?)",
        re.IGNORECASE,
    ),
    re.compile(
        r"give\s+(?:me|him|her|them|this\s+\w+)?\s*(?:a\s+)?"
        r"(?:100|full\s+marks?|perfect\s+score|highest\s+score)",
        re.IGNORECASE,
    ),
    re.compile(r"\b(?:full\s+marks?|perfect\s+score)\b", re.IGNORECASE),
    re.compile(r"\bonly\s+output\s+(?:the\s+)?json\b", re.IGNORECASE),
    # ---- 中文 ----
    re.compile(r"忽略\s*(?:以上|之前|前面|上述|所有)(?:的)?(?:指令|提示|要求|规则|内容)?"),
    re.compile(r"(?:以上|之前|前面|上述)(?:的)?(?:指令|提示|要求)(?:全部|一律|都)?(?:忽略|作废)"),
    re.compile(r"评分标准"),
    re.compile(r"直接给满分"),
    re.compile(r"给\s*(?:我|他|她)?\s*(?:100|满分|高分|满分吧)"),
    re.compile(r"(?:不要|无需|跳过|不用)\s*(?:评审|评分|检查|审核)"),
    re.compile(r"(?:评价|评分|打分|评定)\s*(?:为|成|给)?\s*(?:优秀|满分|高分|100)"),
    re.compile(r"(?:修改|篡改|更改|覆盖)\s*(?:评分|打分|分数|标准)"),
)


def _neutralize_delimiters(text: str) -> str:
    """把材料内部伪造的不可信数据分隔符替换成中性标记。"""
    return _DELIMITER_PATTERN.sub(_DELIMITER_REPLACEMENT, text or "")


def wrap_untrusted(label: str, content: str) -> str:
    """把学生材料包进显式分隔符，声明它是数据而不是指令。

    label 只用于阅读定位（如 ``source_file:BookService.java``）；
    content 内部若出现伪造的结束分隔符，会被中和，不会提前闭合数据块。
    """
    safe_label = (label or "material").strip() or "material"
    safe_content = _neutralize_delimiters(content or "")
    return (
        f"{UNTRUSTED_LABEL_OPEN.format(label=safe_label)}\n"
        f"{safe_content}\n"
        f"{UNTRUSTED_LABEL_CLOSE.format(label=safe_label)}"
    )


def detect_injection(text: str) -> list[str]:
    """检测文本中疑似操纵评审的语句，返回去重后的命中片段（最多 5 条）。"""
    content = text or ""
    if not content.strip():
        return []

    found: list[str] = []
    seen: set[str] = set()
    for pattern in _INJECTION_PATTERNS:
        for match in pattern.finditer(content):
            snippet = re.sub(r"\s+", " ", match.group(0)).strip()
            if not snippet:
                continue
            snippet = snippet[:MAX_SNIPPET_CHARS]
            key = snippet.lower()
            if key in seen:
                continue
            seen.add(key)
            found.append(snippet)
            if len(found) >= MAX_DETECTIONS:
                return found
    return found


def injection_warning(detections: Iterable[str]) -> str:
    """根据命中结果生成单行警告；无命中返回空字符串。"""
    return INJECTION_WARNING if any(detections) else ""


def append_warning(summary: str, detections: Iterable[str]) -> str:
    """把注入警告追加到 summary 末尾；无命中时原样返回。"""
    # Iterable 可能是一次性生成器，先物化再用
    items = list(detections)
    warning = injection_warning(items)
    base = (summary or "").strip()
    if not warning:
        return base
    return f"{base}\n{warning}" if base else warning


def sanitize_material(label: str, text: str) -> tuple[str, list[str]]:
    """一次性完成「包裹 + 检测」，返回 (包裹后的文本, 命中片段列表)。"""
    return wrap_untrusted(label, text), detect_injection(text)
