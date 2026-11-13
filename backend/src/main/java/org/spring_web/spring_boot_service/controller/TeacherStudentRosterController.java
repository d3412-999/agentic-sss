package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.Idempotent;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.StudentImportDtos;
import org.spring_web.spring_boot_service.service.StudentRosterImportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 教师端：教学班学生名单的批量导入、样例下载、名单导出与批量移除。
 *
 * <p>路径都在 {@code /api/v1/teacher/offerings/{offeringId}/students/**} 下，
 * 由 SecurityConfig 限定 TEACHER 角色，Service 里再按 offering.teacherId 做归属校验。</p>
 */
@Tag(name = "教师 - 学生名单导入与导出")
@RestController
@RequestMapping("/api/v1/teacher/offerings/{offeringId}/students")
@RequiredArgsConstructor
public class TeacherStudentRosterController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final StudentRosterImportService importService;

    @Operation(summary = "导入限额（扩展名 / 文件大小 / 条数），前端提示与后端校验共用")
    @GetMapping("/import/policy")
    public Result<StudentImportDtos.ImportPolicyVO> policy(@PathVariable String offeringId) {
        return Result.ok(importService.policy());
    }

    @Operation(summary = "解析粘贴的文本，返回逐行预览（只读，不写库）")
    @PostMapping("/import/parse-text")
    public Result<StudentImportDtos.PreviewVO> parseText(
            @PathVariable String offeringId,
            @Valid @RequestBody StudentImportDtos.TextParseRequest request) {
        return Result.ok(importService.previewText(offeringId, request.text()));
    }

    @Operation(summary = "解析上传的 xlsx / xls / docx / csv，返回逐行预览（只读，不写库）")
    @PostMapping(value = "/import/parse-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<StudentImportDtos.PreviewVO> parseFile(
            @PathVariable String offeringId,
            @RequestParam("file") MultipartFile file) {
        return Result.ok(importService.previewFile(offeringId, file));
    }

    @Operation(summary = "确认导入：逐行短事务，返回成功 / 跳过 / 失败明细")
    @Idempotent
    @PostMapping("/import/confirm")
    public Result<StudentImportDtos.ConfirmVO> confirm(
            @PathVariable String offeringId,
            @Valid @RequestBody StudentImportDtos.ConfirmRequest request) {
        return Result.ok(importService.confirm(offeringId, request));
    }

    @Operation(summary = "下载导入样例（xlsx，表头：学号 / 姓名 / 邮箱）")
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> template(@PathVariable String offeringId) {
        byte[] body = importService.template(offeringId);
        return download(body, XLSX_MIME, "学生名单导入样例.xlsx");
    }

    @Operation(summary = "下载名单（CSV，含学号 / 姓名 / 班级 / 状态 / 选题数 / 最近登录）")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable String offeringId) {
        String csv = importService.exportCsv(offeringId);
        return download(csv.getBytes(StandardCharsets.UTF_8), "text/csv;charset=UTF-8", "学生名单.csv");
    }

    @Operation(summary = "批量移除：只解除与当前教学班的关联，不影响账号与其他班级")
    @PostMapping("/batch-remove")
    public Result<StudentImportDtos.BatchRemoveVO> batchRemove(
            @PathVariable String offeringId,
            @Valid @RequestBody StudentImportDtos.BatchRemoveRequest request) {
        return Result.ok(importService.batchRemove(offeringId, request));
    }

    /** 中文文件名按 RFC 5987 编码，避免部分浏览器把文件名显示成乱码。 */
    private static ResponseEntity<byte[]> download(byte[] body, String contentType, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .body(body);
    }
}
