package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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

    @Operation(summary = "登录，返回 accessToken 与 refreshToken（失败按 用户名 + IP 限流）")
    @PostMapping("/login")
    public Result<AuthDtos.TokenResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                HttpServletRequest servletRequest) {
        return Result.ok(authService.login(request, clientIp(servletRequest)));
    }

    @Operation(summary = "轮换刷新令牌，旧令牌立即失效")
    @PostMapping("/refresh")
    public Result<AuthDtos.TokenResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        return Result.ok(authService.refresh(request));
    }

    @Operation(summary = "登出：accessToken 进黑名单，并作废随请求带来的 refreshToken")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestBody(required = false) AuthDtos.LogoutRequest body) {
        String token = authorization == null || !authorization.startsWith(BEARER)
                ? null : authorization.substring(BEARER.length());
        authService.logout(token, body == null ? null : body.refreshToken());
        return Result.ok();
    }

    /** 取真实客户端 IP：优先 X-Forwarded-For 的第一段（反代场景），否则用直连地址。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
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
