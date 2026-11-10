package org.spring_web.spring_boot_service.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 调用 Python AI 服务的内部契约客户端。
 *
 * <p>使用 Spring 6.1 的 {@link RestClient}（流式 API，取代已进入维护模式的 RestTemplate），
 * 底层用 JDK 自带的 HttpClient，连接/读取超时可配。所有请求自动带上
 * {@code X-Internal-Token}——AI 服务不对前端开放，只认后端的「工牌」。</p>
 */
@Component
public class AiServiceClient {

    private static final String PREFIX = "/internal/ai";

    private final RestClient restClient;

    public AiServiceClient(SssProperties properties, RestClient.Builder builder) {
        SssProperties.Ai ai = properties.getAi();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(ai.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(ai.getReadTimeout());
        this.restClient = builder
                .baseUrl(ai.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Token", ai.getInternalToken())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** 对应 API 文档 21.1：根据题目、源码、报告生成答辩题。 */
    public Map<String, Object> generateDefenseQuestions(Map<String, Object> payload) {
        return post("/defense/questions", payload);
    }

    /** 21.2：根据转写稿与音频评分。 */
    public Map<String, Object> evaluateDefense(Map<String, Object> payload) {
        return post("/defense/evaluate", payload);
    }

    /** 21.3：课程设计报告评审。 */
    public Map<String, Object> evaluateReport(Map<String, Object> payload) {
        return post("/report/evaluate", payload);
    }

    /** 21.4：源码语义评审。 */
    public Map<String, Object> reviewCode(Map<String, Object> payload) {
        return post("/code/review", payload);
    }

    /** 21.5：报告-源码一致性检查（LightRAG）。 */
    public Map<String, Object> checkConsistency(Map<String, Object> payload) {
        return post("/consistency", payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> payload) {
        try {
            Object body = restClient.post()
                    .uri(PREFIX + path)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            return body instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (RestClientException | IllegalStateException ex) {
            // AI 服务未启动 / 返回 501 / 超时，统一转成 50301，由上层决定降级策略
            throw new BizException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用：" + ex.getMessage());
        }
    }
}
