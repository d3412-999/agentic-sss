package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.Idempotent;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.dto.GradeDtos;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.spring_web.spring_boot_service.service.GradeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成绩：教师侧查询/调整/发布/重算/导出，学生侧只读已发布成绩。
 *
 * <p>异步接口分两步：先受理返回 202 + taskId，再由 Controller 经代理调用 @Async 方法。
 * 刻意不在 GradeService 内部自调用 @Async——自调用不经过 Spring 代理，会退化成同步阻塞。</p>
 */
@Tag(name = "成绩管理")
@RestController
@RequiredArgsConstructor
public class GradeController {

    private final GradeService gradeService;

    @GetMapping("/api/v1/teacher/offerings/{offeringId}/grades")
    public Result<Map<String, Object>> grades(
            @PathVariable String offeringId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size) {
        PageResult<GradeDtos.GradeListItemVO> result =
                gradeService.teacherGrades(offeringId, keyword, status, page, size);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", result.getList());
        data.put("total", result.getTotal());
        data.put("page", result.getPage());
        data.put("size", result.getSize());
        data.put("pages", result.getPages());
        data.put("summary", gradeService.summary(offeringId));
        return Result.ok(data);
    }

    @GetMapping("/api/v1/teacher/grades/{gradeId}")
    public Result<GradeDtos.GradeDetailVO> gradeDetail(@PathVariable String gradeId) {
        return Result.ok(gradeService.detail(gradeId));
    }

    @Operation(summary = "调整成绩，reason 必填并写入审计日志，version 做乐观锁")
    @PatchMapping("/api/v1/teacher/grades/{gradeId}")
    public Result<GradeDtos.GradeDetailVO> adjust(
            @PathVariable String gradeId,
            @Valid @RequestBody GradeDtos.GradeAdjustRequest request) {
        return Result.ok(gradeService.adjust(gradeId, request));
    }

    @PostMapping("/api/v1/teacher/grades/{gradeId}/unlock")
    public Result<Void> unlock(@PathVariable String gradeId) {
        gradeService.unlock(gradeId);
        return Result.ok();
    }

    @PostMapping("/api/v1/teacher/offerings/{offeringId}/grades/recalculate")
    public ResponseEntity<Result<CommonDtos.TaskAccepted>> recalculate(
            @PathVariable String offeringId,
            @RequestBody(required = false) GradeDtos.RecalcRequest request) {
        CommonDtos.TaskAccepted accepted = gradeService.recalculate(offeringId, request);
        gradeService.recalculateAsync(BizAssert.id(accepted.taskId(), "taskId"), offeringId, request);
        return ResponseEntity.accepted().body(Result.ok(accepted));
    }

    @Operation(summary = "发布成绩，支持 Idempotency-Key")
    @Idempotent
    @PostMapping("/api/v1/teacher/offerings/{offeringId}/grades/publish")
    public Result<GradeDtos.GradePublishResult> publish(
            @PathVariable String offeringId,
            @Valid @RequestBody GradeDtos.GradePublishRequest request) {
        return Result.ok(gradeService.publish(offeringId, request));
    }

    @PostMapping("/api/v1/teacher/offerings/{offeringId}/grades/unpublish")
    public Result<Map<String, Object>> unpublish(
            @PathVariable String offeringId,
            @RequestBody(required = false) GradeDtos.UnpublishRequest request) {
        return Result.ok(Map.of("updated", gradeService.unpublish(offeringId, request)));
    }

    @PostMapping("/api/v1/teacher/offerings/{offeringId}/grades/export")
    public ResponseEntity<Result<CommonDtos.TaskAccepted>> export(
            @PathVariable String offeringId,
            @RequestBody(required = false) GradeDtos.ExportRequest request) {
        CommonDtos.TaskAccepted accepted = gradeService.export(offeringId, request);
        gradeService.exportAsync(BizAssert.id(accepted.taskId(), "taskId"),
                BizAssert.id(offeringId, "offeringId"), SecurityUtils.userId());
        return ResponseEntity.accepted().body(Result.ok(accepted));
    }

    @Operation(summary = "学生只能看到已发布的成绩")
    @GetMapping("/api/v1/student/grades")
    public Result<PageResult<GradeDtos.StudentGradeVO>> studentGrades(
            @RequestParam(required = false) String offeringId) {
        return Result.ok(gradeService.studentGrades(offeringId));
    }
}
