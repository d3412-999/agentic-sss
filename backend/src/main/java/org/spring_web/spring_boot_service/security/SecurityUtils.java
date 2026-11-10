package org.spring_web.spring_boot_service.security;

import java.util.Optional;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Service 层获取当前登录主体的唯一入口，避免各处直接操作 SecurityContextHolder。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<LoginUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public static LoginUser required() {
        return current().orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }

    public static Long userId() {
        return required().id();
    }

    public static String role() {
        return required().role();
    }

    /** 数据级越权校验：教师只能操作自己的开课。 */
    public static void assertOwner(Long ownerId, String what) {
        BizAssert.forbidden(ownerId != null && ownerId.equals(userId()), "无权访问该" + what);
    }
}
