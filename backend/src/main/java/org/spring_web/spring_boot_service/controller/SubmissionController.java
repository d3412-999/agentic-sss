package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.service.ReviewStatusService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提交物：上传、版本列表、详情、流式下载、重新分析。
 *
 * <p>通用文件上传/下载已迁到 {@link FileController}（批量、格式校验、内嵌预览），
 * 这里只保留源码与报告这两类提交物的业务动作。</p>
 */
@Tag(name = "提交物")
@RestController
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;
    private final ReviewStatusService reviewStatusService;

    /**
     * 统一评审状态：源码与报告各自 PENDING / RUNNING / SUCCEEDED / FAILED + 分数 + 失败原因。
     *
     * <p>前端只认这一份状态做轮询与「是否停止」判断，不再靠反复拉详情猜进度。</p>
     */
    @GetMapping("/api/v1/submissions/{submissionId}/review-status")
    public Result<CommonDtos.ReviewStatusVO> reviewStatus(@PathVariable String submissionId) {
        return Result.ok(reviewStatusService.status(submissionId));
    }

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

    /**
     * 两阶段提交的第一步：单个文件落盘，返回 fileId。
     *
     * <p>一文件一请求，前端才能给出逐文件进度、逐文件重试与取消；
     * 校验（扩展名、魔数、大小、Zip Slip）在这一步就完成，坏包不会进入提交列表。</p>
     */
    @PostMapping("/api/v1/student/selections/{selectionId}/files")
    public Result<CommonDtos.FileVO> uploadSubmissionFile(
            @PathVariable String selectionId,
            @RequestParam String type,
            @RequestPart("file") MultipartFile file) {
        return Result.ok(submissionService.storeFile(selectionId, type, file));
    }

    /**
     * 两阶段提交的第二步：绑定已上传的文件列表。
     *
     * <p>先走 {@code POST /api/v1/files/batch} 拿 fileId，再调这里确认。
     * 这样某个坏包被拒时，其余好包不用重传——单阶段 multipart 做不到这件事。</p>
     */
    @PostMapping("/api/v1/student/selections/{selectionId}/submissions")
    public ResponseEntity<Result<CommonDtos.UploadResultVO>> bindFiles(
            @PathVariable String selectionId,
            @RequestBody CommonDtos.BindFilesRequest request) {
        return ResponseEntity.accepted().body(Result.ok(submissionService.uploadBatch(
                selectionId, request.type(), request.fileIds(), request.remark())));
    }

    @DeleteMapping("/api/v1/submissions/{submissionId}/files/{versionFileId}")
    public Result<Void> deleteFile(@PathVariable String submissionId,
                                   @PathVariable String versionFileId) {
        submissionService.deleteFile(submissionId, versionFileId);
        return Result.ok();
    }

    /** 调整同一版本内的文件顺序，请求体为 versionFileId 的有序数组。 */
    @PutMapping("/api/v1/submissions/{submissionId}/files/order")
    public Result<Void> reorder(@PathVariable String submissionId,
                                @RequestBody List<String> versionFileIds) {
        submissionService.reorder(submissionId, versionFileIds);
        return Result.ok();
    }

    /**
     * 单文件下载 / 内嵌预览。
     *
     * <p>{@code inline=true} 只对 PDF 生效，其它类型强制 attachment——
     * 与 {@link FileController} 的规则保持一致，避免浏览器把未知 MIME 当页面执行。</p>
     */
    @GetMapping("/api/v1/submissions/{submissionId}/files/{versionFileId}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable String submissionId,
                                                 @PathVariable String versionFileId,
                                                 @RequestParam(defaultValue = "false") boolean inline) {
        return stream(submissionService.downloadFile(submissionId, versionFileId), inline);
    }

    @GetMapping("/api/v1/student/selections/{selectionId}/submissions")
    public Result<PageResult<CommonDtos.SubmissionBriefVO>> submissions(@PathVariable String selectionId) {
        return Result.ok(submissionService.list(selectionId));
    }

    /** 提交物详情；带 {@code versionId} 时返回该历史版本（只读）。 */
    @GetMapping("/api/v1/submissions/{submissionId}")
    public Result<CommonDtos.SubmissionVO> submissionDetail(
            @PathVariable String submissionId,
            @RequestParam(required = false) String versionId) {
        return Result.ok(submissionService.detail(submissionId, versionId));
    }

    /** 版本历史：前端版本选择器用，默认最新版本在列表首位。 */
    @GetMapping("/api/v1/submissions/{submissionId}/versions")
    public Result<List<CommonDtos.SubmissionVersionVO>> versions(@PathVariable String submissionId) {
        return Result.ok(submissionService.versions(submissionId));
    }

    @GetMapping("/api/v1/submissions/{submissionId}/download")
    public ResponseEntity<Resource> downloadSubmission(@PathVariable String submissionId) {
        return stream(submissionService.download(submissionId));
    }

    /**
     * 相似度命中列表：本人可看自己的，教师可看本开课学生的。
     *
     * <p>命中只是线索不是结论，所以这里只给「和谁、哪个文件、像到什么程度」，
     * 定性交给教师在答辩环节当面确认。</p>
     */
    @GetMapping("/api/v1/submissions/{submissionId}/similarities")
    public Result<List<CommonDtos.SimilarityVO>> similarities(@PathVariable String submissionId) {
        return Result.ok(submissionService.similarities(submissionId));
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

    private ResponseEntity<Resource> stream(SubmissionService.Download download) {
        return stream(download, false);
    }

    private ResponseEntity<Resource> stream(SubmissionService.Download download, boolean inline) {
        if (download == null || download.path() == null) {
            throw new org.spring_web.spring_boot_service.common.BizException(
                    org.spring_web.spring_boot_service.common.ErrorCode.NOT_FOUND, "文件不存在");
        }
        String encoded = URLEncoder.encode(
                download.filename() == null ? "download" : download.filename(),
                StandardCharsets.UTF_8).replace("+", "%20");
        String contentType = download.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : download.contentType();
        boolean preview = inline && MediaType.APPLICATION_PDF_VALUE.equals(contentType);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (preview ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(download.path()));
    }
}
