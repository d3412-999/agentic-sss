package org.spring_web.spring_boot_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.service.StudentRosterParser.ParsedRow;
import org.spring_web.spring_boot_service.service.StudentRosterParser.ParsedSheet;

/**
 * 名单解析规则的回归测试：这些用例就是老师在 PTA 上会遇到的粘贴 / 上传姿势，
 * 一旦分隔符或表头规则被改动，这里会先红。
 */
class StudentRosterParserTest {

    private static final int MAX_ROWS = 500;

    @Test
    @DisplayName("空格分隔：学号 姓名")
    void splitsBySpace() {
        ParsedSheet sheet = StudentRosterParser.parseText("2023010104 赵六", MAX_ROWS);
        assertThat(sheet.rows()).hasSize(1);
        ParsedRow row = sheet.rows().get(0);
        assertThat(row.studentNo()).isEqualTo("2023010104");
        assertThat(row.name()).isEqualTo("赵六");
        assertThat(row.email()).isEmpty();
    }

    @Test
    @DisplayName("制表符 / 英文逗号 / 中文逗号 / 连续空格 / 全角空格 都能切分")
    void splitsByMixedSeparators() {
        String text = String.join("\n",
                "2023010101\t张三",
                "2023010102,李四",
                "2023010103，王五",
                "2023010104   赵六",
                "2023010105\u3000钱七");
        ParsedSheet sheet = StudentRosterParser.parseText(text, MAX_ROWS);
        assertThat(sheet.rows()).extracting(ParsedRow::studentNo)
                .containsExactly("2023010101", "2023010102", "2023010103", "2023010104", "2023010105");
        assertThat(sheet.rows()).extracting(ParsedRow::name)
                .containsExactly("张三", "李四", "王五", "赵六", "钱七");
    }

    @Test
    @DisplayName("短横线分隔：学号-姓名")
    void splitsByDash() {
        String[] fields = StudentRosterParser.splitFields("2023010104-赵六");
        assertThat(fields[0]).isEqualTo("2023010104");
        assertThat(fields[1]).isEqualTo("赵六");
    }

    @Test
    @DisplayName("学号本身带短横线时不被误切")
    void keepsDashedStudentNo() {
        String[] fields = StudentRosterParser.splitFields("2023-0101 赵六");
        assertThat(fields[0]).isEqualTo("2023-0101");
        assertThat(fields[1]).isEqualTo("赵六");
    }

    @Test
    @DisplayName("空行、首尾空白、BOM 与行号")
    void skipsBlankLinesAndKeepsRowNumbers() {
        String text = "\uFEFF\n\n  2023010104   赵六  \n\n2023010105\t钱七\n";
        ParsedSheet sheet = StudentRosterParser.parseText(text, MAX_ROWS);
        assertThat(sheet.rows()).hasSize(2);
        assertThat(sheet.rows().get(0).rowNo()).isEqualTo(3);
        assertThat(sheet.rows().get(0).name()).isEqualTo("赵六");
        assertThat(sheet.rows().get(1).rowNo()).isEqualTo(5);
    }

    @Test
    @DisplayName("第一行是表头时自动跳过并标记")
    void detectsHeaderRow() {
        ParsedSheet sheet = StudentRosterParser.parseText("学号 姓名\n2023010104 赵六", MAX_ROWS);
        assertThat(sheet.headerDetected()).isTrue();
        assertThat(sheet.rows()).hasSize(1);
    }

    @Test
    @DisplayName("只有学号没有姓名时留空，由校验层报错")
    void keepsMissingNameEmpty() {
        ParsedSheet sheet = StudentRosterParser.parseText("2023010104", MAX_ROWS);
        assertThat(sheet.rows().get(0).studentNo()).isEqualTo("2023010104");
        assertThat(sheet.rows().get(0).name()).isEmpty();
    }

    @Test
    @DisplayName("超过条数上限直接拒绝，不产生半成品数据")
    void rejectsTooManyRows() {
        String text = String.join("\n", List.of("2023010101 张三", "2023010102 李四", "2023010103 王五"));
        assertThatThrownBy(() -> StudentRosterParser.parseText(text, 2))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多导入");
    }

    @Test
    @DisplayName("xlsx：数值型学号保持原样，不变成科学计数法")
    void parsesXlsxWithoutScientificNotation() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学生名单");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("学号");
            header.createCell(1).setCellValue("姓名");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(2023010104d);
            data.createCell(1).setCellValue("赵六");
            workbook.write(out);
            xlsx = out.toByteArray();
        }

        ParsedSheet sheet = StudentRosterParser.parseWorkbook(new ByteArrayInputStream(xlsx), MAX_ROWS);
        assertThat(sheet.headerDetected()).isTrue();
        assertThat(sheet.rows()).hasSize(1);
        assertThat(sheet.rows().get(0).studentNo()).isEqualTo("2023010104");
        assertThat(sheet.rows().get(0).name()).isEqualTo("赵六");
    }

    @Test
    @DisplayName("docx：读取第一个两列表格")
    void parsesDocxTable() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("学号");
            table.getRow(0).getCell(1).setText("姓名");
            table.getRow(1).getCell(0).setText("2023010104");
            table.getRow(1).getCell(1).setText("赵六");
            document.write(out);
            docx = out.toByteArray();
        }

        ParsedSheet sheet = StudentRosterParser.parseDocx(new ByteArrayInputStream(docx), MAX_ROWS);
        assertThat(sheet.headerDetected()).isTrue();
        assertThat(sheet.rows()).hasSize(1);
        assertThat(sheet.rows().get(0).studentNo()).isEqualTo("2023010104");
        assertThat(sheet.rows().get(0).name()).isEqualTo("赵六");
    }

    @Test
    @DisplayName("docx：没有表格时按段落逐行解析")
    void parsesDocxParagraphs() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("2023010104 赵六");
            document.createParagraph().createRun().setText("2023010105 钱七");
            document.write(out);
            docx = out.toByteArray();
        }

        ParsedSheet sheet = StudentRosterParser.parseDocx(new ByteArrayInputStream(docx), MAX_ROWS);
        assertThat(sheet.rows()).hasSize(2);
        assertThat(sheet.rows().get(1).name()).isEqualTo("钱七");
    }

    @Test
    @DisplayName("csv：带 BOM 的 UTF-8 能解析，逗号分隔")
    void parsesCsv() {
        byte[] csv = ("\uFEFF学号,姓名\n2023010104,赵六\n2023010105,钱七\n").getBytes(StandardCharsets.UTF_8);
        ParsedSheet sheet = StudentRosterParser.parseCsv(csv, MAX_ROWS);
        assertThat(sheet.headerDetected()).isTrue();
        assertThat(sheet.rows()).extracting(ParsedRow::name).containsExactly("赵六", "钱七");
    }

    @Test
    @DisplayName("扩展名解析：大小写与无扩展名")
    void resolvesExtension() {
        assertThat(StudentRosterParser.extensionOf("名单.XLSX")).isEqualTo("xlsx");
        assertThat(StudentRosterParser.extensionOf("名单")).isEmpty();
        assertThat(StudentRosterParser.extensionOf(null)).isEmpty();
    }
}
