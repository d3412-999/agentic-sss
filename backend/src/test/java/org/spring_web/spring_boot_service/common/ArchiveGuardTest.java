package org.spring_web.spring_boot_service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ArchiveGuard 的离线行为测试：用 {@link ZipOutputStream} 现场造包，
 * 不依赖 Spring 容器、不读取仓库外的真实文件。
 */
class ArchiveGuardTest {

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- 正常包

    @Test
    void validArchiveCollectsStatsAndMavenBuildTool() throws Exception {
        String javaSource = "public class Hello {\n    int x = 1;\n}\n";
        Path zip = zipFile("valid.zip",
                file("src/Hello.java", javaSource),
                file("pom.xml", "<project/>"));

        ArchiveGuard.Scan scan = ArchiveGuard.scan(zip, ArchiveGuard.Limits.defaults());

        assertEquals(1, scan.javaFiles());
        assertEquals(2, scan.totalFiles());
        assertEquals(3, scan.codeLines());
        assertEquals("MAVEN", scan.buildTool());
        assertEquals("// src/Hello.java\n" + javaSource + "\n", scan.snippet());
    }

    @Test
    void detectsGradleBuildTool() throws Exception {
        Path zip = zipFile("gradle.zip",
                file("src/App.java", "class App {}\n"),
                file("build.gradle.kts", "plugins {}\n"));

        ArchiveGuard.Scan scan = ArchiveGuard.scan(zip, ArchiveGuard.Limits.defaults());

        assertEquals("GRADLE", scan.buildTool());
        assertEquals(1, scan.javaFiles());
    }

    @Test
    void snippetIsCappedAt30000Chars() throws Exception {
        StringBuilder source = new StringBuilder("class Big {\n");
        while (source.length() < 40_000) {
            source.append("int value = 1;\n");
        }
        Path zip = zipFile("big.zip", file("Big.java", source.toString()));

        ArchiveGuard.Scan scan = ArchiveGuard.scan(zip, ArchiveGuard.Limits.defaults());

        assertEquals(30_000, scan.snippet().length());
        assertTrue(scan.snippet().startsWith("// Big.java\n"));
    }

    @Test
    void streamOverloadDoesNotCloseCallerStream() throws Exception {
        Path zip = zipFile("stream.zip", file("A.java", "class A {}\n"));
        ByteArrayInputStream in = new ByteArrayInputStream(Files.readAllBytes(zip));

        ArchiveGuard.scan(in, ArchiveGuard.Limits.defaults());

        // 流若被关闭，read() 会抛 IOException；未关闭且已读到末尾则返回 -1
        assertEquals(-1, in.read());
    }

    // ---------------------------------------------------------------- 安全校验

    @Test
    void rejectsZipSlipPath() throws Exception {
        Path zip = zipFile("slip.zip", file("../evil.java", "class Evil {}\n"));

        ArchiveGuard.Violation violation = assertThrows(ArchiveGuard.Violation.class,
                () -> ArchiveGuard.scan(zip, ArchiveGuard.Limits.defaults()));

        assertEquals("ZIP_SLIP", violation.code());
        assertEquals("压缩包包含非法路径：../evil.java", violation.message());
    }

    @Test
    void rejectsAbsolutePath() throws Exception {
        Path zip = zipFile("absolute.zip", file("/etc/evil.java", "class Evil {}\n"));

        ArchiveGuard.Violation violation = assertThrows(ArchiveGuard.Violation.class,
                () -> ArchiveGuard.scan(zip, ArchiveGuard.Limits.defaults()));

        assertEquals("ZIP_SLIP", violation.code());
    }

    @Test
    void rejectsExecutableEntry() throws Exception {
        Path zip = zipFile("script.zip", file("run.sh", "#!/bin/sh\n"));

        ArchiveGuard.Violation violation = assertThrows(ArchiveGuard.Violation.class,
                () -> ArchiveGuard.scan(zip, ArchiveGuard.Limits.defaults()));

        assertEquals("UNSAFE_FILE", violation.code());
        assertEquals("压缩包包含可执行文件：run.sh", violation.message());
    }

    @Test
    void rejectsTooManyEntries() throws Exception {
        Path zip = zipFile("many.zip",
                file("a.java", "class A {}\n"),
                file("b.java", "class B {}\n"),
                file("c.java", "class C {}\n"));
        ArchiveGuard.Limits limits = new ArchiveGuard.Limits(2, 10L * 1024 * 1024);

        ArchiveGuard.Violation violation = assertThrows(ArchiveGuard.Violation.class,
                () -> ArchiveGuard.scan(zip, limits));

        assertEquals("TOO_MANY_ENTRIES", violation.code());
        assertEquals("压缩包文件数超过 2", violation.message());
    }

    @Test
    void rejectsUnpackedSizeLimit() throws Exception {
        byte[] payload = new byte[4096];
        // 随机字节基本不可压缩，保证「解压后」远超 1KB，而不是被压缩率蒙混过关
        new Random(42).nextBytes(payload);
        Path zip = zipFile("bomb.zip", file("payload.bin", payload));
        ArchiveGuard.Limits limits = new ArchiveGuard.Limits(100, 1024);

        ArchiveGuard.Violation violation = assertThrows(ArchiveGuard.Violation.class,
                () -> ArchiveGuard.scan(zip, limits));

        assertEquals("UNPACKED_TOO_LARGE", violation.code());
        assertEquals("解压后超过 1 KB", violation.message());
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

    /** 在临时目录里现场生成 zip 文件。 */
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
