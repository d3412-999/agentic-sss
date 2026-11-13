package org.spring_web.spring_boot_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 无状态 JWT 配置（lambda DSL，不使用已废弃的 WebSecurityConfigurerAdapter）。
 *
 * <p>授权边界按 URL 前缀粗分，Service 层再做数据级越权校验：
 * 教师只能操作 {@code offering.teacherId == 当前用户}，学生只能操作自己的 {@code selection}。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, cause) -> writeError(res, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, cause) -> writeError(res, ErrorCode.FORBIDDEN)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/v1/health",
                                // 纯静态字典（难度项、上传限制），不含任何用户数据；
                                // 放行后教师端/学生端页面首屏取字典不依赖 token 有效性，
                                // 避免 token 过期时难度筛选条静默回退到前端兜底值。
                                "/api/v1/meta/**",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/actuator/health",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/api/v1/teacher/**").hasRole("TEACHER")
                        .requestMatchers("/api/v1/student/**").hasRole("STUDENT")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 通用文件上传只服务题目附件，教师专属；下载仍对两种角色开放（学生要下题目附件）
                        .requestMatchers(HttpMethod.POST, "/api/v1/files", "/api/v1/files/batch").hasRole("TEACHER")
                        // 题目写操作挂在 /api/v1/topics/** 而非 /teacher/**，这里按方法再收紧一层。
                        // 注意 GET /topics/{id} 不能整段锁 TEACHER：已选该题的学生也要能看详情，
                        // 该接口由 TopicService.detail() 做「教师本人 or 已选学生」的数据级判断。
                        .requestMatchers(HttpMethod.PUT, "/api/v1/topics/**").hasRole("TEACHER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/topics/**").hasRole("TEACHER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/topics/**").hasRole("TEACHER")
                        // 管理员平台只服务「教师账号管理」：除自身认证接口外，
                        // 教师端 / 学生端的所有业务接口一律对 ADMIN 关闭。
                        .requestMatchers("/api/v1/auth/me", "/api/v1/auth/logout",
                                "/api/v1/auth/password").authenticated()
                        .requestMatchers("/api/v1/**").hasAnyRole("TEACHER", "STUDENT")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 401 / 403 也走统一响应包，前端只需处理一套结构。 */
    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Result.fail(errorCode));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
