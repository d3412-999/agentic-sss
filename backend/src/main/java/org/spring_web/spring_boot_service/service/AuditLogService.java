package org.spring_web.spring_boot_service.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.entity.AuditLog;
import org.spring_web.spring_boot_service.mapper.AuditLogMapper;
import org.spring_web.spring_boot_service.security.ClientIpResolver;
import org.spring_web.spring_boot_service.security.LoginUser;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 追加写审计日志：改分、发布、重置答辩等敏感动作必须留痕。
 *
 * <p>before/after 是完整快照，reason 是业务强制填写的依据；表结构本身不提供 UPDATE，
 * 这里也刻意不暴露更新方法，保证审计不可篡改。</p>
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final JsonCodec json;
    private final ClientIpResolver clientIpResolver;

    public void record(String action, String targetType, Long targetId,
                       Object before, Object after, String reason) {
        AuditLog log = new AuditLog();
        LoginUser actor = SecurityUtils.current().orElse(null);
        log.setActorId(actor == null ? null : actor.id());
        log.setActorRole(actor == null ? "SYSTEM" : actor.role());
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setBeforeJson(json.write(before));
        log.setAfterJson(json.write(after));
        log.setReason(reason);
        HttpServletRequest request = currentRequest();
        log.setIp(request == null ? null : clientIpResolver.resolve(request));
        log.setUserAgent(request == null ? null : request.getHeader("User-Agent"));
        log.setCreatedAt(Times.now());
        auditLogMapper.insert(log);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
