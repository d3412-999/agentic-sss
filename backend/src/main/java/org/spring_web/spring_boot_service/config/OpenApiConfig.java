package org.spring_web.spring_boot_service.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI 配置，声明 Bearer JWT 便于直接调试受保护接口。
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME = "bearerAuth";

    @Bean
    public OpenAPI sssOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("课程设计智能答辩与评分系统 API")
                        .version("v0.2.0")
                        .description("统一响应 {code,message,data,traceId}；除登录/刷新外均需 Bearer Token。"))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME))
                .components(new Components().addSecuritySchemes(SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("POST /api/v1/auth/login 返回的 accessToken")));
    }
}
