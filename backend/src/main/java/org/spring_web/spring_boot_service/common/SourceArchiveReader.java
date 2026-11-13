package org.spring_web.spring_boot_service.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 把源码压缩包（.zip / .jar）流式读成「文件路径 → 纯文本」列表，供答辩页只读查看。
 *
 * <p>与 {@link ArchiveGuard} 的分工：那个类回答「这个包能不能收」（上传闸门，只扫不读全文），
 * 这个类回答「这个包里的代码长什么样」（查看接口，需要内容）。两者共用同一套
 * Zip Slip / 可执行文件判定动作，避免「上传拦了、查看没拦」的漏口。</p>
 *
 * <p>五条硬约束，全部在读取过程中即时生效，不做「先解压后检查」：</p>
 * <ol>
 *   <li>绝不落盘：所有内容只在内存里过一遍，不产生临时文件，也就没有「临时文件被谁捡走」的问题；</li>
 *   <li>绝不返回压缩包原始字节：本类只产出文本条目，二进制（图片 / class / jar / exe）直接跳过；</li>
 *   <li>条目数、单文件字节、总字节、总耗时四个上限任何一个触顶就中断 —— 压缩炸弹读不完；</li>
 *   <li>Zip Slip：绝对路径、Windows 盘符、任何 {@code ..} 路径段一律拒绝整个包；</li>
 *   <li>超单文件上限时截断并置 {@code truncated=true}，绝不截断后假装完整。</li>
 * </ol>
 *
 * <p>只依赖 {@code java.*}，可脱离 Spring 单独编译与单测。</p>
 */
public final class SourceArchiveReader {

    /** 单次读取缓冲；8KB 与 {@link ArchiveGuard} 保持一致，压缩包吞吐在此量级已足够。 */
    private static final int BUFFER_SIZE = 8 * 1024;
    /** 判定二进制时只看头部这么多字节：文本文件里出现 NUL 基本可判定为二进制。 */
    private static final int BINARY_PROBE_BYTES = 8 * 1024;
    /** 条目总数硬上限（相对于 maxFiles 的倍数）：防止上万个空目录条目把循环拖长。 */
    private static final int ENTRY_MULTIPLIER = 20;

    /** 明确禁止的可执行文件后缀，与 {@link ArchiveGuard} 同一份口径。 */
    private static final String[] UNSAFE_EXTENSIONS = {
            ".exe", ".dll", ".so", ".bat", ".cmd", ".sh", ".com", ".scr", ".msi", ".dylib"
    };

    /** 纯噪声目录：前端依赖与版本库元数据，既不是学生写的代码，又会让文件数瞬间爆掉。 */
    private static final String[] NOISE_SEGMENTS = {
            "node_modules/", ".git/", ".svn/", ".idea/", ".gradle/", ".mvn/", "__pycache__/"
    };

    /** 允许展示的文本后缀 → highlight.js 的语言标识（前端拿到就能直接喂给高亮器）。 */
    private static final Map<String, String> TEXT_LANGUAGES = textLanguages();

    private SourceArchiveReader() {
    }

    /**
     * 读取上限。
     *
     * @param maxFiles     最多返回多少个文本文件
     * @param maxFileBytes 单文件最多返回多少字节（超出截断）
     * @param maxTotalBytes 解压后总字节上限
     * @param timeoutMillis 整体超时（毫秒）
     */
    public record Limits(int maxFiles, long maxFileBytes, long maxTotalBytes, long timeoutMillis) {

        public Limits {
            if (maxFiles <= 0) {
                throw new IllegalArgumentException("maxFiles 必须大于 0");
            }
            if (maxFileBytes <= 0) {
                throw new IllegalArgumentException("maxFileBytes 必须大于 0");
            }
            if (maxTotalBytes <= 0) {
                throw new IllegalArgumentException("maxTotalBytes 必须大于 0");
            }
            if (timeoutMillis <= 0) {
                throw new IllegalArgumentException("timeoutMillis 必须大于 0");
            }
        }

        public static Limits defaults() {
            return new Limits(500, 5L * 1024 * 1024, 200L * 1024 * 1024, 60_000L);
        }
    }

    /**
     * 单个文本文件。
     *
     * @param path      包内路径（多包合并时带 {@code 包名/} 前缀）
     * @param content   纯文本内容
     * @param size      该条目真实字节数（截断前）
     * @param truncated 是否因为超过单文件上限被截断
     * @param language  语言标识（java / xml / javascript …），未知为 text
     */
    public record Entry(String path, String content, long size, boolean truncated, String language) {
    }

