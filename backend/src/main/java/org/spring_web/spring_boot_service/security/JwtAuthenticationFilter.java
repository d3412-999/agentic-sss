package org.spring_web.spring_boot_service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每个请求解析 Bearer Token，并回查用户状态。
 *
 * <p>刻意回查 DB：教师把学生账号设为 DISABLED 后，该学生手里尚未过期的 Token
 * 必须立刻失效，否则「禁用」形同虚设。代价是每请求一次主键查询，可后续加缓存。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final RefreshTokenStore tokenStore;
    private final SysUserMapper sysUserMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER)) {
            authenticate(header.substring(BEARER.length()), request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.parse(token);
            if (!jwtService.isAccess(claims)) {
                return;
            }
            long uid = Long.parseLong(claims.get("uid", String.class));
            long version = Long.parseLong(claims.get("ver", String.class));

            if (tokenStore.isBlacklisted(claims.getId())
                    || version != tokenStore.tokenVersion(uid)) {
                return;
            }
            SysUser user = sysUserMapper.selectById(uid);
            if (user == null || !"ACTIVE".equals(user.getStatus())) {
                return;
            }

            LoginUser principal = LoginUser.from(user);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
            log.debug("Rejected JWT: {}", ex.getMessage());
        }
    }
}
