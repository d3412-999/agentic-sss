package org.spring_web.spring_boot_service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SourceArchiveReader 的离线行为测试：现场造包，不依赖 Spring 容器与真实磁盘文件。
 *
 * <p>重点覆盖「读出来会不会出事」：Zip Slip、可执行文件、二进制伪装、截断口径、
 * 四个上限是否真的能中断读取。这些用例的价值不在于覆盖率，而在于把
 * 「上传时拦了、查看时漏了」这类只有线上才发现的漏洞钉死在单测里。</p>
 */
class SourceArchiveReaderTest {

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- 正常读取

    @Test
    void readsTextFilesWithLanguageAndSortedPath() throws Exception {
        Path zip = zipFile("ok.zip",
                file("src/main/java/Hello.java", "public class Hello {}\n"),
                file("pom.xml", "<project/>"));

        SourceArchiveReader.Result result =
                SourceArchiveReader.read(zip, "", SourceArchiveReader.Limits.defaults());

        assertEquals(2, result.totalEntries());
        assertEquals(2, result.files().size());
        assertFalse(result.truncated());
        // 按路径字典序：pom.xml 在 src/... 之前
        assertEquals("pom.xml", result.files().get(0).path());
        assertEquals("xml", result.files().get(0).language());
        assertEquals("src/main/java/Hello.java", result.files().get(1).path());
        assertEquals("java", result.files().get(1).language());
        assertEquals("public class Hello {}\n", result.files().get(1).content());
        assertEquals(0, result.skippedFiles());
        // "public class Hello {}\n"(22) + "<project/>"(10)
        assertEquals(32, result.totalSize());
    }

    @Test
    void prefixIsAppliedWhenMergingMultipleArchives() throws Exception {
        Path zip = zipFile("merge.zip", file("src/App.java", "class App {}\n"));

        SourceArchiveReader.Result result =
                SourceArchiveReader.read(zip, "第一份包/", SourceArchiveReader.Limits.defaults());

        assertEquals("第一份包/src/App.java", result.files().get(0).path());
    }

    @Test
    void skipsBinaryNoiseAndFakeTextEntries() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G', 0, 0, 0, 0};
        byte[] fakeJava = new byte[] {(byte) 'c', (byte) 'l', (byte) 'a', (byte) 's', (byte) 's', 0, 0, 1};
        Path zip = zipFile("mixed.zip",
                file("src/App.java", "class App {}\n"),
                file("logo.png", png),
                file("node_modules/left-pad/index.js", "module.exports = 1\n"),
                file("weird.java", fakeJava));

        SourceArchiveReader.Result result =
                SourceArchiveReader.read(zip, "", SourceArchiveReader.Limits.defaults());

