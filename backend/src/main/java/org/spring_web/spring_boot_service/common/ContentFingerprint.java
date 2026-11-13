package org.spring_web.spring_boot_service.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 源码内容指纹：归一化 + MinHash，用来抓「改注释、改缩进、改变量名」式抄袭。
 *
 * <p><b>为什么需要它：</b>原有两级检测是两个极端——
 * {@code sha256} 只能抓逐字节相同（改一个空格就失效），
 * {@code structureKey} 只能抓目录结构完全一致（重排文件就失效）。
 * 中间这档「代码主体一样、表面动过」正是最常见的抄袭形态。</p>
 *
 * <p><b>为什么用 MinHash 而不是存全部 shingle：</b>一份 5MB 源码有数百万个 5-gram，
 * 全存进数据库既撑爆行宽也没必要。MinHash 用固定 128 个整数近似 Jaccard 相似度，
 * 误差约 ±0.05，对「像不像」的判断完全够用，且比对是 128 次整数比较——
 * 同题 100 人 × 9 个包也就 10 万次比较，微秒级。</p>
 *
 * <p><b>它不做什么：</b>不做语义等价（把 for 改成 stream、换个算法实现）识别，
 * 也不判断谁抄谁。命中只是线索。</p>
 */
public final class ContentFingerprint {

    /** 归一化后参与计算的最大字符数：超长工程截断，保证单文件耗时可控。 */
    private static final int MAX_TEXT_CHARS = 2 * 1024 * 1024;

    /** shingle 长度（字符级 5-gram）。太短误报高，太长对少量改动不敏感。 */
    private static final int SHINGLE = 5;

    /** 签名维度。128 维的 Jaccard 估计标准差约 1/sqrt(128) ≈ 0.09。 */
    public static final int DIMS = 128;

    private ContentFingerprint() {
    }

    /**
     * 计算源码 zip 的内容指纹。
     *
     * @param zipPath 压缩包路径
     * @return 128 维签名；包内没有可读文本时返回 null（不写入库，也不参与比对）
     */
    public static int[] ofZip(Path zipPath) {
        String text = normalizedTextOfZip(zipPath);
        if (text.isEmpty()) {
            return null;
        }
        return minhash(text);
    }

    /** 读取 zip 里的文本类条目并归一化；读不动或为空返回空串。 */
    public static String normalizedTextOfZip(Path zipPath) {
        StringBuilder merged = new StringBuilder();
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isTextEntry(entry.getName())) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    merged.append(new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8));
                }
                if (merged.length() >= MAX_TEXT_CHARS) {
                    break;
                }
            }
        } catch (IOException | RuntimeException ex) {
            // 损坏的包在入库前已被 ArchiveGuard 拦过；这里出问题只说明指纹算不出来，
            // 退回 sha256/结构比对即可，不能因此让整次提交失败
            return "";
        }
        return normalize(merged);
    }

    /** 只对源码类文本取指纹；图片、jar 等二进制内容参与 shingle 会污染结果。 */
    private static boolean isTextEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".xml")
                || lower.endsWith(".sql") || lower.endsWith(".properties") || lower.endsWith(".yml")
                || lower.endsWith(".yaml") || lower.endsWith(".md");
    }

    /**
     * 归一化：去掉注释与所有空白、统一小写，保留字符串字面量。
     *
     * <p>注释和缩进是最容易被批量替换掉的部分，不先抹掉它们，
     * 抄袭者只要跑一遍格式化就能绕过检测。</p>
     */
    public static String normalize(CharSequence src) {
        if (src == null) {
            return "";
        }
        int n = Math.min(src.length(), MAX_TEXT_CHARS);
        StringBuilder out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                i += 2;
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\') {
                        out.append(d);
                        if (i + 1 < n) {
                            out.append(src.charAt(i + 1));
                        }
                        i += 2;
                        continue;
                    }
                    out.append(d);
                    i++;
                    if (d == quote) {
                        break;
                    }
                }
                continue;
            }
            if (!Character.isWhitespace(c)) {
                out.append(c);
            }
            i++;
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** 字符级 k-gram 的 MinHash 签名。 */
    public static int[] minhash(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Set<Long> shingles = new HashSet<>();
        int len = text.length();
        if (len <= SHINGLE) {
            shingles.add(fnv(text));
        } else {
            for (int i = 0; i + SHINGLE <= len; i++) {
                shingles.add(fnv(text.substring(i, i + SHINGLE)));
            }
        }
        if (shingles.isEmpty()) {
            return null;
        }
        int[] sig = new int[DIMS];
        Arrays.fill(sig, Integer.MAX_VALUE);
        for (long h : shingles) {
            for (int d = 0; d < DIMS; d++) {
                int v = (int) (mix64(h ^ (d * 0x9E3779B97F4A7C15L)) >>> 32);
                if (v < sig[d]) {
                    sig[d] = v;
                }
            }
        }
        return sig;
    }

    /** Jaccard 估计：签名同位相等的比例。任一为空返回 -1，调用方据此跳过。 */
    public static double similarity(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return -1;
        }
        int same = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) {
                same++;
            }
        }
        return same / (double) a.length;
    }

    /** 序列化成逗号分隔字符串，入库用。 */
    public static String encode(int[] sig) {
        if (sig == null || sig.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(sig.length * 11);
        for (int i = 0; i < sig.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Integer.toUnsignedString(sig[i]));
        }
        return sb.toString();
    }

    /** 解析入库的签名；格式不符或为空返回 null（历史数据、手工改坏都能安全退化为「不比对」）。 */
    public static int[] decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split(",");
        if (parts.length != DIMS) {
            return null;
        }
        int[] sig = new int[DIMS];
        try {
            for (int i = 0; i < DIMS; i++) {
                sig[i] = Integer.parseUnsignedInt(parts[i].trim());
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return sig;
    }

    private static long fnv(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
