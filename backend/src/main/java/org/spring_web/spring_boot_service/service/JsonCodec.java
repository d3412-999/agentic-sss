package org.spring_web.spring_boot_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数据库 JSON 列的读写统一入口。
 *
 * <p>约定：读失败一律降级为空值而不是抛异常——历史脏数据不应让整个接口 500；
 * 写失败才是真故障，必须暴露出来。</p>
 */
@Component
@RequiredArgsConstructor
public class JsonCodec {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "数据序列化失败");
        }
    }

    public List<String> stringList(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return mapper.readValue(raw, STRING_LIST);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    public Map<String, Object> map(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, MAP);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    /** 把 JSON 字符串还原成任意对象，供 async_task.result / parseResult 对外输出。 */
    public Object object(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return mapper.readValue(raw, Object.class);
        } catch (JsonProcessingException ex) {
            return raw;
        }
    }
}
