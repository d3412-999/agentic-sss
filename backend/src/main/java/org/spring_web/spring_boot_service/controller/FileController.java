package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.spring_web.spring_boot_service.service.FileAccessService;
import org.spring_web.spring_boot_service.service.FileStorageService;
import org.spring_web.spring_boot_service.service.FileUploadService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传与下载。
 *
 * <p>从 SubmissionController 里抽出来，是因为题目附件已经变成一等公民：
 * 批量上传、格式校验、内嵌预览各自有明确契约，混在提交物控制器里迟早会互相牵制。</p>
 *
 * <p>下载鉴权保持三种放行：上传者本人 / 教师 / 题目附件所属开课的名单内学生。
 * 内嵌预览（{@code inline=true}）只对 PDF 生效，其它类型一律强制下载，避免浏览器把
 * 未知 MIME 当 HTML 执行。</p>
 */
@Tag(name = "文件")
@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileUploadService fileUploadService;
    private final FileStorageService fileStorage;
    private final FileAccessService fileAccessService;

    /** 兼容旧客户端的单文件入口，同样走三重校验。 */
    @PostMapping("/api/v1/files")
    public Result<CommonDtos.FileVO> uploadFile(@RequestPart("file") MultipartFile file) {
        return Result.ok(fileUploadService.uploadOne(file));
    }

    /**
     * 批量上传：multipart 字段名 {@code files}（可重复）。
     *
     * <p>数量与总大小超限在写盘前就 400 掉，不会产生半成品文件；
     * 单个文件的格式/大小问题不影响其它文件，逐条返回失败原因。</p>
     */
    @PostMapping("/api/v1/files/batch")
    public Result<CommonDtos.BatchUploadVO> uploadBatch(@RequestPart("files") List<MultipartFile> files) {
        return Result.ok(fileUploadService.uploadTopicAttachments(files));
    }

    /**
     * 文件元数据（只读）。
     *
     * <p>前端把「已上传成功、但还没提交」的文件记在本地，刷新后要用这条接口确认
     * 文件是否还在：库里的记录可能已被清理（逻辑删除），盘上的文件也可能丢了。</p>
     *
     * <p>两种「不存在」都返回 200 + {@code exists=false}，不抛 404：本地记录失效是
     * 预期结果，抛 404 会被前端统一错误处理当成请求失败弹提示。已删除的文件拿不到
     * {@code createdBy}，因此不再做归属判断——此时响应里除了 {@code fileId} 与
     * {@code exists=false} 不含任何信息。</p>
     */
    @GetMapping("/api/v1/files/{fileId}")
    public Result<CommonDtos.FileInfoVO> fileInfo(@PathVariable String fileId) {
        FileObject file = fileStorage.loadOrNull(fileId);
        if (file == null) {
            return Result.ok(new CommonDtos.FileInfoVO(fileId, null, 0L, null, null, null, false));
        }
        boolean owner = file.getCreatedBy() != null
                && file.getCreatedBy().equals(SecurityUtils.userId());
        var current = SecurityUtils.current().orElse(null);
        boolean privileged = current != null && current.isTeacher();
        boolean topicAttachment = current != null
                && fileAccessService.canAccessTopicAttachment(file.getId(), current.id());
        if (!owner && !privileged && !topicAttachment) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看该文件");
        }
        boolean onDisk = fileStorage.locate(file) != null;
        return Result.ok(new CommonDtos.FileInfoVO(
                String.valueOf(file.getId()),
                file.getFilename(),
                file.getSizeBytes() == null ? 0L : file.getSizeBytes(),
                file.getSha256(),
                file.getContentType(),
                Times.offset(file.getCreatedAt()),
                onDisk));
    }

    @GetMapping("/api/v1/files/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId,
                                                 @RequestParam(defaultValue = "false") boolean inline,
                                                 HttpServletRequest request) {
        FileObject file = fileStorage.load(fileId);
        boolean owner = file.getCreatedBy() != null
                && file.getCreatedBy().equals(SecurityUtils.userId());
        var current = SecurityUtils.current().orElse(null);
        boolean privileged = current != null && current.isTeacher();
        // 题目附件由教师上传、名单内学生下载：单凭「上传者或教师」会让学生的下载链接 403
        boolean topicAttachment = current != null
                && fileAccessService.canAccessTopicAttachment(file.getId(), current.id());
        if (!owner && !privileged && !topicAttachment) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权下载该文件");
        }
        var path = fileStorage.locate(file);
        if (path == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在或已丢失");
        }
        String filename = file.getFilename() == null ? "download" : file.getFilename();
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String contentType = file.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
        boolean preview = inline && MediaType.APPLICATION_PDF_VALUE.equals(contentType);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (preview ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded)
                // 禁止浏览器猜类型：即使文件内容是 HTML 也不会被当页面执行
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
    }
}
