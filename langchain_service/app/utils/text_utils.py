"""通用工具：文本截断、代码标识符提取。"""

import re
from typing import Iterable

from app.schemas.materials import ReportMaterial, SourceMaterial

# Java 标识符：大驼峰类名、方法名、带下划线常量
_JAVA_IDENTIFIER_PATTERN = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]{2,}\b")
# 报告中反引号包裹的代码标识符，如 `BookService`
_BACKTICK_IDENTIFIER_PATTERN = re.compile(r"`([A-Za-z_][A-Za-z0-9_\.]{2,})`")

# 常见类型与关键字，避免被当成“报告提到的类”
_IGNORED_IDENTIFIERS = {
    "Java", "String", "Integer", "Long", "Boolean", "List", "Map", "Set", "Optional",
    "Controller", "Service", "Repository", "Impl", "Util", "Utils", "Exception",
    "System", "Object", "Class", "Override", "Public", "Private", "Static", "Void",
    "Int", "Bool", "True", "False", "Null", "New", "Return", "Import", "Package",
    "Spring", "Boot", "Maven", "Gradle", "MySQL", "JDBC", "JavaFX", "JUnit",
}


def truncate_text(text: str, limit: int, suffix: str = "\n……（内容过长已截断）") -> str:
    """按字符数截断文本。"""
    if not text:
        return ""
    if limit <= 0 or len(text) <= limit:
        return text
    return text[:limit] + suffix


def clip_source_material(source: SourceMaterial, total_limit: int, per_file_limit: int) -> SourceMaterial:
    """裁剪源码内容，避免提示词过长。"""
    remaining = total_limit
    clipped_files = []
    for file in source.java_files:
        if remaining <= 0:
            break
        content = truncate_text(file.content, min(per_file_limit, remaining))
        clipped_files.append(file.model_copy(update={"content": content}))
        remaining -= len(content)
    return source.model_copy(update={"java_files": clipped_files})


def clip_report_material(report: ReportMaterial, section_limit: int, total_limit: int) -> ReportMaterial:
    """裁剪报告章节内容。"""
    remaining = total_limit
    clipped_sections = []
    for section in report.sections:
        if remaining <= 0:
            break
        content = truncate_text(section.content, min(section_limit, remaining))
        clipped_sections.append(section.model_copy(update={"content": content}))
        remaining -= len(content)
    return report.model_copy(update={"sections": clipped_sections})


def extract_identifiers(text: str) -> list[str]:
    """从报告文本中提取可能指向源码实体的标识符（去重并保持出现顺序）。"""
    found: list[str] = []
    seen: set[str] = set()

    for match in _BACKTICK_IDENTIFIER_PATTERN.finditer(text or ""):
        name = match.group(1).split(".")[0].strip()
        if _is_meaningful_identifier(name) and name not in seen:
            seen.add(name)
            found.append(name)

    for match in _JAVA_IDENTIFIER_PATTERN.finditer(text or ""):
        name = match.group(0)
        if _is_meaningful_identifier(name) and name not in seen:
            seen.add(name)
            found.append(name)

    return found


def split_report_text(text: str, max_sections: int = 30, chunk_size: int = 4000) -> list["ReportSection"]:
    """把一整段报告正文切分成章节。

    优先按「第 X 章 / 一、 / 1.1」等标题切分；识别不到标题时按固定长度切块，
    保证 Java 后端只传 reportText 时也能进入正常的报告评审流程。
    """
    from app.schemas.materials import ReportSection

    content = (text or "").strip()
    if not content:
        return []

    heading_pattern = re.compile(
        r"^\s*(?:#{1,6}\s*)?(?:第[一二三四五六七八九十百\d]+[章节部分]|[一二三四五六七八九十]+[、.]|\d+(?:\.\d+)*[、.\s])",
        re.MULTILINE,
    )
    matches = list(heading_pattern.finditer(content))

    sections: list[ReportSection] = []
    if len(matches) >= 2:
        for index, match in enumerate(matches[:max_sections]):
            start = match.start()
            end = matches[index + 1].start() if index + 1 < len(matches) else len(content)
            block = content[start:end].strip()
            if not block:
                continue
            title = block.splitlines()[0][:80]
            sections.append(ReportSection(title=title, content=block))
        return sections

    for index in range(0, len(content), chunk_size):
        chunk = content[index : index + chunk_size]
        sections.append(ReportSection(title=f"报告正文（第 {index // chunk_size + 1} 段）", content=chunk))
        if len(sections) >= max_sections:
            break
    return sections


def unique_preserve_order(items: Iterable[str]) -> list[str]:
    """去重并保持原顺序。"""
    result: list[str] = []
    seen: set[str] = set()
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def _is_meaningful_identifier(name: str) -> bool:
    """过滤过短、全小写、常见类型与关键字的标识符。"""
    if not name or len(name) < 3:
        return False
    if name in _IGNORED_IDENTIFIERS or name.lower() in {item.lower() for item in _IGNORED_IDENTIFIERS}:
        return False
    # 只保留大驼峰（类名）或包含下划线 / 点号的标识符，降低误报
    return bool(re.match(r"^[A-Z][A-Za-z0-9_]*$", name)) or "_" in name
