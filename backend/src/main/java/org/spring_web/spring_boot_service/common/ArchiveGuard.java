package org.spring_web.spring_boot_service.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 上传压缩包（.zip / .jar）安全闸门：一遍扫描同时完成「校验」与「源码统计」。
 *
 * <p>上传入口（{@code SubmissionService.validateArchive}）与解析入口
 * （{@code AiEvaluationService.inspectZip}）共用这一份实现，避免两处逻辑漂移：
 * 只修一边、另一边忘了修，是这类漏洞最常见的复发方式。</p>
 *
 * <p>校验规则：条目数上限、解压后总字节上限、拒绝绝对路径与 {@code ..} 路径段（Zip Slip）、
 * 拒绝可执行文件。统计口径与旧 {@code inspectZip} 保持一致：非目录文件计数、Java 文件数、
 * Java 文件换行数、构建方式（MAVEN / GRADLE / PLAIN），以及最多 30000 字符的 Java
 * 源码片段（格式 {@code // <entryName>\n<content>\n}）。</p>
 *
 * <p>本类只依赖 {@code java.*}，可以脱离 Spring 与第三方依赖单独编译、单独测试。
 * 注意：单个条目的压缩算法不受支持（如 bzip2）时 {@link ZipInputStream} 会抛
 * {@link IOException}，按「压缩包无法解析」处理。</p>
 */
public final class ArchiveGuard {

    /** 读缓冲 8KB：吞吐够用，且单次读取的内存占用可预期。 */
    private static final int BUFFER_SIZE = 8 * 1024;
    /** 交给 AI 的源码片段总长度上限（与旧实现一致）。 */
    private static final int MAX_SNIPPET_CHARS = 30_000;
    /** 明确禁止的可执行文件后缀（统一小写比较）。 */
    private static final String[] UNSAFE_EXTENSIONS = {
            ".exe", ".dll", ".so", ".bat", ".cmd", ".sh"
    };

    private ArchiveGuard() {
    }

    /**
     * 校验失败。{@code code} 供调用方映射成前端错误明细，{@code message} 是可直接展示的中文原因。
     */
    public static final class Violation extends Exception {

        private static final long serialVersionUID = 1L;

        /** 失败码：TOO_MANY_ENTRIES / ZIP_SLIP / UNSAFE_FILE / UNPACKED_TOO_LARGE。 */
        public final String code;
        /** 中文失败原因（同时作为 {@link #getMessage()} 的返回值）。 */
        public final String message;

        public Violation(String code, String message) {
            super(message);
            this.code = code;
            this.message = message;
        }

        public String code() {
            return code;
        }

        public String message() {
            return message;
        }
    }

    /** 扫描上限：默认 3000 个条目 / 解压后 500MB。 */
    public record Limits(int maxEntries, long maxUnpackedBytes) {

        public Limits {
            if (maxEntries <= 0) {
                throw new IllegalArgumentException("maxEntries 必须大于 0");
            }
            if (maxUnpackedBytes <= 0) {
                throw new IllegalArgumentException("maxUnpackedBytes 必须大于 0");
            }
        }

        public static Limits defaults() {
            return new Limits(3000, 500L * 1024 * 1024);
        }
    }

    /** 扫描结果；字段顺序刻意与旧 {@code ZipStats} 的构造参数保持一致，便于一对一映射。 */
    public record Scan(int totalFiles, int javaFiles, int codeLines, String buildTool, String snippet) {
    }

    /** 扫描磁盘上的 .zip / .jar；本方法负责打开并关闭文件流。 */
    public static Scan scan(Path file, Limits limits) throws IOException, Violation {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(limits, "limits");
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            return scanInternal(zip, limits);
        }
    }

    /**
     * 扫描调用方提供的流（如 {@code MultipartFile.getInputStream()}）。
     *
     * <p><b>不关闭传入流</b>：流的生命周期归调用方管理，避免出现「谁关闭」的隐式约定。</p>
     */
    public static Scan scan(InputStream in, Limits limits) throws IOException, Violation {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(limits, "limits");
        return scanInternal(new ZipInputStream(in), limits);
    }

    // ---------------------------------------------------------------- 内部实现

    /** 唯一的扫描实现：单次顺序读取，条目数 / 解压字节 / 路径 / 后缀在读取过程中即时校验。 */
    private static Scan scanInternal(ZipInputStream zip, Limits limits) throws IOException, Violation {
        int entries = 0;
        int totalFiles = 0;
        int javaFiles = 0;
        long codeLines = 0L;
        long totalBytes = 0L;
        String buildTool = "PLAIN";
        StringBuilder snippet = new StringBuilder();
        byte[] buffer = new byte[BUFFER_SIZE];

        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            entries++;
            if (entries > limits.maxEntries()) {
                throw new Violation("TOO_MANY_ENTRIES",
                        "压缩包文件数超过 " + limits.maxEntries());
            }
            String name = entry.getName();
            checkEntryName(name);
            String lower = name.toLowerCase(Locale.ROOT);
            checkExtension(lower, name);
            if (entry.isDirectory()) {
                continue;
            }

            totalFiles++;
            if (lower.endsWith("pom.xml")) {
                buildTool = "MAVEN";
            } else if (lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts")) {
                buildTool = "GRADLE";
            }
            boolean javaSource = lower.endsWith(".java");
            if (javaSource) {
                javaFiles++;
            }
            // 只缓存 Java 文件片段，且单文件缓存量不超过片段上限；其它文件读完即丢。
            ByteArrayOutputStream content = javaSource ? new ByteArrayOutputStream() : null;
            long entryBytes = 0L;
            while (true) {
                int read = zip.read(buffer);
                if (read == -1) {
                    break;
                }
                entryBytes += read;
                totalBytes += read;
                if (javaSource) {
                    for (int i = 0; i < read; i++) {
                        if (buffer[i] == '\n') {
                            codeLines++;
                        }
                    }
                    if (content.size() < MAX_SNIPPET_CHARS) {
                        content.write(buffer, 0, Math.min(read, MAX_SNIPPET_CHARS - content.size()));
                    }
                }
                // 累计超过上限立即中断，绝不把整个压缩炸弹读完；单条目上限同样兜底。
                if (totalBytes > limits.maxUnpackedBytes() || entryBytes > limits.maxUnpackedBytes()) {
                    throw new Violation("UNPACKED_TOO_LARGE",
                            "解压后超过 " + formatBytes(limits.maxUnpackedBytes()));
                }
            }
            if (javaSource) {
                appendSnippet(snippet, name, content.toByteArray());
            }
        }
        return new Scan(totalFiles, javaFiles, (int) Math.min(codeLines, Integer.MAX_VALUE),
                buildTool, snippet.toString());
    }

    /** Zip Slip 防护：拒绝绝对路径（含 Windows 盘符）与任何 {@code ..} 路径段。 */
    private static void checkEntryName(String name) throws Violation {
        String unified = name.replace('\\', '/');
        if (unified.startsWith("/") || hasWindowsDrive(unified) || isAbsolute(name)) {
            throw new Violation("ZIP_SLIP", "压缩包包含非法路径：" + name);
        }
        for (String segment : unified.split("/")) {
            if ("..".equals(segment)) {
                throw new Violation("ZIP_SLIP", "压缩包包含非法路径：" + name);
            }
        }
    }

    /** 连路径都无法表示的条目名（如含 NUL）同样按非法路径处理。 */
    private static boolean isAbsolute(String name) {
        try {
            return Paths.get(name).isAbsolute();
        } catch (InvalidPathException ex) {
            return true;
        }
    }

    /** Windows 盘符（C:/、C:\）在 Linux 上 Paths 不认为是绝对路径，这里显式识别。 */
    private static boolean hasWindowsDrive(String unified) {
        return unified.length() >= 2 && Character.isLetter(unified.charAt(0))
                && unified.charAt(1) == ':';
    }

    /** 拒绝可执行文件；目录条目以分隔符结尾，不会命中后缀表。 */
    private static void checkExtension(String lowerName, String name) throws Violation {
        for (String extension : UNSAFE_EXTENSIONS) {
            if (lowerName.endsWith(extension)) {
                throw new Violation("UNSAFE_FILE", "压缩包包含可执行文件：" + name);
            }
        }
    }

    /** 按旧格式 {@code // <entryName>\n<content>\n} 追加片段，整体严格不超过 30000 字符。 */
    private static void appendSnippet(StringBuilder snippet, String entryName, byte[] content) {
        int remaining = MAX_SNIPPET_CHARS - snippet.length();
        if (remaining <= 0) {
            return;
        }
        String piece = "// " + entryName + '\n' + new String(content, StandardCharsets.UTF_8) + '\n';
        snippet.append(piece, 0, Math.min(piece.length(), remaining));
    }

    /** 把字节上限格式化成可读中文（默认值恰好是「500 MB」）。 */
    private static String formatBytes(long bytes) {
        long mb = bytes / (1024L * 1024L);
        if (mb > 0 && bytes % (1024L * 1024L) == 0) {
            return mb + " MB";
        }
        long kb = bytes / 1024L;
        if (kb > 0 && bytes % 1024L == 0) {
            return kb + " KB";
        }
        return bytes + " 字节";
    }
}
