package org.spring_web.spring_boot_service.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

/**
 * 学生名单解析器：把「文本 / Excel / Word / CSV」统一解析成 {@link ParsedRow}。
 *
 * <p>刻意做成无状态静态工具：解析规则是纯函数，可以脱离 Spring 上下文单测，
 * 也保证单条添加、批量粘贴、文件导入三条入口用的是同一套分隔符与表头规则。</p>
 *
 * <p>分隔符兼容顺序（先命中先生效，避免「姓名里带空格」被误切）：</p>
 * <ol>
 *   <li>制表符 —— Excel / Word 复制粘贴的默认分隔符；</li>
 *   <li>英文逗号、中文逗号 —— CSV 与手写表格；</li>
 *   <li>空白（含全角空格、连续空格）—— 「学号 姓名」；</li>
 *   <li>短横线 —— 「学号-姓名」，仅当左侧全部是数字/字母且右侧非空时生效。</li>
 * </ol>
 */
public final class StudentRosterParser {

    /** 原始行号从 1 开始计，与老师在 Excel / Word 里看到的行号一致。 */
    public record ParsedRow(int rowNo, String raw, String studentNo, String name, String email) {
    }

    /** 解析结果：{@code headerDetected} 表示首行表头已被识别并跳过，前端会给出提示。 */
    public record ParsedSheet(List<ParsedRow> rows, boolean headerDetected) {
    }

    /** 学号：字母、数字、下划线、短横线，2~32 位（兼容 2023010104 与 2023-0101 这类写法）。 */
    private static final Pattern STUDENT_NO = Pattern.compile("^[A-Za-z0-9_-]{2,32}$");

    private static final Pattern WHITESPACE =
            Pattern.compile("[\\s\\u00A0\\u3000]+");

    private static final Pattern COMMA = Pattern.compile("[,，]");

    private static final Set<String> ID_HEADERS = Set.of(
            "学号", "学生学号", "学号/工号", "学生编号", "编号", "studentno", "student no", "sno", "no");

    private static final Set<String> NAME_HEADERS =
            Set.of("姓名", "学生姓名", "名字", "name", "studentname", "student name");

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("xlsx", "xls", "docx", "csv");

    private StudentRosterParser() {
    }

    /* ------------------------------------------------------------------ */
    /* 入口                                                                */
    /* ------------------------------------------------------------------ */

    /** 按扩展名分派文件解析；{@code .doc} 给出「另存为」提示而不是 500。 */
    public static ParsedSheet parseFile(MultipartFile file, int maxRows) {
        BizAssert.param(file != null && !file.isEmpty(), "请选择要导入的文件");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extensionOf(filename);
        if ("doc".equals(ext)) {
            throw new BizException(ErrorCode.FILE_INVALID, "不支持旧版 .doc，请另存为 .docx 或 .xlsx 后重新上传");
        }
        BizAssert.check(SUPPORTED_EXTENSIONS.contains(ext), ErrorCode.FILE_INVALID,
                "不支持的文件格式" + (ext.isEmpty() ? "" : "（." + ext + "）")
                        + "，请上传 " + String.join(" / ", List.of(".xlsx", ".xls", ".docx", ".csv")));
        try {
            return switch (ext) {
                case "xlsx", "xls" -> parseWorkbook(file.getInputStream(), maxRows);
                case "docx" -> parseDocx(file.getInputStream(), maxRows);
                case "csv" -> parseCsv(file.getBytes(), maxRows);
                default -> throw new BizException(ErrorCode.FILE_INVALID, "不支持的文件格式");
            };
        } catch (IOException ex) {
            throw new BizException(ErrorCode.FILE_INVALID, "文件读取失败，请确认文件未损坏后重试");
        }
    }

