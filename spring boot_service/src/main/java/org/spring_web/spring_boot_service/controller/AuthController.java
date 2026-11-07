package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.AuthDtos;
import org.spring_web.spring_boot_service.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证入口。/login 与 /refresh 匿名开放，其余需 Bearer Token。
 */
@Tag(name = "认证与当前用户")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER = "Bearer ";

    private final AuthService authService;

    @Operation(summary = "登录，返回 accessToken 与 refreshToken")
    @PostMapping("/login")
    public Result<AuthDtos.TokenResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @Operation(summary = "轮换刷新令牌，旧令牌立即失效")
    @PostMapping("/refresh")
    public Result<AuthDtos.TokenResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        return Result.ok(authService.refresh(request));
    }

    @Operation(summary = "登出，当前 accessToken 加入黑名单")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization == null || !authorization.startsWith(BEARER)
                ? null : authorization.substring(BEARER.length());
        authService.logout(token);
        return Result.ok();
    }

    @Operation(summary = "当前登录用户与所属开课")
    @GetMapping("/me")
    public Result<AuthDtos.MeVO> me() {
        return Result.ok(authService.me());
    }

    @Operation(summary = "修改密码，成功后所有已签发令牌失效")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody AuthDtos.PasswordChangeRequest request) {
        authService.changePassword(request);
        return Result.ok();
    }
}
