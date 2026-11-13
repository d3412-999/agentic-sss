package org.spring_web.spring_boot_service.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 题目附件三重校验：扩展名 → 文件头魔数 → MIME。
 *
 * <p>只信扩展名会被「把 exe 改名成 pdf」绕过，所以三者都要过：</p>
 * <ol>
 *   <li><b>扩展名</b>：白名单来自 {@code sss.upload.topic-attachment.allowed-extensions}，可配置；</li>
 *   <li><b>魔数</b>：PDF 读 {@code %PDF}，DOC 读 OLE2 复合文档头，DOCX 还要能解出 {@code word/} 目录，
 *       仅把 zip 改名为 docx 会被拒；</li>
 *   <li><b>MIME</b>：允许浏览器/命令行常见的 {@code application/octet-stream}（它们经常不给准确值），
 *       其余必须落在白名单内。</li>
 * </ol>
 *
 * <p>所有失败都抛 {@link ErrorCode#FILE_INVALID}，HTTP 400，message 直接可读，不产生 500。</p>
 */
@Service
@RequiredArgsConstructor
public class FileTypeValidator {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};
    private static final byte[] OLE2_MAGIC = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};

    /** docx 的 zip 头，可能是本地文件头或空档头。 */
    private static final int ZIP_LOCAL_HEADER = 0x04034b50;
    private static final int ZIP_EMPTY_HEADER = 0x06054b50;

    private static final String CT_PDF = "application/pdf";
    private static final String CT_DOC = "application/msword";
    private static final String CT_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final SssProperties properties;

    /** 校验通过后的规范化结果：文件名已清洗，contentType 已归一，扩展名小写。 */
    public record Validated(String filename, String extension, String contentType) {
    }

    public SssProperties.TopicAttachment policy() {
        return properties.getUpload().getTopicAttachment();
    }

    /** 供错误提示复用的一句话白名单描述，例如「PDF / DOC / DOCX」。 */
    public String allowedText() {
        return policy().getAllowedExtensions().stream()
                .map(String::toUpperCase)
                .reduce((a, b) -> a + " / " + b)
                .orElse("PDF");
    }

    public String maxFileText() {
        return humanSize(policy().getMaxFileBytes());
    }

    public String maxTotalText() {
        return humanSize(policy().getMaxTotalBytes());
    }

    public Validated validate(MultipartFile file) {
        SssProperties.TopicAttachment policy = policy();
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BizException(ErrorCode.FILE_INVALID, "不能上传空文件");
        }
        String filename = cleanName(file.getOriginalFilename());
        String extension = extensionOf(filename);
        if (!policy.getAllowedExtensions().contains(extension)) {
            throw new BizException(ErrorCode.FILE_INVALID,
                    "「" + filename + "」格式不支持，仅允许 " + allowedText());
        }
        if (file.getSize() > policy.getMaxFileBytes()) {
            throw new BizException(ErrorCode.FILE_INVALID,
                    "「" + filename + "」大小 " + humanSize(file.getSize())
                            + " 超过单文件上限 " + maxFileText());
        }
        if (!magicMatches(file, extension)) {
            throw new BizException(ErrorCode.FILE_INVALID,
                    "「" + filename + "」内容与扩展名不符，已拒绝（请勿修改文件后缀）");
        }
        String declared = file.getContentType();
        if (StringUtils.hasText(declared)) {
            String normalized = declared.toLowerCase(Locale.ROOT).split(";")[0].trim();
            boolean permitted = policy.getAllowedContentTypes().contains(normalized)
                    || "application/octet-stream".equals(normalized);
            if (!permitted) {
                throw new BizException(ErrorCode.FILE_INVALID,
                        "「" + filename + "」的 MIME 类型不被允许：" + normalized);
            }
        }
        return new Validated(filename, extension, canonicalContentType(extension));
    }

    /**
     * 去路径 + 去控制字符 + 去 Windows 非法字符。
     *
     * <p>注意这里只做「展示与落库安全」的清洗，真正的落盘路径由服务端 UUID 生成，
     * 用户文件名永远不参与路径拼接——两道防线叠加才彻底排除路径穿越。</p>
     */
    public String cleanName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "attachment";
        }
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("\\p{Cntrl}", "")
                .replaceAll("[<>:\"|?*]", "_")
                .replaceAll("^[.\\s]+", "")
                .trim();
        if (!StringUtils.hasText(name)) {
            return "attachment";
        }
        if (name.length() > 120) {
            String ext = extensionOf(name);
            String suffix = StringUtils.hasText(ext) ? "." + ext : "";
            int keep = Math.max(1, 120 - suffix.length());
            name = name.substring(0, Math.min(name.length(), keep)) + suffix;
        }
        return name;
    }

    public String extensionOf(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String canonicalContentType(String extension) {
        return switch (extension) {
            case "pdf" -> CT_PDF;
            case "doc" -> CT_DOC;
            case "docx" -> CT_DOCX;
            default -> "application/octet-stream";
        };
    }

    private boolean magicMatches(MultipartFile file, String extension) {
        try {
            byte[] head = readHead(file, 8);
            return switch (extension) {
                case "pdf" -> startsWith(head, PDF_MAGIC);
                case "doc" -> startsWith(head, OLE2_MAGIC);
                case "docx" -> isDocxArchive(head, file);
                default -> false;
            };
        } catch (IOException ex) {
            throw new BizException(ErrorCode.FILE_INVALID, "读取上传文件失败，请重试");
        }
    }

    private boolean isDocxArchive(byte[] head, MultipartFile file) throws IOException {
        if (!startsWith(head, new byte[]{'P', 'K'})) {
            return false;
        }
        int signature = (head[0] & 0xFF) | (head[1] & 0xFF) << 8 | (head[2] & 0xFF) << 16 | (head[3] & 0xFF) << 24;
        if (signature != ZIP_LOCAL_HEADER && signature != ZIP_EMPTY_HEADER) {
            return false;
        }
        // 只读条目名、不解压内容：既是格式判据，也不会被 zip 炸弹拖垮
        try (InputStream in = file.getInputStream(); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            int scanned = 0;
            while ((entry = zip.getNextEntry()) != null && scanned++ < 500) {
                String name = entry.getName();
                if (name != null && (name.startsWith("word/") || name.equals("[Content_Types].xml"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private byte[] readHead(MultipartFile file, int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        try (InputStream in = file.getInputStream()) {
            while (read < length) {
                int n = in.read(buffer, read, length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        if (read == 0) {
            return new byte[0];
        }
        byte[] head = new byte[read];
        System.arraycopy(buffer, 0, head, 0, read);
        return head;
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    public static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024) {
            double mb = bytes / (1024.0 * 1024.0);
            return (mb == Math.rint(mb) ? String.valueOf((long) mb) : String.format(Locale.ROOT, "%.1f", mb)) + "MB";
        }
        if (bytes >= 1024) {
            return (bytes / 1024) + "KB";
        }
        return bytes + "B";
    }
}
