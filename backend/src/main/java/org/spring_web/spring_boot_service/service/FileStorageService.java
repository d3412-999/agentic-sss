package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.mapper.FileObjectMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

/**
 * 本地文件存储（MinIO / OSS 预留）。
 *
 * <p>三个要点：</p>
 * <ol>
 *   <li>SHA-256 命中已有文件直接复用，实现「秒传」且不重复占磁盘；</li>
 *   <li>objectKey 由服务端生成 UUID，绝不使用用户上传的文件名做路径，杜绝路径穿越；</li>
 *   <li>读取时再次 normalize 并校验前缀，即使数据库被写入脏 objectKey 也逃不出 root。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final DateTimeFormatter DIR = DateTimeFormatter.ofPattern("yyyy/MM", Locale.ROOT);

    private final FileObjectMapper fileObjectMapper;
    private final SssProperties properties;

    private Path root;

    @PostConstruct
    public void init() throws IOException {
        root = Paths.get(properties.getStorage().getLocalDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public FileObject store(MultipartFile file, String purpose, String expectedSha256, long maxBytes) {
        BizAssert.param(file != null && !file.isEmpty(), "文件不能为空");
        BizAssert.param(file.getSize() <= maxBytes, "文件超过大小限制");
        String filename = StringUtils.hasText(file.getOriginalFilename())
                ? Paths.get(file.getOriginalFilename()).getFileName().toString() : "upload.bin";
        BizAssert.param(!filename.contains("..") && !filename.contains("/") && !filename.contains("\\"),
                "文件名不合法");

        String sha256;
        try (InputStream in = file.getInputStream()) {
            sha256 = sha256(in);
        } catch (IOException ex) {
            throw new BizException(ErrorCode.FILE_INVALID, "读取上传文件失败");
        }
        if (StringUtils.hasText(expectedSha256) && !expectedSha256.equalsIgnoreCase(sha256)) {
            throw new BizException(ErrorCode.FILE_INVALID, "文件 SHA-256 校验失败");
        }
        FileObject existing = fileObjectMapper.selectOne(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getSha256, sha256).last("limit 1"));
        if (existing != null && locate(existing) != null) {
            return existing;
        }

        String objectKey = LocalDate.now(Times.BUSINESS_ZONE).format(DIR)
                + "/" + UUID.randomUUID() + "-" + filename;
        Path target = safeResolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "保存文件失败");
        }

        FileObject object = new FileObject();
        object.setStorage("LOCAL");
        object.setBucket(purpose);
        object.setObjectKey(objectKey);
        object.setFilename(filename);
        object.setContentType(file.getContentType());
        object.setSizeBytes(file.getSize());
        object.setSha256(sha256);
        object.setCreatedBy(SecurityUtils.userId());
        object.setCreatedAt(Times.now());
        object.setUpdatedAt(Times.now());
        fileObjectMapper.insert(object);
        return object;
    }

    /**
     * 供成绩导出等服务端生成的文件复用同一套存储与索引逻辑。
     * operatorId 需由调用方显式传入——异步线程里没有 SecurityContext，取不到当前用户。
     */
    public FileObject storeBytes(String filename, byte[] content, String contentType,
                                 String purpose, Long operatorId) {
        String objectKey = LocalDate.now(Times.BUSINESS_ZONE).format(DIR)
                + "/" + UUID.randomUUID() + "-" + filename;
        Path target = safeResolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "保存导出文件失败");
        }
        FileObject object = new FileObject();
        object.setStorage("LOCAL");
        object.setBucket(purpose);
        object.setObjectKey(objectKey);
        object.setFilename(filename);
        object.setContentType(contentType);
        object.setSizeBytes((long) content.length);
        object.setCreatedBy(operatorId);
        object.setCreatedAt(Times.now());
        object.setUpdatedAt(Times.now());
        fileObjectMapper.insert(object);
        return object;
    }

    /** 返回 null 表示文件记录存在但磁盘上已丢失。 */
    public Path locate(FileObject object) {
        Path path = safeResolve(object.getObjectKey());
        return Files.isRegularFile(path) ? path : null;
    }

    public FileObject load(String fileId) {
        return BizAssert.found(fileObjectMapper.selectById(BizAssert.id(fileId, "fileId")), "文件");
    }

    public CommonDtos.FileVO toVo(FileObject object) {
        return new CommonDtos.FileVO(String.valueOf(object.getId()), object.getFilename(),
                object.getSizeBytes() == null ? 0 : object.getSizeBytes(),
                object.getSha256(), object.getContentType());
    }

    private Path safeResolve(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new BizException(ErrorCode.FILE_INVALID, "文件对象键为空");
        }
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root)) {
            throw new BizException(ErrorCode.FILE_INVALID, "文件对象键非法");
        }
        return path;
    }

    private String sha256(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
