package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.spring_web.spring_boot_service.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查。 */
@Tag(name = "健康检查")
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final String applicationName;

    public HealthController(@Value("${spring.application.name:sss-backend}") String applicationName) {
        this.applicationName = applicationName;
    }

    @Operation(summary = "服务健康状态")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("app", applicationName);
        data.put("time", OffsetDateTime.now().toString());
        return Result.ok(data);
    }
}