    /** 文本粘贴：自动识别换行、制表符、全角空格，去掉首尾空白与空行。 */
    public static ParsedSheet parseText(String raw, int maxRows) {
        BizAssert.param(raw != null && !raw.isBlank(), "请粘贴学生数据");
        List<ParsedRow> rows = new ArrayList<>();
        boolean headerDetected = false;
        String[] lines = normalizeNewlines(stripBom(raw)).split("\n", -1);
        boolean first = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] fields = splitFields(line);
            if (first && isHeader(fields)) {
                headerDetected = true;
                first = false;
                continue;
            }
            first = false;
            rows.add(toRow(i + 1, line, fields));
            guardRowCount(rows.size(), maxRows);
        }
        return new ParsedSheet(rows, headerDetected);
    }

    /* ------------------------------------------------------------------ */
    /* Excel / Word / CSV                                                  */
    /* ------------------------------------------------------------------ */

    public static ParsedSheet parseWorkbook(InputStream in, int maxRows) {
        List<ParsedRow> rows = new ArrayList<>();
        boolean headerDetected = false;
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null) {
                return new ParsedSheet(rows, false);
            }
            boolean first = true;
            for (Row row : sheet) {
                String[] fields = new String[] {cellText(cell(row, 0)), cellText(cell(row, 1)),
                        cellText(cell(row, 2))};
                if (isBlankRow(fields)) {
                    continue;
                }
                if (first && isHeader(fields)) {
                    headerDetected = true;
                    first = false;
                    continue;
                }
                first = false;
                rows.add(new ParsedRow(row.getRowNum() + 1, String.join(" ", fields),
                        fields[0], fields[1], fields[2]));
                guardRowCount(rows.size(), maxRows);
            }
        } catch (BizException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new BizException(ErrorCode.FILE_INVALID, "表格解析失败，请确认文件未损坏、且为 .xlsx / .xls 格式");
        }
        return new ParsedSheet(rows, headerDetected);
    }

    /**
     * Word 导入：优先取第一个「两列以上」的表格（PTA / 教务系统导出的名单都是表格），
     * 没有表格时退回逐段落的「学号 姓名」文本解析。
     */
    public static ParsedSheet parseDocx(InputStream in, int maxRows) {
        try (XWPFDocument document = new XWPFDocument(in)) {
            for (XWPFTable table : document.getTables()) {
                if (!hasTwoColumns(table)) {
                    continue;
                }
                return parseWordTable(table, maxRows);
            }
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append('\n');
            }
            if (text.toString().isBlank()) {
                throw new BizException(ErrorCode.FILE_INVALID, "文档里没有可识别的学生数据");
            }
            return parseText(text.toString(), maxRows);
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException(ErrorCode.FILE_INVALID, "Word 解析失败，请确认文件为 .docx 格式");
        }
    }

    /** CSV：Excel 另存的 CSV 可能是 GBK，先按 UTF-8 严格解码，失败再回退 GBK。 */
    public static ParsedSheet parseCsv(byte[] bytes, int maxRows) {
        BizAssert.param(bytes != null && bytes.length > 0, "CSV 文件为空");
        return parseText(decodeCsv(bytes), maxRows);
    }

    private static ParsedSheet parseWordTable(XWPFTable table, int maxRows) {
        List<ParsedRow> rows = new ArrayList<>();
        boolean headerDetected = false;
        boolean first = true;
        List<XWPFTableRow> tableRows = table.getRows();
        for (int i = 0; i < tableRows.size(); i++) {
            List<XWPFTableCell> cells = tableRows.get(i).getTableCells();
            String[] fields = new String[] {cellText(cells, 0), cellText(cells, 1), cellText(cells, 2)};
            if (isBlankRow(fields)) {
                continue;
            }
            if (first && isHeader(fields)) {
                headerDetected = true;
                first = false;
                continue;
            }
            first = false;
            rows.add(new ParsedRow(i + 1, String.join(" ", fields), fields[0], fields[1], fields[2]));
            guardRowCount(rows.size(), maxRows);
        }
        return new ParsedSheet(rows, headerDetected);
    }

    private static boolean hasTwoColumns(XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            if (row.getTableCells().size() >= 2) {
                return true;
            }
        }
        return false;
    }

    private static String cellText(List<XWPFTableCell> cells, int index) {
        if (cells == null || index >= cells.size()) {
            return "";
        }
        XWPFTableCell cell = cells.get(index);
        return cell == null || cell.getText() == null ? "" : cell.getText().strip();
    }

    /* ------------------------------------------------------------------ */
    /* 文本切分与单元格读取                                                */
    /* ------------------------------------------------------------------ */

    /** 把一行切成 [学号, 姓名, 邮箱?]，切不出来的部分留空由校验层报错。 */
    public static String[] splitFields(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isEmpty()) {
            return new String[] {"", "", ""};
        }
        if (text.indexOf('\t') >= 0) {
            return pad(text.split("\t", 3));
        }
        if (COMMA.matcher(text).find()) {
            return pad(COMMA.split(text, 3));
        }
        String[] parts = WHITESPACE.split(text);
        if (parts.length >= 2) {
            return pad(parts);
        }
        int dash = text.indexOf('-');
        if (dash > 0 && dash < text.length() - 1 && STUDENT_NO.matcher(text.substring(0, dash)).matches()) {
            String left = text.substring(0, dash).strip();
            String right = text.substring(dash + 1).strip();
            if (!right.isEmpty() && !right.matches("[0-9]+")) {
                return new String[] {left, right, ""};
            }
        }
        return pad(parts);
    }

    /** 学号格式校验与单条接口共用同一套规则。 */
    public static boolean isValidStudentNo(String studentNo) {
        return studentNo != null && STUDENT_NO.matcher(studentNo).matches();
    }

    public static boolean isHeader(String[] fields) {
        if (fields == null || fields.length == 0) {
            return false;
        }
        String first = normalizeKey(fields[0]);
        String second = fields.length > 1 ? normalizeKey(fields[1]) : "";
        if (ID_HEADERS.contains(first) && (NAME_HEADERS.contains(second) || second.isEmpty())) {
            return true;
        }
        return NAME_HEADERS.contains(first) && (ID_HEADERS.contains(second) || second.isEmpty());
    }

    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    public static List<String> supportedExtensions() {
        return List.of("xlsx", "xls", "docx", "csv");
    }

    private static String[] pad(String[] values) {
        String[] result = new String[] {"", "", ""};
        for (int i = 0; i < Math.min(3, values.length); i++) {
            result[i] = values[i] == null ? "" : values[i].strip();
        }
        return result;
    }

    private static ParsedRow toRow(int rowNo, String raw, String[] fields) {
        return new ParsedRow(rowNo, raw == null ? "" : raw.strip(), fields[0], fields[1], fields[2]);
    }

    private static boolean isBlankRow(String[] fields) {
        for (String field : fields) {
            if (field != null && !field.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : WHITESPACE.matcher(value.strip()).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static Cell cell(Row row, int index) {
        if (row == null) {
            return null;
        }
        return row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    }

    /**
     * 单元格取文本。
     *
     * <p>数值单元格必须走 {@link BigDecimal#toPlainString()}：直接用 {@code String.valueOf(double)}
     * 会把 2023010104 变成 2.023010104E9，学号就废了。</p>
     */
    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        try {
            return switch (type) {
                case STRING -> cell.getStringCellValue() == null ? "" : cell.getStringCellValue().strip();
                case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                        ? String.valueOf(cell.getLocalDateTimeCellValue().toLocalDate())
                        : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String decodeCsv(byte[] bytes) {
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException ex) {
            return new String(bytes, offset, bytes.length - offset, Charset.forName("GBK"));
        }
    }

    private static void guardRowCount(int size, int maxRows) {
        if (maxRows > 0 && size > maxRows) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "单次最多导入 " + maxRows + " 条学生记录，请拆分文件后分批导入");
        }
    }
}
