package org.spring_web.spring_boot_service.security;

import java.io.Serializable;
import java.util.List;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 登录主体：不可变快照，放进 SecurityContext，避免 Service 层反复查库。
 *
 * <p>用 Java 17 record 而非可变 bean：认证信息一旦建立就不应被业务代码改掉。</p>
 */
public record LoginUser(
        Long id,
        String username,
        String name,
        String role,
        String studentNo,
        String className,
        String email) implements Serializable {

    public static LoginUser from(SysUser user) {
        return new LoginUser(user.getId(), user.getUsername(), user.getName(), user.getRole(),
                user.getStudentNo(), user.getClassName(), user.getEmail());
    }

    /** hasRole("TEACHER") 要求 authority 为 ROLE_TEACHER。 */
    public List<SimpleGrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    public boolean isTeacher() {
        return "TEACHER".equals(role) || "ADMIN".equals(role);
    }

    public boolean isStudent() {
        return "STUDENT".equals(role);
    }
}
