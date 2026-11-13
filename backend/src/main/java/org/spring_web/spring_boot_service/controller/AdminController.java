package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.AdminDtos;
import org.spring_web.spring_boot_service.service.AdminService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员端：教师账号与权限。
 *
 * <p>整个管理员平台只提供这一组接口；{@code SecurityConfig} 把 {@code /api/v1/admin/**}
 * 限定为 ADMIN 角色，同时禁止 ADMIN 访问教师端 / 学生端接口。</p>
 */
@Tag(name = "管理员 - 教师账号与权限")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "教师账号列表（支持工号 / 姓名 / 院系 / 手机号 / 邮箱搜索）")
    @GetMapping("/teachers")
    public Result<PageResult<AdminDtos.TeacherVO>> teachers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(adminService.list(keyword, status, page, size));
    }

    @Operation(summary = "教师权限目录（角色制：TEACHER 即拥有整包权限）")
    @GetMapping("/teacher-permissions")
    public Result<List<AdminDtos.PermissionVO>> permissions() {
        return Result.ok(adminService.permissions());
    }

    @Operation(summary = "新增单个教师账号（统一初始密码）")
    @PostMapping("/teachers")
    public Result<AdminDtos.TeacherVO> create(
            @RequestBody AdminDtos.TeacherCreateRequest request) {
        return Result.ok(adminService.create(request));
    }

    @Operation(summary = "批量新增教师账号（逐行返回成功 / 失败原因）")
    @PostMapping("/teachers/batch")
    public Result<AdminDtos.BatchCreateResult> batchCreate(
            @RequestBody AdminDtos.BatchCreateRequest request) {
        return Result.ok(adminService.batchCreate(request));
    }

    @Operation(summary = "编辑教师资料（工号不可修改）")
    @PatchMapping("/teachers/{teacherId}")
    public Result<AdminDtos.TeacherVO> update(
            @PathVariable String teacherId,
            @RequestBody AdminDtos.TeacherUpdateRequest request) {
        return Result.ok(adminService.update(teacherId, request));
    }

    @Operation(summary = "启用 / 停用教师账号")
    @PatchMapping("/teachers/{teacherId}/status")
    public Result<AdminDtos.TeacherVO> updateStatus(
            @PathVariable String teacherId,
            @RequestBody AdminDtos.TeacherStatusRequest request) {
        return Result.ok(adminService.updateStatus(teacherId, request.status()));
    }

    @Operation(summary = "重置教师密码为统一初始密码")
    @PostMapping("/teachers/{teacherId}/reset-password")
    public Result<AdminDtos.ResetPasswordVO> resetPassword(@PathVariable String teacherId) {
        return Result.ok(adminService.resetPassword(teacherId));
    }

    @Operation(summary = "删除教师账号（名下有未归档教学班时拒绝）")
    @DeleteMapping("/teachers/{teacherId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String teacherId) {
        adminService.delete(teacherId);
    }
}
