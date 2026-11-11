package org.spring_web.spring_boot_service.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 系统自有配置项，全部带安全默认值，开箱即用。
 *
 * <p>仓库的 {@code application.yaml} 已给出本地默认值；如需覆盖，在部署配置里加 {@code sss.*} 即可，例如：</p>
 * <pre>
 * sss:
 *   ai:
 *     base-url: http://127.0.0.1:8000
 *     internal-token: your-strong-token
 *   storage:
 *     local-dir: ./data/objects
 *   security:
 *     trusted-proxies: 127.0.0.1,10.0.0.0/8
 *   cors:
 *     allowed-origins: http://localhost:5173
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "sss")
public class SssProperties {

    private Ai ai = new Ai();
    private Storage storage = new Storage();
    private Limits limits = new Limits();
    private Cors cors = new Cors();
    private Security security = new Security();

    /** Python AI 服务（FastAPI）连接参数。 */
    @Data
    public static class Ai {
        private String baseUrl = "http://127.0.0.1:8000";
        private String internalToken = "change-me";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(90);
    }

    /** 本地文件存储参数（MinIO / OSS 预留，见 storage 字段）。 */
    @Data
    public static class Storage {
        private String localDir = "./data/objects";
        private long maxSourceBytes = 100L * 1024 * 1024;
        private long maxReportBytes = 50L * 1024 * 1024;
    }

    /** 限流与幂等参数。 */
    @Data
    public static class Limits {
        private int loginAttemptsPerMinute = 5;
        private int idempotencyTtlHours = 24;
        private int selectionBatchMax = 10;
        private int gradeAdjustReasonMinLength = 5;
    }

    /** 跨域白名单：默认只允许本地前端，生产必须显式配置。 */
    @Data
    public static class Cors {
        private String[] allowedOrigins = {"http://localhost:5173", "http://127.0.0.1:5173"};
    }

    /**
     * 安全相关：可信反向代理白名单。
     *
     * <p>默认空 = 永不信任 {@code X-Forwarded-For}，客户端无法伪造来源 IP；只有部署在
     * Nginx / 网关后面时才填写代理自身的 IP 或 IPv4 CIDR（如 {@code 10.0.0.0/8}）。</p>
     */
    @Data
    public static class Security {
        private List<String> trustedProxies = new ArrayList<>();
    }
}
