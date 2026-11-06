package org.spring_web.spring_boot_service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI 配置。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sssOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("课程设计智能答辩与评分系统 API")
                .version("v0.1.0")
                .description("骨架阶段仅提供健康检查接口，业务接口按 API 文档逐步补齐。"));
    }
}
