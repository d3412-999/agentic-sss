"""RAG 文档构建：把源码与报告拆成可检索的片段。"""

from dataclasses import dataclass, field

from app.schemas.materials import ReportMaterial, SourceMaterial


@dataclass(frozen=True)
class RagDocument:
    """一条可检索的文档片段。"""

    doc_id: str
    kind: str  # code / report
    title: str
    content: str
    metadata: dict[str, str] = field(default_factory=dict)


def build_rag_documents(source: SourceMaterial, report: ReportMaterial) -> list[RagDocument]:
    """将源码文件与报告章节转换为 RAG 文档列表。"""
    documents: list[RagDocument] = []

    for index, java_file in enumerate(source.java_files):
        documents.append(
            RagDocument(
                doc_id=f"code:{index}:{java_file.path}",
                kind="code",
                title=java_file.path,
                content=java_file.content,
                metadata={"path": java_file.path},
            )
        )

    for index, section in enumerate(report.sections):
        documents.append(
            RagDocument(
                doc_id=f"report:{index}:{section.title}",
                kind="report",
                title=section.title,
                content=section.content,
                metadata={"section": section.title},
            )
        )

    return documents
