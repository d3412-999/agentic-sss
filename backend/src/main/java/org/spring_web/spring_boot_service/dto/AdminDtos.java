package org.spring_web.spring_boot_service.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员端契约：教师账号的增删改查、批量导入与权限目录。
 *
 * <p>字段校验放在 Service 里做（而不是 Bean Validation 注解），
 * 因为批量导入需要「逐行返回失败原因」而不是让整批请求直接 400。</p>
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** 新增教师：工号即登录账号，其余为档案信息。 */
    public record TeacherCreateRequest(
            String username,
            String name,
            String department,
            String phone,
            String email) {
    }

    /** 编辑教师：工号是登录账号，创建后不可修改。 */
    public record TeacherUpdateRequest(
            String name,
            String department,
            String phone,
            String email) {
    }

    public record TeacherStatusRequest(String status) {
    }

    public record BatchCreateRequest(List<TeacherCreateRequest> teachers) {
    }

    public record TeacherVO(
            String id,
            String username,
            String name,
            String department,
            String phone,
            String email,
            String status,
            boolean mustChangePassword,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            List<String> permissions,
            /** 仅「新增教师」响应返回初始密码，列表 / 编辑时为 null。 */
            String initialPassword) {
    }

    /** 批量导入的单行结果：row 为提交时的行号（从 1 开始）。 */
    public record BatchRowResult(
            int row,
            String username,
            String name,
            boolean success,
            String message) {
    }

    public record BatchCreateResult(
            int total,
            int successCount,
            int failCount,
            List<BatchRowResult> items) {
    }

    public record ResetPasswordVO(String initialPassword, boolean mustChangePassword) {
    }

    /** 教师权限目录项：当前采用角色制，TEACHER 角色即拥有整包权限。 */
    public record PermissionVO(String code, String name, String description) {
    }
}
