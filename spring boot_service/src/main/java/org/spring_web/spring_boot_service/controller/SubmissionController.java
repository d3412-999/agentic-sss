package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.spring_web.spring_boot_service.service.FileStorageService;
import org.spring_web.spring_boot_service.service.SubmissionService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提交物与文件：上传、版本列表、详情、流式下载、重新分析。
 */
@Tag(name = "提交物与文件")
@RestController
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;
    private final FileStorageService fileStorage;

    @PostMapping("/api/v1/student/selections/{selectionId}/source")
    public ResponseEntity<Result<CommonDtos.UploadResultVO>> uploadSource(
            @PathVariable String selectionId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String remark,
            @RequestHeader(value = "X-File-Sha256", required = false) String sha256) {
        return ResponseEntity.accepted().body(Result.ok(
                submissionService.upload(selectionId, "SOURCE", file, remark, sha256)));
    }

    @PostMapping("/api/v1/student/selections/{selectionId}/report")
    public ResponseEntity<Result<CommonDtos.UploadResultVO>> uploadReport(
            @PathVariable String selectionId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String remark,
            @RequestHeader(value = "X-File-Sha256", required = false) String sha256) {
        return ResponseEntity.accepted().body(Result.ok(
                submissionService.upload(selectionId, "REPORT", file, remark, sha256)));
    }

    @GetMapping("/api/v1/student/selections/{selectionId}/submissions")
    public Result<PageResult<CommonDtos.SubmissionBriefVO>> submissions(@PathVariable String selectionId) {
        return Result.ok(submissionService.list(selectionId));
    }

    @GetMapping("/api/v1/submissions/{submissionId}")
    public Result<CommonDtos.SubmissionVO> submissionDetail(@PathVariable String submissionId) {
        return Result.ok(submissionService.detail(submissionId));
    }

    @GetMapping("/api/v1/submissions/{submissionId}/download")
    public ResponseEntity<Resource> downloadSubmission(@PathVariable String submissionId) {
        return stream(submissionService.download(submissionId));
    }

    @DeleteMapping("/api/v1/submissions/{submissionId}")
    public Result<Void> deleteSubmission(@PathVariable String submissionId) {
        submissionService.delete(submissionId);
        return Result.ok();
    }

    @PostMapping("/api/v1/submissions/{submissionId}/reanalyze")
    public ResponseEntity<Result<CommonDtos.TaskAccepted>> reanalyze(
            @PathVariable String submissionId,
            @RequestParam(defaultValue = "false") boolean force) {
        return ResponseEntity.accepted().body(
                Result.ok(submissionService.reanalyze(submissionId, force)));
    }

    @PostMapping("/api/v1/files")
    public Result<CommonDtos.FileVO> uploadFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "TOPIC_ATTACHMENT") String purpose,
            @RequestHeader(value = "X-File-Sha256", required = false) String sha256) {
        FileObject stored = fileStorage.store(file, purpose, sha256, 20L * 1024 * 1024);
        return Result.ok(fileStorage.toVo(stored));
    }

    @GetMapping("/api/v1/files/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId) {
        FileObject file = fileStorage.load(fileId);
        // 文件归属校验：只允许上传者本人或教师下载；导出文件由发起人取回
        boolean owner = file.getCreatedBy() != null
                && file.getCreatedBy().equals(SecurityUtils.userId());
        var current = SecurityUtils.current().orElse(null);
        boolean privileged = current != null && current.isTeacher();
        if (!owner && !privileged) {
            throw new org.spring_web.spring_boot_service.common.BizException(
                    org.spring_web.spring_boot_service.common.ErrorCode.FORBIDDEN, "无权下载该文件");
        }
        return stream(new SubmissionService.Download(file.getFilename(), file.getContentType(),
                fileStorage.locate(file)));
    }

    private ResponseEntity<Resource> stream(SubmissionService.Download download) {
        if (download == null || download.path() == null) {
            throw new org.spring_web.spring_boot_service.common.BizException(
                    org.spring_web.spring_boot_service.common.ErrorCode.NOT_FOUND, "文件不存在");
        }
        String encoded = URLEncoder.encode(
                download.filename() == null ? "download" : download.filename(),
                StandardCharsets.UTF_8).replace("+", "%20");
        String contentType = download.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : download.contentType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(new FileSystemResource(download.path()));
    }
}
