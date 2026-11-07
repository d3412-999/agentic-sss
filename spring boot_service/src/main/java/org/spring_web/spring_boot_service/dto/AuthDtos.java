package org.spring_web.spring_boot_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 认证相关契约。对应 API 文档 3 章与前端 LoginView / stores/session.js。
 *
 * <p>全部用 Java 17 record：不可变、天然线程安全，配合 Jakarta Validation 做入参校验。</p>
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** role 只是前端入口提示，服务端一律以 sys_user 里的真实角色为准。 */
    public record LoginRequest(
            @NotBlank(message = "学号或工号不能为空") String username,
            @NotBlank(message = "密码不能为空") String password,
            String role) {
    }

    public record RefreshRequest(
            @NotBlank(message = "refreshToken 不能为空") String refreshToken) {
    }

    public record PasswordChangeRequest(
            @NotBlank(message = "原密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空")
            @Size(min = 8, max = 64, message = "新密码长度需为 8~64 位")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "新密码需同时包含字母和数字")
            String newPassword) {
    }

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            UserVO user) {
    }

    /** 前端 session 里保存 name / account；这里额外给出 id 与角色供前端做路由守卫。 */
    public record UserVO(
            String id,
            String username,
            String name,
            String role,
            String className,
            String avatarUrl,
            boolean mustChangePassword) {
    }

    public record MeVO(
            String id,
            String username,
            String name,
            String role,
            String className,
            String email,
            List<OfferingRef> offerings) {
    }

    public record OfferingRef(String offeringId, String courseName, String semester) {
    }
}