        assertEquals(1, result.files().size());
        assertEquals("src/App.java", result.files().get(0).path());
        // logo.png + node_modules + 伪造扩展名的 weird.java
        assertEquals(3, result.skippedFiles());
    }

    @Test
    void truncatesOversizedFileAndFlagsIt() throws Exception {
        byte[] big = new byte[4096];
        java.util.Arrays.fill(big, (byte) 'a');
        Path zip = zipFile("big.zip", file("Big.java", big));
        SourceArchiveReader.Limits limits = new SourceArchiveReader.Limits(10, 1024, 1_000_000, 10_000);

        SourceArchiveReader.Result result = SourceArchiveReader.read(zip, "", limits);

        assertTrue(result.truncated());
        SourceArchiveReader.Entry entry = result.files().get(0);
        assertTrue(entry.truncated());
        assertEquals(1024, entry.content().length());
        assertEquals(4096, entry.size());
    }

    @Test
    void streamOverloadDoesNotCloseCallerStream() throws Exception {
        Path zip = zipFile("stream.zip", file("A.java", "class A {}\n"));
        ByteArrayInputStream in = new ByteArrayInputStream(Files.readAllBytes(zip));

        SourceArchiveReader.read(in, "", SourceArchiveReader.Limits.defaults());

        assertEquals(-1, in.read());
    }

    // ---------------------------------------------------------------- 安全拒绝

    @Test
    void rejectsZipSlipPath() throws Exception {
        Path zip = zipFile("slip.zip", file("../evil.java", "class Evil {}\n"));

        ArchiveGuard.Violation violation = assertThrows(ArchiveGuard.Violation.class,
                () -> SourceArchiveReader.read(zip, "", SourceArchiveReader.Limits.defaults()));

        assertEquals("ZIP_SLIP", violation.code());
    }

    @Test
    void rejectsAbsolutePathAndWindowsDrive() throws Exception {
        Path unix = zipFile("abs.zip", file("/etc/evil.java", "class Evil {}\n"));
        Path windows = zipFile("drive.zip", file("C:/Windows/evil.java", "class Evil {}\n"));

        assertEquals("ZIP_SLIP", assertThrows(ArchiveGuard.Violation.class,
                () -> SourceArchiveReader.read(unix, "", SourceArchiveReader.Limits.defaults())).code());
        assertEquals("ZIP_SLIP", assertThrows(ArchiveGuard.Violation.class,
                () -> SourceArchiveReader.read(windows, "", SourceArchiveReader.Limits.defaults())).code());
    }

    @Test
    void rejectsExecutableEntryEvenWhenNamedLikeSource() throws Exception {
        Path zip = zipFile("exe.zip", file("bin/run.exe", "MZ"));

        ArchiveGuard.Violation violation = assertThrows(ArchiveGuard.Violation.class,
                () -> SourceArchiveReader.read(zip, "", SourceArchiveReader.Limits.defaults()));

        assertEquals("UNSAFE_FILE", violation.code());
    }

    // ---------------------------------------------------------------- 上限中断

    @Test
    void rejectsTooManyTextFiles() throws Exception {
        Path zip = zipFile("many.zip",
                file("A.java", "class A {}\n"),
                file("B.java", "class B {}\n"),
                file("C.java", "class C {}\n"));
        SourceArchiveReader.Limits limits = new SourceArchiveReader.Limits(2, 1_000_000, 10_000_000, 10_000);

        ArchiveGuard.Violation violation = assertThrows(ArchiveGuard.Violation.class,
                () -> SourceArchiveReader.read(zip, "", limits));

        assertEquals("TOO_MANY_FILES", violation.code());
    }

    @Test
    void rejectsUnpackedTotalSizeLimit() throws Exception {
        byte[] text = new byte[8192];
        java.util.Arrays.fill(text, (byte) 'b');
        Path zip = zipFile("bomb.zip", file("payload.txt", text));
        SourceArchiveReader.Limits limits = new SourceArchiveReader.Limits(100, 1_000_000, 1024, 10_000);

        ArchiveGuard.Violation violation = assertThrows(ArchiveGuard.Violation.class,
                () -> SourceArchiveReader.read(zip, "", limits));

        assertEquals("UNPACKED_TOO_LARGE", violation.code());
        assertEquals("解压后超过 1 KB", violation.message());
    }

    @Test
    void stopsWhenTimeoutIsExceeded() throws Exception {
        List<Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            entries.add(file("src/F" + i + ".java", "class F {}\n"));
        }
        Path zip = zipFile("slow.zip", entries.toArray(new Entry[0]));
        SourceArchiveReader.Limits limits = new SourceArchiveReader.Limits(1000, 1_000_000, 10_000_000, 1);

        ArchiveGuard.Violation violation = assertThrows(ArchiveGuard.Violation.class,
                () -> SourceArchiveReader.read(zip, "", limits));

        assertEquals("TIMEOUT", violation.code());
    }

    @Test
    void emptyArchiveReturnsEmptyListInsteadOfFailing() throws Exception {
        Path zip = zipFile("empty.zip");

        SourceArchiveReader.Result result =
                SourceArchiveReader.read(zip, "", SourceArchiveReader.Limits.defaults());

        assertTrue(result.files().isEmpty());
        assertEquals(0, result.totalEntries());
        assertFalse(result.truncated());
    }

    // ---------------------------------------------------------------- 辅助方法

    private record Entry(String name, byte[] content) {
    }

    private static Entry file(String name, String content) {
        return new Entry(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry file(String name, byte[] content) {
        return new Entry(name, content);
    }

    private Path zipFile(String filename, Entry... entries) throws IOException {
        Path path = tempDir.resolve(filename);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return path;
    }
}
