package org.spring_web.spring_boot_service.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

/**
 * 课程设计报告正文抽取。
 *
 * <p>PDF 使用 PDFBox，DOCX 使用 Apache POI。抽取失败不抛异常中断上传流程，
 * 而是返回 success=false + 空正文，由调用方决定降级策略（当前是继续调用 AI，
 * 但把失败原因写进解析结果，避免静默生成一个看似正常的分数）。</p>
 */
@Slf4j
@Service
public class ReportTextExtractor {

    /**
     * 单份报告最多保留的字符数，避免把超长正文全部塞进提示词。
     */
    private static final int MAX_TEXT_CHARS = 60_000;

    /**
     * 抽取结果。
     *
     * @param text      归一化后的正文，失败时为空字符串
     * @param pageCount PDF 页数；DOCX 或失败时为 null
     * @param success   是否成功抽取
     * @param error     失败原因；成功时为 null
     */
    public record ExtractedReport(String text, Integer pageCount, boolean success, String error) {

        /**
         * 空结果。
         */
        public static ExtractedReport empty(String error) {
            return new ExtractedReport("", null, false, error);
        }
    }

    /**
     * 按文件扩展名选择解析器。
     */
    public ExtractedReport extract(Path path, String filename) {
        if (path == null || !Files.isRegularFile(path)) {
            return ExtractedReport.empty("文件不存在");
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".pdf")) {
                return extractPdf(path);
            }
            if (name.endsWith(".docx")) {
                return extractDocx(path);
            }
            return ExtractedReport.empty("不支持的报告格式，仅支持 PDF / DOCX");
        } catch (Exception ex) {
            log.warn("报告正文抽取失败，file={}", filename, ex);
            return ExtractedReport.empty("正文抽取失败：" + ex.getMessage());
        }
    }

    private ExtractedReport extractPdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = normalize(stripper.getText(document));
            int pages = document.getNumberOfPages();
            if (text.isBlank()) {
                return new ExtractedReport("", pages, false, "PDF 中没有可抽取的文本（可能是扫描件）");
            }
            return new ExtractedReport(text, pages, true, null);
        }
    }

    private ExtractedReport extractDocx(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(in)) {
            StringBuilder builder = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String line = paragraph.getText();
                if (line != null && !line.isBlank()) {
                    builder.append(line).append('\n');
                }
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String cellText = cell.getText();
                        if (cellText != null && !cellText.isBlank()) {
                            builder.append(cellText).append('\t');
                        }
                    }
                    builder.append('\n');
                }
            }
            String text = normalize(builder.toString());
            if (text.isBlank()) {
                return new ExtractedReport("", null, false, "DOCX 中没有可抽取的文本");
            }
            return new ExtractedReport(text, null, true, null);
        }
    }

    /**
     * 去掉控制字符、压缩多余空行，并限制最大长度。
     */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace('\u0000', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll("[ ]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS) + "\n……（正文过长已截断）";
        }
        return text;
    }
}