    /**
     * 读取结果。
     *
     * @param files        按路径字典序排好的文件列表
     * @param skippedFiles 跳过的非文本/噪声文件数（给前端一句「已忽略 N 个非文本文件」）
     * @param totalEntries 包内条目总数（含目录）
     * @param totalSize    返回内容的总字节数
     * @param truncated    是否有任意文件被截断（前端据此显示整体提示）
     */
    public record Result(List<Entry> files, int skippedFiles, int totalEntries, long totalSize,
                         boolean truncated) {
    }

    /** 读磁盘上的压缩包；本方法负责打开并关闭文件流。 */
    public static Result read(Path file, String prefix, Limits limits)
            throws IOException, ArchiveGuard.Violation {
        Objects.requireNonNull(file, "file");
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, prefix, limits);
        }
    }

    /**
     * 读调用方提供的流。
     *
     * <p><b>不关闭传入流</b>：生命周期归调用方；{@link ZipInputStream} 自身也只是包装。</p>
     */
    public static Result read(InputStream in, String prefix, Limits limits)
            throws IOException, ArchiveGuard.Violation {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(limits, "limits");
        return readInternal(new ZipInputStream(in), normalizePrefix(prefix), limits);
    }

    // ---------------------------------------------------------------- 内部实现

    private static Result readInternal(ZipInputStream zip, String prefix, Limits limits)
            throws IOException, ArchiveGuard.Violation {
        long deadline = System.nanoTime() + limits.timeoutMillis() * 1_000_000L;
        int entryCap = Math.min(Integer.MAX_VALUE / 2, limits.maxFiles() * ENTRY_MULTIPLIER);

        List<Entry> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int entries = 0;
        int skipped = 0;
        long totalBytes = 0L;
        boolean anyTruncated = false;
        byte[] buffer = new byte[BUFFER_SIZE];

        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > entryCap) {
                    throw new ArchiveGuard.Violation("TOO_MANY_ENTRIES",
                            "压缩包条目数超过 " + entryCap);
                }
                checkDeadline(deadline);

                String name = normalizeName(entry.getName());
                checkEntryName(name);
                if (entry.isDirectory()) {
                    continue;
                }
                if (isNoise(name)) {
                    skipped++;
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                checkExecutable(lower, name);

                String extension = extensionOf(lower);
                if (!TEXT_LANGUAGES.containsKey(extension)) {
                    skipped++;
                    continue;
                }
                if (files.size() >= limits.maxFiles()) {
                    throw new ArchiveGuard.Violation("TOO_MANY_FILES",
                            "源码文件数超过 " + limits.maxFiles() + " 个");
                }

                // 逐条目读取：超过单文件上限就停止读这一条（getNextEntry 会自动跳过剩余数据），
                // 不把整条炸弹读完，也不为了「算大小」白白消耗 CPU。
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                long entryBytes = 0L;
                boolean truncated = false;
                while (true) {
                    int read = zip.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    checkDeadline(deadline);
                    entryBytes += read;
                    totalBytes += read;
                    if (totalBytes > limits.maxTotalBytes()) {
                        throw new ArchiveGuard.Violation("UNPACKED_TOO_LARGE",
                                "解压后超过 " + formatBytes(limits.maxTotalBytes()));
                    }
                    int room = (int) Math.min(read, limits.maxFileBytes() - content.size());
                    if (room > 0) {
                        content.write(buffer, 0, room);
                    }
                    if (entryBytes > limits.maxFileBytes()) {
                        truncated = true;
                        anyTruncated = true;
                        break;
                    }
                }

                byte[] bytes = content.toByteArray();
                if (isBinary(bytes)) {
                    // 后缀像文本、内容其实是二进制的（伪造扩展名）一并跳过
                    skipped++;
                    continue;
                }
                String path = prefix.isEmpty() ? name : prefix + "/" + name;
                if (!seen.add(path)) {
                    // 同名条目只保留第一条：重复条目是典型的「解压覆盖」攻击面
                    skipped++;
                    continue;
                }
                long size = entry.getSize() >= 0 ? entry.getSize() : entryBytes;
                files.add(new Entry(path, decode(bytes), size, truncated,
                        TEXT_LANGUAGES.get(extension)));
            }
        } finally {
            // 不关闭底层流，但要释放 inflater 占用的本地内存
            try {
                zip.closeEntry();
            } catch (IOException ignored) {
                // 关闭当前条目失败不影响已读出的结果
            }
        }

        files.sort(Comparator.comparing(Entry::path));
        return new Result(List.copyOf(files), skipped, entries, totalBytes, anyTruncated);
    }

    /** 超时检查：用 nanoTime 做单调时钟，避免系统时间被调整后「超时永远不触发」。 */
    private static void checkDeadline(long deadline) throws ArchiveGuard.Violation {
        if (System.nanoTime() > deadline) {
            throw new ArchiveGuard.Violation("TIMEOUT", "源码解压超时");
        }
    }

    /** Zip Slip 防护：拒绝绝对路径（含 Windows 盘符）与任何 {@code ..} 路径段。 */
    private static void checkEntryName(String name) throws ArchiveGuard.Violation {
        if (name.startsWith("/") || hasWindowsDrive(name) || isAbsolute(name)) {
            throw new ArchiveGuard.Violation("ZIP_SLIP", "压缩包包含非法路径：" + name);
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw new ArchiveGuard.Violation("ZIP_SLIP", "压缩包包含非法路径：" + name);
            }
        }
    }

    private static boolean isAbsolute(String name) {
        try {
            return Paths.get(name).isAbsolute();
        } catch (InvalidPathException ex) {
            return true;
        }
    }

    private static boolean hasWindowsDrive(String unified) {
        return unified.length() >= 2 && Character.isLetter(unified.charAt(0)) && unified.charAt(1) == ':';
    }

    /** 可执行文件拒收 —— 与上传闸门同口径，查看阶段再拦一次（纵深防御）。 */
    private static void checkExecutable(String lowerName, String name) throws ArchiveGuard.Violation {
        for (String extension : UNSAFE_EXTENSIONS) {
            if (lowerName.endsWith(extension)) {
                throw new ArchiveGuard.Violation("UNSAFE_FILE", "压缩包包含可执行文件：" + name);
            }
        }
    }

    private static boolean isNoise(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String segment : NOISE_SEGMENTS) {
            if (lower.startsWith(segment) || lower.contains("/" + segment)) {
                return true;
            }
        }
        return false;
    }

    /** 统一分隔符并去掉 {@code ./} 前缀，让 Windows 打包的 zip 也能得到同一套路径。 */
    private static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        return name;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String value = normalizeName(prefix).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String extensionOf(String lowerName) {
        int slash = lowerName.lastIndexOf('/');
        int dot = lowerName.lastIndexOf('.');
        return dot > slash ? lowerName.substring(dot) : "";
    }

    /** 头部含 NUL 即判定二进制；顺带拦掉 UTF-16 BOM 之外的乱码文本。 */
    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** 去 UTF-8 BOM 后解码；坏字节用替换符而非抛异常，避免一个乱码文件废掉整包。 */
    private static String decode(byte[] bytes) {
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
    }

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

    /**
     * 文本后缀 → 语言标识。
     *
     * <p>顺序即 {@link LinkedHashMap} 的插入顺序，单测按这里断言「白名单里的都能展示」。
     * 语言标识直接用 highlight.js 的名字（javascript / xml / java …），前端无需再做一层映射。</p>
     */
    private static Map<String, String> textLanguages() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(".java", "java");
        map.put(".kt", "kotlin");
        map.put(".xml", "xml");
        map.put(".html", "xml");
        map.put(".vue", "xml");
        map.put(".js", "javascript");
        map.put(".mjs", "javascript");
        map.put(".cjs", "javascript");
        map.put(".jsx", "javascript");
        map.put(".ts", "typescript");
        map.put(".tsx", "typescript");
        map.put(".py", "python");
        map.put(".c", "c");
        map.put(".h", "c");
        map.put(".cpp", "cpp");
        map.put(".cc", "cpp");
        map.put(".hpp", "cpp");
        map.put(".go", "go");
        map.put(".rs", "rust");
        map.put(".php", "php");
        map.put(".rb", "ruby");
        map.put(".cs", "csharp");
        map.put(".sql", "sql");
        map.put(".css", "css");
        map.put(".scss", "scss");
        map.put(".less", "less");
        map.put(".json", "json");
        map.put(".yaml", "yaml");
        map.put(".yml", "yaml");
        map.put(".properties", "properties");
        map.put(".ini", "ini");
        map.put(".toml", "ini");
        map.put(".gradle", "groovy");
        map.put(".md", "markdown");
        map.put(".txt", "text");
        map.put(".gitignore", "text");
        map.put(".env", "text");
        return java.util.Collections.unmodifiableMap(map);
    }
}
