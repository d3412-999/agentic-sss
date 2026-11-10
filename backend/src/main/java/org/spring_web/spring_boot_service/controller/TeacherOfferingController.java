package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.Idempotent;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.OfferingDtos;
import org.spring_web.spring_boot_service.service.OfferingService;
import org.spring_web.spring_boot_service.service.OfferingStudentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师端：课程模板、开课生命周期、仪表盘、学生名单。
 *
 * <p>URL 前缀 /teacher 由 SecurityConfig 限定角色，Service 内再做 teacherId 归属校验。</p>
 */
@Tag(name = "教师 - 开课与学生名单")
@RestController
@RequestMapping("/api/v1/teacher")
@RequiredArgsConstructor
public class TeacherOfferingController {

    private final OfferingService offeringService;
    private final OfferingStudentService studentService;

    @Operation(summary = "课程模板列表")
    @GetMapping("/courses")
    public Result<PageResult<OfferingDtos.CourseVO>> courses(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(offeringService.courses(keyword, page, size));
    }

    @Operation(summary = "新建课程模板")
    @PostMapping("/courses")
    public Result<OfferingDtos.CourseVO> createCourse(
            @Valid @RequestBody OfferingDtos.CourseCreateRequest request) {
        return Result.ok(offeringService.createCourse(request));
    }

    @Operation(summary = "我的开课列表")
    @GetMapping("/offerings")
    public Result<PageResult<OfferingDtos.OfferingListItemVO>> offerings(
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(offeringService.teacherOfferings(semester, status, keyword, page, size));
    }

    @Operation(summary = "新建开课（草稿），支持 Idempotency-Key")
    @Idempotent
    @PostMapping("/offerings")
    public Result<OfferingDtos.OfferingVO> createOffering(
            @Valid @RequestBody OfferingDtos.OfferingCreateRequest request) {
        return Result.ok(offeringService.create(request));
    }

    @GetMapping("/offerings/{offeringId}")
    public Result<OfferingDtos.OfferingVO> offeringDetail(@PathVariable String offeringId) {
        return Result.ok(offeringService.detail(offeringId));
    }

    @Operation(summary = "修改开课，version 做乐观锁")
    @PatchMapping("/offerings/{offeringId}")
    public Result<OfferingDtos.OfferingVO> patchOffering(
            @PathVariable String offeringId,
            @Valid @RequestBody OfferingDtos.OfferingPatchRequest request) {
        return Result.ok(offeringService.patch(offeringId, request));
    }

    @Operation(summary = "发布开课，前置条件不满足返回 data.blockers")
    @PostMapping("/offerings/{offeringId}/publish")
    public Result<OfferingDtos.OfferingVO> publish(@PathVariable String offeringId) {
        return Result.ok(offeringService.publish(offeringId));
    }

    @PostMapping("/offerings/{offeringId}/close")
    public Result<OfferingDtos.OfferingVO> close(@PathVariable String offeringId) {
        return Result.ok(offeringService.close(offeringId));
    }

    @PostMapping("/offerings/{offeringId}/archive")
    public Result<OfferingDtos.OfferingVO> archive(@PathVariable String offeringId) {
        return Result.ok(offeringService.archive(offeringId));
    }

    @Operation(summary = "开课仪表盘统计")
    @GetMapping("/offerings/{offeringId}/dashboard")
    public Result<OfferingDtos.DashboardVO> dashboard(@PathVariable String offeringId) {
        return Result.ok(offeringService.dashboard(offeringId));
    }

    @GetMapping("/offerings/{offeringId}/students")
    public Result<PageResult<OfferingDtos.StudentVO>> students(
            @PathVariable String offeringId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(studentService.list(offeringId, keyword, status, page, size));
    }

    @Operation(summary = "添加学生，首次创建返回一次 initialPassword")
    @PostMapping("/offerings/{offeringId}/students")
    public Result<OfferingDtos.StudentVO> addStudent(
            @PathVariable String offeringId,
            @Valid @RequestBody OfferingDtos.StudentCreateRequest request) {
        return Result.ok(studentService.create(offeringId, request));
    }

    @PatchMapping("/offerings/{offeringId}/students/{studentId}")
    public Result<OfferingDtos.StudentVO> patchStudent(
            @PathVariable String offeringId, @PathVariable String studentId,
            @Valid @RequestBody OfferingDtos.StudentStatusPatch request) {
        return Result.ok(studentService.patchStatus(offeringId, studentId, request));
    }

    @DeleteMapping("/offerings/{offeringId}/students/{studentId}")
    public Result<Void> deleteStudent(
            @PathVariable String offeringId, @PathVariable String studentId) {
        studentService.delete(offeringId, studentId);
        return Result.ok();
    }

    @PostMapping("/offerings/{offeringId}/students/{studentId}/reset-password")
    public Result<OfferingDtos.ResetPasswordVO> resetPassword(
            @PathVariable String offeringId, @PathVariable String studentId) {
        return Result.ok(studentService.resetPassword(offeringId, studentId));
    }
}
