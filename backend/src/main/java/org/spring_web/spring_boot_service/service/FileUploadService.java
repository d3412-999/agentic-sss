package org.spring_web.spring_boot_service.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 批量上传编排：先做「请求级」校验，再逐文件做「文件级」校验与落盘。
 *
 * <p>两级校验的区别很关键：</p>
 * <ul>
 *   <li>请求级（数量、总大小）在写任何文件之前完成，超限直接 400，磁盘上不会留下半个文件；</li>
 *   <li>文件级（格式、单文件大小、魔数、MIME）逐个 try/catch，一个失败不影响其他文件，
 *       结果里逐条返回原因，前端可以只重试失败项。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadService {

    public static final String TOPIC_ATTACHMENT = "TOPIC_ATTACHMENT";

    private final FileTypeValidator validator;
    private final FileStorageService storage;

    public CommonDtos.UploadPolicyVO policy() {
        SssProperties.TopicAttachment policy = validator.policy();
        return new CommonDtos.UploadPolicyVO(List.copyOf(policy.getAllowedExtensions()),
                policy.getMaxFileBytes(), policy.getMaxFilesPerRequest(),
                policy.getMaxTotalBytes(), policy.getMaxPerTopic());
    }

    public CommonDtos.BatchUploadVO uploadTopicAttachments(List<MultipartFile> files) {
        SssProperties.TopicAttachment policy = validator.policy();
        List<MultipartFile> present = files == null ? List.of()
                : files.stream().filter(Objects::nonNull).toList();
        BizAssert.param(!present.isEmpty(), "请至少选择一个文件");
        BizAssert.param(present.size() <= policy.getMaxFilesPerRequest(),
                "单次最多上传 " + policy.getMaxFilesPerRequest() + " 个文件，本次选择了 " + present.size() + " 个");
        long total = present.stream().mapToLong(MultipartFile::getSize).sum();
        BizAssert.param(total <= policy.getMaxTotalBytes(),
                "本次文件合计 " + FileTypeValidator.humanSize(total)
                        + "，超过单次总量上限 " + validator.maxTotalText());

        List<CommonDtos.FileVO> succeeded = new ArrayList<>();
        List<CommonDtos.FailedUpload> failed = new ArrayList<>();
        for (MultipartFile file : present) {
            String rawName = validator.cleanName(file.getOriginalFilename());
            try {
                FileTypeValidator.Validated validated = validator.validate(file);
                FileObject stored = storage.storeValidated(file, validated, TOPIC_ATTACHMENT);
                succeeded.add(storage.toVo(stored));
            } catch (BizException ex) {
                failed.add(new CommonDtos.FailedUpload(rawName, ex.getMessage()));
            } catch (RuntimeException ex) {
                // 单个文件的意外错误不能拖垮整批：记录日志，返回可重试的提示
                log.error("批量上传单个文件失败，filename={}", rawName, ex);
                failed.add(new CommonDtos.FailedUpload(rawName, "上传失败，请重试"));
            }
        }
        if (succeeded.isEmpty()) {
            // 全部失败时仍返回 200 + 逐条原因，前端展示更友好；真正的参数问题已在上面 400 掉
            return new CommonDtos.BatchUploadVO(succeeded, failed);
        }
        return new CommonDtos.BatchUploadVO(succeeded, failed);
    }

    /** 单文件兼容入口：沿用旧 /files 接口，但同样走三重校验。 */
    public CommonDtos.FileVO uploadOne(MultipartFile file) {
        FileTypeValidator.Validated validated = validator.validate(file);
        FileObject stored = storage.storeValidated(file, validated, TOPIC_ATTACHMENT);
        return storage.toVo(stored);
    }
}
