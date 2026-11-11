package org.spring_web.spring_boot_service.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
        // 必须显式使用 HTTP/1.1：JDK HttpClient 默认尝试 HTTP/2（明文下先发 h2c Upgrade），
        // 而 AI 服务使用的 uvicorn/h11 不支持 h2c 升级，会把请求体解析坏，
        // 表现为 400 "Invalid HTTP request received" 或 422 "body missing"。
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
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

    /**
     * 答辩语音转写（契约 A1）：multipart 上传整段音频，返回
     * {@code text / segments / durationMs / confidence / provider / model}。
     *
     * <p>音频由前端生成 WAV（16k mono PCM16）上传；失败统一转 50301，
     * 由 DefenseService 把作答标记为 FAILED，绝不伪造转写文本。</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> transcribeAudio(byte[] bytes, String filename,
                                               String contentType, String language) {
        BizAssert.param(bytes != null && bytes.length > 0, "音频内容不能为空");
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        String safeName = safeFilename(filename);
        ByteArrayResource audio = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return safeName;
            }
        };
        builder.part("file", audio).contentType(mediaType(contentType));
        if (StringUtils.hasText(language)) {
            builder.part("language", language);
        }
        try {
            Object body = restClient.post()
                    .uri(PREFIX + "/asr/transcribe")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(builder.build())
                    .retrieve()
                    .body(Map.class);
            return body instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (RestClientException | IllegalStateException ex) {
            throw new BizException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用：" + ex.getMessage());
        }
    }

    /**
     * 摄像头在场检测（契约 A3）：只判画面里有没有人脸，不做身份识别，也不落盘。
     *
     * <p>请求体 {@code {"imageBase64": "..."}}；失败统一转 50301，
     * 由 DefenseService 决定「不落库、前端降级提示」。</p>
     */
    public Map<String, Object> facePresence(String imageBase64) {
        BizAssert.param(StringUtils.hasText(imageBase64), "画面内容不能为空");
        return post("/face/presence", Map.of("imageBase64", imageBase64));
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

    /** 把优秀作品写入 AI 模板库（LightRAG 优先，未启用时写本地模板索引）。 */
    public Map<String, Object> indexTemplate(Map<String, Object> payload) {
        return post("/template/index", payload);
    }

    /** 检索模板库，供报告评审等场景做 RAG 参考。 */
    public Map<String, Object> searchTemplate(Map<String, Object> payload) {
        return post("/template/search", payload);
    }

    /** 从模板库删除文档。 */
    public void deleteTemplate(String docId) {
        delete("/template/" + docId);
    }

    /** 只保留文件名本身，去掉路径与换行/引号，避免拼进 multipart 头时破坏分隔符。 */
    private String safeFilename(String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace("\r", "").replace("\n", "").replace("\"", "");
        return name.isBlank() ? "answer.wav" : name;
    }

    /** Content-Type 非法时退回二进制流，不让一个脏头导致整个转写请求 500。 */
    private MediaType mediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
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

    private void delete(String path) {
        try {
            restClient.delete()
                    .uri(PREFIX + path)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException | IllegalStateException ex) {
            throw new BizException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用：" + ex.getMessage());
        }
    }
}
