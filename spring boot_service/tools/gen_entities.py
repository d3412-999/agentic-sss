"""Generate MyBatis-Plus entities and mappers from the DDL.

The DDL is the single source of truth: column order, types and comments all
come from V1__init_schema.sql so the Java model cannot drift from the schema.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL_PATH = ROOT / "src/main/resources/db/migration/V1__init_schema.sql"
ENTITY_DIR = ROOT / "src/main/java/org/spring_web/spring_boot_service/entity"
MAPPER_DIR = ROOT / "src/main/java/org/spring_web/spring_boot_service/mapper"

JAVA_TYPES = {
    "BIGINT": "Long",
    "INT": "Integer",
    "SMALLINT": "Integer",
    "TINYINT": "Integer",
    "DECIMAL": "BigDecimal",
    "DATETIME": "LocalDateTime",
    "DATE": "LocalDate",
    "VARCHAR": "String",
    "CHAR": "String",
    "TEXT": "String",
    "MEDIUMTEXT": "String",
    "LONGTEXT": "String",
    "JSON": "String",
}

# 这些表的 version 列是乐观锁；submission_version.version 是业务版本号，不能加 @Version
LOCK_VERSION_TABLES = {
    "offering",
    "topic",
    "selection",
    "code_analysis",
    "report_evaluation",
    "defense_session",
    "grade",
}


def camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def pascal(name: str) -> str:
    return "".join(p[:1].upper() + p[1:] for p in name.split("_"))


def parse() -> list[dict]:
    sql = SQL_PATH.read_text(encoding="utf-8")
    pattern = re.compile(
        r"CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\) ENGINE = InnoDB.*?COMMENT = '([^']*)';",
        re.S,
    )
    tables = []
    for table_name, body, table_comment in pattern.findall(sql):
        columns = []
        for raw in body.splitlines():
            line = raw.strip()
            if not line or line.startswith("--"):
                continue
            if re.match(r"^(PRIMARY|UNIQUE|KEY|CONSTRAINT|INDEX)\b", line, re.I):
                continue
            line = line.rstrip(",")
            match = re.match(r"^([a-z_][a-z0-9_]*)\s+([A-Z]+(?:\([^)]*\))?)\s*(.*)$", line)
            if not match:
                continue
            name, sql_type, rest = match.groups()
            comment_match = re.search(r"COMMENT\s+'([^']*)'", rest)
            columns.append(
                {
                    "name": name,
                    "sql_type": sql_type,
                    "java_type": JAVA_TYPES[sql_type.split("(")[0]],
                    "comment": comment_match.group(1) if comment_match else "",
                    "not_null": "NOT NULL" in rest,
                }
            )
        tables.append(
            {
                "table": table_name,
                "class": pascal(table_name),
                "comment": table_comment,
                "columns": columns,
            }
        )
    return tables


def render_entity(table: dict) -> str:
    columns = table["columns"]
    needs_big_decimal = any(c["java_type"] == "BigDecimal" for c in columns)
    needs_datetime = any(c["java_type"] == "LocalDateTime" for c in columns)
    needs_date = any(c["java_type"] == "LocalDate" for c in columns)

    imports = [
        "com.baomidou.mybatisplus.annotation.IdType",
        "com.baomidou.mybatisplus.annotation.TableId",
        "com.baomidou.mybatisplus.annotation.TableLogic",
        "com.baomidou.mybatisplus.annotation.TableName",
        "com.baomidou.mybatisplus.annotation.Version",
        "lombok.Data",
    ]
    if needs_big_decimal:
        imports.append("java.math.BigDecimal")
    if needs_datetime:
        imports.append("java.time.LocalDateTime")
    if needs_date:
        imports.append("java.time.LocalDate")
    imports = sorted(set(imports))

    lines = [
        "package org.spring_web.spring_boot_service.entity;",
        "",
        *[f"import {item};" for item in imports],
        "",
        "/**",
        f" * {table['comment']}。",
        " */",
        "@Data",
        f'@TableName("{table["table"]}")',
        f"public class {table['class']} {{",
        "",
    ]

    for column in columns:
        lines.append("    /**")
        lines.append(f"     * {column['comment'] if column['comment'] else column['name']}")
        lines.append("     */")
        if column["name"] == "id":
            lines.append('    @TableId(value = "id", type = IdType.ASSIGN_ID)')
        elif column["name"] == "deleted":
            lines.append("    @TableLogic")
        elif column["name"] == "version" and table["table"] in LOCK_VERSION_TABLES:
            lines.append("    @Version")
        lines.append(f"    private {column['java_type']} {camel(column['name'])};")
        lines.append("")

    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def render_mapper(table: dict) -> str:
    return "\n".join(
        [
            "package org.spring_web.spring_boot_service.mapper;",
            "",
            "import com.baomidou.mybatisplus.core.mapper.BaseMapper;",
            f"import org.spring_web.spring_boot_service.entity.{table['class']};",
            "import org.apache.ibatis.annotations.Mapper;",
            "",
            f"/** {table['comment']} Mapper。 */",
            "@Mapper",
            f"public interface {table['class']}Mapper extends BaseMapper<{table['class']}> {{",
            "}",
            "",
        ]
    )


def main() -> None:
    tables = parse()
    ENTITY_DIR.mkdir(parents=True, exist_ok=True)
    MAPPER_DIR.mkdir(parents=True, exist_ok=True)
    for table in tables:
        (ENTITY_DIR / f"{table['class']}.java").write_text(render_entity(table), encoding="utf-8")
        (MAPPER_DIR / f"{table['class']}Mapper.java").write_text(render_mapper(table), encoding="utf-8")
    print(f"generated {len(tables)} entities and mappers")
    for table in tables:
        print(f"  {table['table']:20s} -> {table['class']:22s} {len(table['columns'])} columns")


if __name__ == "__main__":
    main()
