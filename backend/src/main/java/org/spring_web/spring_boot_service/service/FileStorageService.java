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
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地文件存储（MinIO / OSS 预留）。
 *
 * <p>四个要点：</p>
 * <ol>
 *   <li>SHA-256 命中同一上传者、同一用途的已有文件直接复用，实现「秒传」且不重复占磁盘；
 *       去重范围必须带上 bucket + created_by，否则会把别人的文件「借用」过来，后续鉴权直接错乱；</li>
 *   <li>objectKey 由服务端生成 UUID，绝不使用用户上传的文件名做路径，杜绝路径穿越；</li>
 *   <li>读取时再次 normalize 并校验前缀，即使数据库被写入脏 objectKey 也逃不出 root；</li>
 *   <li>先落盘再入库，入库失败立刻删掉刚落盘的文件，避免留下孤儿文件。</li>
 * </ol>
 */
@Slf4j
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
        return persist(file, filename, file.getContentType(), purpose, expectedSha256);
    }

    /**
     * 题目附件专用入口：文件名与 contentType 已在 {@link FileTypeValidator} 里清洗、归一，
     * 这里只负责去重、流式落盘与入库。
     */
    public FileObject storeValidated(MultipartFile file, FileTypeValidator.Validated validated, String purpose) {
        return persist(file, validated.filename(), validated.contentType(), purpose, null);
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

    /**
     * 查不到（含逻辑删除、id 非法）时返回 null，不抛异常。
     *
     * <p>给「本地记录是否还有效」这类只读校验用：记录失效是正常结果，不是错误。</p>
     */
    public FileObject loadOrNull(String fileId) {
        Long id = BizAssert.parseIdOrNull(fileId);
        return id == null ? null : fileObjectMapper.selectById(id);
    }

    public CommonDtos.FileVO toVo(FileObject object) {
        return new CommonDtos.FileVO(String.valueOf(object.getId()), object.getFilename(),
                object.getSizeBytes() == null ? 0 : object.getSizeBytes(),
                object.getSha256(), object.getContentType());
    }

    /**
     * 删除文件记录与磁盘文件，任何一步失败都只记日志不抛出。
     *
     * <p>附件解绑属于「用户已经看到成功」的操作，清理失败不该把整个请求变成 500；
     * 残留的孤儿文件由后续巡检处理，比让用户重试更合理。</p>
     */
    public void deleteQuietly(FileObject object) {
        if (object == null) {
            return;
        }
        try {
            Path path = safeResolve(object.getObjectKey());
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ex) {
            log.warn("删除磁盘文件失败，objectKey={}, cause={}", object.getObjectKey(), ex.getMessage());
        }
        try {
            fileObjectMapper.deleteById(object.getId());
        } catch (RuntimeException ex) {
            log.warn("删除文件记录失败，fileId={}, cause={}", object.getId(), ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- 内部

    private FileObject persist(MultipartFile file, String filename, String contentType,
                               String purpose, String expectedSha256) {
        String sha256;
        try (InputStream in = file.getInputStream()) {
            sha256 = sha256(in);
        } catch (IOException ex) {
            throw new BizException(ErrorCode.FILE_INVALID, "读取上传文件失败");
        }
        if (StringUtils.hasText(expectedSha256) && !expectedSha256.equalsIgnoreCase(sha256)) {
            throw new BizException(ErrorCode.FILE_INVALID, "文件 SHA-256 校验失败");
        }

        Long uploader = SecurityUtils.userId();
        FileObject existing = fileObjectMapper.selectOne(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getSha256, sha256)
                .eq(FileObject::getBucket, purpose)
                .eq(FileObject::getCreatedBy, uploader)
                .last("limit 1"));
        if (existing != null && locate(existing) != null) {
            return existing;
        }

        String objectKey = LocalDate.now(Times.BUSINESS_ZONE).format(DIR)
                + "/" + UUID.randomUUID() + "-" + filename;
        Path target = safeResolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            // 流式拷贝：大文件不会一次性读进堆内存
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
        object.setContentType(contentType);
        object.setSizeBytes(file.getSize());
        object.setSha256(sha256);
        object.setCreatedBy(uploader);
        object.setCreatedAt(Times.now());
        object.setUpdatedAt(Times.now());
        try {
            fileObjectMapper.insert(object);
        } catch (RuntimeException ex) {
            // 入库失败必须把刚落盘的文件删掉，否则磁盘会慢慢被孤儿文件吃掉
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanup) {
                log.warn("清理孤儿文件失败，objectKey={}", objectKey, cleanup);
            }
            throw ex;
        }
        return object;
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
