package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.AuthDtos;
import org.spring_web.spring_boot_service.entity.Course;
import org.spring_web.spring_boot_service.entity.Offering;
import org.spring_web.spring_boot_service.entity.OfferingStudent;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.mapper.CourseMapper;
import org.spring_web.spring_boot_service.mapper.OfferingMapper;
import org.spring_web.spring_boot_service.mapper.OfferingStudentMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.spring_web.spring_boot_service.security.JwtService;
import org.spring_web.spring_boot_service.security.LoginUser;
import org.spring_web.spring_boot_service.security.RefreshTokenStore;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 认证：登录限流、JWT 签发、刷新令牌一次性轮换、登出黑名单、改密码强制下线。
 *
 * <p>安全要点：</p>
 * <ol>
 *   <li>限流只计「失败」次数，正常用户不会被自己的成功登录锁住；</li>
 *   <li>账号不存在与密码错误返回同一个 40102，不泄露账号是否存在；</li>
 *   <li>role 入参被忽略，角色永远取自 DB；</li>
 *   <li>刷新令牌用 Redis Lua 原子消费，杜绝并发重放；</li>
 *   <li>改密码递增 tokenVersion，所有已签发令牌立即失效。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final OfferingMapper offeringMapper;
    private final OfferingStudentMapper offeringStudentMapper;
    private final CourseMapper courseMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore tokenStore;
    private final StringRedisTemplate redis;
    private final SssProperties properties;
    private final AuditLogService auditLog;

    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        String rateKey = "sss:login:fail:" + request.username();
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.username())
                .last("limit 1"));
        boolean credentialOk = user != null
                && passwordEncoder.matches(request.password(), user.getPasswordHash());
        if (!credentialOk) {
            checkRateLimit(rateKey);
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        redis.delete(rateKey);

        SysUser fresh = new SysUser();
        fresh.setId(user.getId());
        fresh.setLastLoginAt(Times.now());
        userMapper.updateById(fresh);
        return issue(user);
    }

    private void checkRateLimit(String rateKey) {
        Long attempts = redis.opsForValue().increment(rateKey);
        if (attempts != null && attempts == 1L) {
            redis.expire(rateKey, Duration.ofMinutes(1));
        }
        if (attempts != null && attempts > properties.getLimits().getLoginAttemptsPerMinute()) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后再试");
        }
    }

    private AuthDtos.TokenResponse issue(SysUser user) {
        LoginUser principal = LoginUser.from(user);
        long version = tokenStore.tokenVersion(user.getId());
        String jti = UUID.randomUUID().toString();
        String refreshToken = jwtService.issueRefresh(principal, version, jti);
        tokenStore.saveRefresh(jti, user.getId(),
                Duration.ofSeconds(jwtService.refreshTtlSeconds()));
        String accessToken = jwtService.issueAccess(principal, version);
        return new AuthDtos.TokenResponse(accessToken, refreshToken, "Bearer",
                jwtService.accessExpiresInSeconds(), toUserVo(user));
    }

    @Transactional(rollbackFor = Exception.class)
    public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtService.parse(request.refreshToken());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期");
        }
        if (!jwtService.isRefresh(claims)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期");
        }
        long uid;
        long version;
        try {
            uid = Long.parseLong(claims.get("uid", String.class));
            version = Long.parseLong(claims.get("ver", String.class));
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期");
        }

        // 先原子消费：即使后续校验失败，旧令牌也已作废，不能留给第二个请求重放。
        Optional<Long> owner = tokenStore.consumeRefresh(claims.getId());
        if (owner.isEmpty() || !owner.get().equals(uid)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌已被使用，请重新登录");
        }
        if (version != tokenStore.tokenVersion(uid)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录状态已变更，请重新登录");
        }
        SysUser user = BizAssert.found(userMapper.selectById(uid), "用户");
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        return issue(user);
    }

    public void logout(String bearerToken) {
        if (!StringUtils.hasText(bearerToken)) {
            return;
        }
        try {
            Claims claims = jwtService.parse(bearerToken);
            long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remaining > 0) {
                tokenStore.blacklist(claims.getId(), Duration.ofMillis(remaining));
            }
        } catch (JwtException | IllegalArgumentException ex) {
            // 登出是尽力而为，令牌本身无效也无需报错
        }
    }

    public AuthDtos.MeVO me() {
        LoginUser current = SecurityUtils.required();
        SysUser user = BizAssert.found(userMapper.selectById(current.id()), "用户");
        return new AuthDtos.MeVO(String.valueOf(user.getId()), user.getUsername(), user.getName(),
                user.getRole(), user.getClassName(), user.getEmail(), offeringsOf(user));
    }

    private List<AuthDtos.OfferingRef> offeringsOf(SysUser user) {
        List<Offering> offerings;
        if ("STUDENT".equals(user.getRole())) {
            List<OfferingStudent> relations = offeringStudentMapper.selectList(
                    new LambdaQueryWrapper<OfferingStudent>()
                            .eq(OfferingStudent::getStudentId, user.getId())
                            .eq(OfferingStudent::getStatus, "ACTIVE"));
            if (relations.isEmpty()) {
                return List.of();
            }
            offerings = offeringMapper.selectBatchIds(
                    relations.stream().map(OfferingStudent::getOfferingId).distinct().toList());
        } else {
            offerings = offeringMapper.selectList(new LambdaQueryWrapper<Offering>()
                    .eq(Offering::getTeacherId, user.getId())
                    .ne(Offering::getStatus, "ARCHIVED"));
        }
        if (offerings.isEmpty()) {
            return List.of();
        }
        Map<Long, String> courseNames = new HashMap<>();
        List<Long> courseIds = offerings.stream().map(Offering::getCourseId).distinct().toList();
        for (Course course : courseMapper.selectBatchIds(courseIds)) {
            courseNames.put(course.getId(), course.getName());
        }
        List<AuthDtos.OfferingRef> refs = new ArrayList<>();
        for (Offering offering : offerings) {
            refs.add(new AuthDtos.OfferingRef(String.valueOf(offering.getId()),
                    courseNames.getOrDefault(offering.getCourseId(), ""), offering.getSemester()));
        }
        return refs;
    }

    @Transactional(rollbackFor = Exception.class)
    public void changePassword(AuthDtos.PasswordChangeRequest request) {
        LoginUser current = SecurityUtils.required();
        SysUser stored = BizAssert.found(userMapper.selectById(current.id()), "用户");
        BizAssert.param(passwordEncoder.matches(request.oldPassword(), stored.getPasswordHash()),
                "原密码不正确");
        BizAssert.param(!passwordEncoder.matches(request.newPassword(), stored.getPasswordHash()),
                "新密码不能与原密码相同");

        SysUser patch = new SysUser();
        patch.setId(stored.getId());
        patch.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        patch.setMustChangePassword(0);
        userMapper.updateById(patch);

        tokenStore.bumpTokenVersion(stored.getId());
        auditLog.record("PASSWORD_CHANGE", "USER", stored.getId(), null, null, "用户自助修改密码");
    }

    private AuthDtos.UserVO toUserVo(SysUser user) {
        String avatarUrl = user.getAvatarFileId() == null
                ? null : "/api/v1/files/" + user.getAvatarFileId() + "/download";
        return new AuthDtos.UserVO(String.valueOf(user.getId()), user.getUsername(), user.getName(),
                user.getRole(), user.getClassName(), avatarUrl,
                Integer.valueOf(1).equals(user.getMustChangePassword()));
    }
}
