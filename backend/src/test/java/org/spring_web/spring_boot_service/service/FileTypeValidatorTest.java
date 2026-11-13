package org.spring_web.spring_boot_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 附件三重校验的纯单元测试：扩展名、魔数、MIME 各自都能单独拦住伪装文件。
 *
 * <p>不启动 Spring 上下文——校验逻辑不依赖容器，跑得够快才能拦住回归。</p>
 */
class FileTypeValidatorTest {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OLE2 = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0, 0};

    private SssProperties properties;
    private FileTypeValidator validator;

    @BeforeEach
    void setUp() {
        properties = new SssProperties();
        validator = new FileTypeValidator(properties);
    }

    @Test
    void acceptsRealPdf() {
        var result = validator.validate(file("题目说明.pdf", PDF, "application/pdf"));
        assertEquals("pdf", result.extension());
        assertEquals("application/pdf", result.contentType());
    }

    @Test
    void acceptsRealDoc() {
        var result = validator.validate(file("旧版文档.doc", OLE2, "application/msword"));
        assertEquals("doc", result.extension());
        assertEquals("application/msword", result.contentType());
    }

    @Test
    void acceptsDocxThatContainsWordFolder() throws Exception {
        byte[] docx = zip("word/document.xml");
        var result = validator.validate(file("报告.docx", docx, null));
        assertEquals("docx", result.extension());
    }

    @Test
    void rejectsZipRenamedToDocx() throws Exception {
        byte[] fake = zip("readme.txt");
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate(file("假文档.docx", fake, null)));
        assertTrue(ex.getMessage().contains("内容与扩展名不符"));
    }

    @Test
    void rejectsTextRenamedToPdf() {
        byte[] text = "这个文件其实不是 PDF".getBytes(StandardCharsets.UTF_8);
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate(file("伪装.pdf", text, "application/pdf")));
        assertTrue(ex.getMessage().contains("内容与扩展名不符"));
    }

    @Test
    void rejectsExecutableAndUnknownExtension() {
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 0, 0};
        BizException byExt = assertThrows(BizException.class,
                () -> validator.validate(file("木马.exe", exe, "application/octet-stream")));
        assertTrue(byExt.getMessage().contains("格式不支持"));

        BizException byMime = assertThrows(BizException.class,
                () -> validator.validate(file("页面.pdf", PDF, "text/html")));
        assertTrue(byMime.getMessage().contains("MIME"));
    }

    @Test
    void rejectsOversizeFile() {
        properties.getUpload().getTopicAttachment().setMaxFileBytes(1024);
        byte[] big = new byte[2048];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate(file("超限.pdf", big, "application/pdf")));
        assertTrue(ex.getMessage().contains("超过单文件上限"));
    }

    @Test
    void rejectsEmptyFile() {
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate(new MockMultipartFile("files", "空.pdf", "application/pdf", new byte[0])));
        assertTrue(ex.getMessage().contains("空文件"));
    }

    @Test
    void cleanNameStripsPathAndIllegalCharacters() {
        assertEquals("passwd", validator.cleanName("../../etc/passwd"));
        assertEquals("a_b_.pdf", validator.cleanName("..\\..\\a<b>.pdf"));
        assertEquals("attachment", validator.cleanName(null));
        assertEquals("attachment", validator.cleanName("///"));
    }

    private MockMultipartFile file(String name, byte[] content, String contentType) {
        return new MockMultipartFile("files", name, contentType, content);
    }

    private byte[] zip(String entryName) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
