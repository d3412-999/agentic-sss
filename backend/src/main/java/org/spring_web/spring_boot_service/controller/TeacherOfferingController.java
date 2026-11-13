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
import org.spring_web.spring_boot_service.service.SelectionService;
import org.spring_web.spring_boot_service.service.SubmissionService;
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
    private final SelectionService selectionService;
    private final SubmissionService submissionService;

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

    @Operation(summary = "单个添加学生（次要入口）：账号不存在则建号，已存在只建班级关联")
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

    @Operation(summary = "重置学生密码为统一的默认密码（前端不展示密码）")
    @PostMapping("/offerings/{offeringId}/students/{studentId}/reset-password")
    public Result<Void> resetPassword(
            @PathVariable String offeringId, @PathVariable String studentId) {
        studentService.resetPassword(offeringId, studentId);
        return Result.ok();
    }

    /**
     * 教师端：某个学生在本开课下的选题。
     *
     * <p>只读。「提交评审」要先拿到 selectionId 才能查提交物，而学生的选题列表接口
     * 只返回当前登录人自己的。</p>
     */
    @Operation(summary = "教师查看某学生的选题（只读，供提交评审页定位）")
    @GetMapping("/offerings/{offeringId}/students/{studentId}/selections")
    public Result<java.util.List<org.spring_web.spring_boot_service.dto.SelectionDtos.SelectionVO>>
            studentSelections(@PathVariable String offeringId, @PathVariable String studentId) {
        return Result.ok(selectionService.teacherByStudent(offeringId, studentId));
    }

    /**
     * 教师端：某个选题下的提交物列表（只读）。
     *
     * <p>为什么不能直接复用 {@code /api/v1/student/selections/{id}/submissions}：
     * 那条路径在 SecurityConfig 里被限定为 ROLE_STUDENT，教师调用会在进入 Service
     * 之前就被 403 拦掉（不是业务权限判断的问题，是路径级角色规则）。而
     * 「提交评审」页需要先列出提交物、再逐个查详情，所以这里补一条教师侧的只读入口。</p>
     *
     * <p>权限：本方法本身在 /teacher 前缀下（已限 TEACHER），Service 里的
     * list() 还会用 ownedOffering 再校验一次该开课是否属于当前教师，
     * 因此不会出现「任意教师看任意学生材料」的越权。</p>
     */
    @Operation(summary = "教师查看某选题的提交物列表（只读）")
    @GetMapping("/selections/{selectionId}/submissions")
    public Result<PageResult<org.spring_web.spring_boot_service.dto.CommonDtos.SubmissionBriefVO>>
            selectionSubmissions(@PathVariable String selectionId) {
        return Result.ok(submissionService.list(selectionId));
    }
}
