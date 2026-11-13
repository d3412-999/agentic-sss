package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.DefenseDtos;
import org.spring_web.spring_boot_service.entity.DefenseSession;
import org.spring_web.spring_boot_service.entity.DefenseViolation;
import org.spring_web.spring_boot_service.entity.Offering;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.mapper.DefenseSessionMapper;
import org.spring_web.spring_boot_service.mapper.DefenseViolationMapper;
import org.spring_web.spring_boot_service.mapper.OfferingMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.spring_web.spring_boot_service.security.ClientIpResolver;
import org.spring_web.spring_boot_service.security.LoginUser;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 答辩违规事件：上报 → 阈值终止 → 教师裁决（恢复 / 维持终止）。
 *
 * <h3>为什么要设计成「终止 + 可恢复」而不是「终止即定论」</h3>
 * <p>前端检测（切屏、DevTools、粘贴）本质是<b>弱证据</b>：系统弹窗、输入法、投屏、
 * 多显示器都可能让一次切屏变成误判。所以这里只把「累计 N 次」当成<b>暂停键</b>，
 * 真正的裁决权留给教师：</p>
 * <ul>
 *   <li>{@code resume}：判定为误报，会话回到 IN_PROGRESS，学生可继续答题；
 *       同时写一条 RESUME 事件，计数从这一刻重新开始（不会「一恢复就立刻又被终止」）；</li>
 *   <li>{@code uphold}：确认违规，会话转为 COMPLETED，成绩留空，
 *       教师随后可正常走「AI 评分」或记 0 分，评分链路完全不改。</li>
 * </ul>
 *
 * <p>等级（MAJOR / MINOR）由服务端按类型判定，前端只能选类型：
 * 否则前端被改一行代码就能把切屏标成 COPY_BLOCK 绕过阈值。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefenseViolationService {

    /** 允许前端上报的类型；不在这里的一律按参数错误拒绝（防止刷脏数据）。 */
    private static final List<String> REPORTABLE_TYPES = List.of(
            "TAB_SWITCH", "DEVTOOLS", "PASTE_PATTERN", "COPY_BLOCK", "SHORTCUT_BLOCK");
    /** 教师裁决事件类型（不是学生上报，仅用于时间线）。 */
    private static final String TYPE_RESUME = "RESUME";
    private static final String TYPE_UPHOLD = "UPHOLD";
    private static final String SEVERITY_MAJOR = "MAJOR";
    private static final String SEVERITY_MINOR = "MINOR";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_VIOLATED = "VIOLATED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    /** 教师端每个会话最多回传多少条证据，够看且不至于把响应撑大。 */
    private static final int MAX_ITEMS = 20;

    private final DefenseSessionMapper sessionMapper;
    private final DefenseViolationMapper violationMapper;
    private final OfferingMapper offeringMapper;
    private final SysUserMapper sysUserMapper;
    private final OfferingService offeringService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final JsonCodec json;
    private final ClientIpResolver clientIpResolver;
    private final SssProperties properties;

    // ---------------------------------------------------------------- 学生：上报

    /**
     * 学生上报一次违规。
     *
     * <p>答辩已结束/已被终止时不再落库，只回 {@code accepted=false}：
     * 前端在收到终止后会跳离页面，多标签页的重复上报没有证据价值，
     * 落库只会让教师端的时间线里堆一堆重复事件。</p>
     */
    public DefenseDtos.ViolationReportVO report(String sessionId, DefenseDtos.ViolationReportRequest request) {
        LoginUser current = SecurityUtils.required();
        BizAssert.forbidden(current.isStudent(), "只有学生端会上报答辩违规");
        DefenseSession session = loadSession(sessionId);
        BizAssert.forbidden(current.id().equals(session.getStudentId()), "只能上报自己答辩会话的违规");

        String type = normalizeType(request == null ? null : request.type());
        BizAssert.param(type != null, "违规类型不能为空");
        BizAssert.param(REPORTABLE_TYPES.contains(type), "不支持的违规类型："
                + (request == null ? "" : request.type()));

        SssProperties.Violation config = properties.getDefense().getViolation();
        int threshold = Math.max(1, config.getThreshold());
        String severity = isCounted(config, type) ? SEVERITY_MAJOR : SEVERITY_MINOR;
        int count = majorCount(session.getId());

        if (!STATUS_IN_PROGRESS.equals(session.getStatus())) {
            return new DefenseDtos.ViolationReportVO(count, threshold, false, false,
                    "答辩当前状态不接受违规上报，本次未记录");
        }

        insertEvent(session, type, severity, request.detail());
        if (SEVERITY_MAJOR.equals(severity)) {
            count = majorCount(session.getId());
        }
        if (count < threshold) {
            return new DefenseDtos.ViolationReportVO(count, threshold, true, false,
                    "第 " + count + " 次违规已记录，累计 " + threshold + " 次将终止答辩");
        }
        terminate(session, type);
        return new DefenseDtos.ViolationReportVO(count, threshold, true, true,
                "累计违规达到 " + threshold + " 次，答辩已被强制终止");
    }

    /** 累计到阈值后把会话置为 VIOLATED，并通知教师。 */
    private void terminate(DefenseSession session, String type) {
        LocalDateTime now = Times.now();
        session.setStatus(STATUS_VIOLATED);
        session.setFinishedAt(now);
        session.setDurationMs(session.getStartedAt() == null ? 0L
                : Duration.between(session.getStartedAt(), now).toMillis());
        session.setUpdatedAt(now);
        if (sessionMapper.updateById(session) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "答辩状态已变更，请刷新后重试");
        }

        Map<String, Object> before = Map.of("status", STATUS_IN_PROGRESS);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", STATUS_VIOLATED);
        after.put("trigger", type);
        auditLogService.record("DEFENSE_VIOLATION_TERMINATE", "DEFENSE_SESSION", session.getId(),
                before, after, "前端累计违规达到阈值，系统强制终止");

        Offering offering = offeringMapper.selectById(session.getOfferingId());
        SysUser student = sysUserMapper.selectById(session.getStudentId());
        if (offering != null && offering.getTeacherId() != null) {
            String who = student == null || student.getName() == null ? "学生" : student.getName();
            notificationService.push(offering.getTeacherId(), "DEFENSE_VIOLATION", "答辩违规告警",
                    who + " 因累计违规已被系统暂停答辩，请在答辩工作台确认是终止评分还是恢复答辩。",
                    "/defense/" + session.getId());
        }
        notificationService.push(session.getStudentId(), "DEFENSE_VIOLATION", "答辩已被暂停",
                "系统检测到多次离开答题页/打开开发者工具等行为，答辩已暂停。等待监考教师确认。",
                "/defense/" + session.getId());
    }

    // ---------------------------------------------------------------- 教师：裁决

    /**
     * 恢复答辩：VIOLATED → IN_PROGRESS。
     *
     * <p>只清结束时间，不动 {@code started_at}：答辩时长会包含这段中断时间，
     * 这是保守口径（宁可高估用时，也不让学生通过「触发违规再恢复」把时间洗回来）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public DefenseDtos.ViolationDecisionVO resume(String sessionId) {
        DefenseSession session = loadSession(sessionId);
        ownedOffering(session);
        BizAssert.rule(STATUS_VIOLATED.equals(session.getStatus()),
                "只有因违规暂停的答辩可以恢复");

        LocalDateTime now = Times.now();
        session.setStatus(STATUS_IN_PROGRESS);
        session.setFinishedAt(null);
        session.setDurationMs(null);
        session.setUpdatedAt(now);
        if (sessionMapper.updateById(session) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "答辩状态已变更，请刷新后重试");
        }

        insertEvent(session, TYPE_RESUME, SEVERITY_MINOR,
                Map.of("by", "teacher", "reason", "教师确认为误报或给予补答机会"));
        auditLogService.record("DEFENSE_VIOLATION_RESUME", "DEFENSE_SESSION", session.getId(),
                Map.of("status", STATUS_VIOLATED), Map.of("status", STATUS_IN_PROGRESS),
                "教师恢复答辩（违规计数重新开始）");
        notificationService.push(session.getStudentId(), "DEFENSE_RESUMED", "答辩已恢复",
                "教师已恢复你的答辩，请立即回到答辩页继续作答（违规次数已重新计算）。",
                "/defense/" + session.getId());
        return new DefenseDtos.ViolationDecisionVO(String.valueOf(session.getId()), STATUS_IN_PROGRESS);
    }

    /**
     * 维持终止：VIOLATED → COMPLETED。
     *
     * <p>转成 COMPLETED 而不是另立状态，是为了让「AI 评分 / 改分」那套流程一行都不用改：
     * 教师确认违规后照常评分，想给 0 分也走现有的改分审计。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public DefenseDtos.ViolationDecisionVO uphold(String sessionId) {
        DefenseSession session = loadSession(sessionId);
        ownedOffering(session);
        BizAssert.rule(STATUS_VIOLATED.equals(session.getStatus()),
                "只有因违规暂停的答辩可以确认终止");

        LocalDateTime now = Times.now();
        LocalDateTime finished = session.getFinishedAt() == null ? now : session.getFinishedAt();
        session.setStatus(STATUS_COMPLETED);
        session.setFinishedAt(finished);
        if (session.getDurationMs() == null && session.getStartedAt() != null) {
            session.setDurationMs(Duration.between(session.getStartedAt(), finished).toMillis());
        }
        session.setUpdatedAt(now);
        if (sessionMapper.updateById(session) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "答辩状态已变更，请刷新后重试");
        }

        insertEvent(session, TYPE_UPHOLD, SEVERITY_MINOR,
                Map.of("by", "teacher", "reason", "教师确认违规并结束答辩，成绩待定"));
        auditLogService.record("DEFENSE_VIOLATION_UPHOLD", "DEFENSE_SESSION", session.getId(),
                Map.of("status", STATUS_VIOLATED), Map.of("status", STATUS_COMPLETED),
                "教师确认违规终止，成绩留空待评分");
        notificationService.push(session.getStudentId(), "DEFENSE_TERMINATED", "答辩已终止",
                "教师已确认违规并终止本次答辩，成绩将由教师后续评定。", "/defense/" + session.getId());
        return new DefenseDtos.ViolationDecisionVO(String.valueOf(session.getId()), STATUS_COMPLETED);
    }

    // ---------------------------------------------------------------- 教师：查询

    /**
     * 某开课下的违规汇总（只返回有违规记录的会话）。
     *
     * <p>教师端用它渲染红色角标与详情弹窗；没有 WebSocket，靠前端 30s 轮询刷新，
     * 与现有「未读通知 60s 轮询」是同一套机制，不新建通道。</p>
     */
    public List<DefenseDtos.SessionViolationVO> violations(String offeringId) {
        Offering offering = offeringService.ownedOffering(offeringId);
        List<DefenseSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<DefenseSession>()
                .eq(DefenseSession::getOfferingId, offering.getId())
                .orderByDesc(DefenseSession::getId));
        if (sessions.isEmpty()) {
            return List.of();
        }
        Map<Long, DefenseSession> byId = sessions.stream()
                .collect(Collectors.toMap(DefenseSession::getId, Function.identity()));
        List<DefenseViolation> rows = violationMapper.selectList(new LambdaQueryWrapper<DefenseViolation>()
                .in(DefenseViolation::getDefenseId, byId.keySet())
                .orderByDesc(DefenseViolation::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<DefenseViolation>> grouped = new LinkedHashMap<>();
        for (DefenseViolation row : rows) {
            grouped.computeIfAbsent(row.getDefenseId(), key -> new ArrayList<>()).add(row);
        }
        Map<Long, SysUser> students = studentMap(byId.values().stream()
                .map(DefenseSession::getStudentId).filter(Objects::nonNull).distinct().toList());

        int threshold = Math.max(1, properties.getDefense().getViolation().getThreshold());
        List<DefenseDtos.SessionViolationVO> result = new ArrayList<>();
        for (Map.Entry<Long, List<DefenseViolation>> entry : grouped.entrySet()) {
            DefenseSession session = byId.get(entry.getKey());
            if (session == null) {
                continue;
            }
            List<DefenseViolation> items = entry.getValue();
            SysUser student = students.get(session.getStudentId());
            int major = (int) items.stream()
                    .filter(item -> SEVERITY_MAJOR.equals(item.getSeverity())).count();
            int minor = items.size() - major;
            result.add(new DefenseDtos.SessionViolationVO(
                    String.valueOf(session.getId()),
                    String.valueOf(session.getStudentId()),
                    student == null || student.getStudentNo() == null ? "" : student.getStudentNo(),
                    student == null || student.getName() == null ? "" : student.getName(),
                    student == null || student.getClassName() == null ? "" : student.getClassName(),
                    session.getStatus(),
                    major,
                    minor,
                    majorSinceResume(items),
                    threshold,
                    Times.offset(items.get(0).getOccurredAt()),
                    items.stream().limit(MAX_ITEMS).map(this::toItemVo).toList()));
        }
        // 有 VIOLATED 的排最前，再按最近事件时间倒序：教师打开就看见最该处理的
        result.sort((left, right) -> {
            boolean leftViolated = STATUS_VIOLATED.equals(left.sessionStatus());
            boolean rightViolated = STATUS_VIOLATED.equals(right.sessionStatus());
            if (leftViolated != rightViolated) {
                return leftViolated ? -1 : 1;
            }
            if (left.latestAt() == null || right.latestAt() == null) {
                return 0;
            }
            return right.latestAt().compareTo(left.latestAt());
        });
        return result;
    }

    // ---------------------------------------------------------------- 内部

    private DefenseSession loadSession(String sessionId) {
        return BizAssert.found(
                sessionMapper.selectById(BizAssert.id(sessionId, "sessionId")), "答辩会话");
    }

    /** 数据级归属：只有开课所有者能看违规详情、能裁决。 */
    private void ownedOffering(DefenseSession session) {
        offeringService.ownedOffering(String.valueOf(session.getOfferingId()));
    }

    private String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isCounted(SssProperties.Violation config, String type) {
        List<String> counted = config.getCountedTypes();
        return counted != null && counted.contains(type);
    }

    /** 当前生效的 MAJOR 次数：只算「最后一次教师恢复」之后的事件。 */
    private int majorCount(Long defenseId) {
        Long sinceId = lastResumeId(defenseId);
        LambdaQueryWrapper<DefenseViolation> query = new LambdaQueryWrapper<DefenseViolation>()
                .eq(DefenseViolation::getDefenseId, defenseId)
                .eq(DefenseViolation::getSeverity, SEVERITY_MAJOR);
        if (sinceId != null) {
            query.gt(DefenseViolation::getId, sinceId);
        }
        Long count = violationMapper.selectCount(query);
        return count == null ? 0 : count.intValue();
    }

    private Long lastResumeId(Long defenseId) {
        List<DefenseViolation> resumes = violationMapper.selectList(
                new LambdaQueryWrapper<DefenseViolation>()
                        .eq(DefenseViolation::getDefenseId, defenseId)
                        .eq(DefenseViolation::getViolationType, TYPE_RESUME)
                        .orderByDesc(DefenseViolation::getId)
                        .last("limit 1"));
        return resumes.isEmpty() ? null : resumes.get(0).getId();
    }

    /** 列表版同样只看最后一次 RESUME 之后的 MAJOR 数量。 */
    private int majorSinceResume(List<DefenseViolation> itemsDesc) {
        int count = 0;
        for (DefenseViolation item : itemsDesc) {
            if (TYPE_RESUME.equals(item.getViolationType())) {
                break;
            }
            if (SEVERITY_MAJOR.equals(item.getSeverity())) {
                count++;
            }
        }
        return count;
    }

    private void insertEvent(DefenseSession session, String type, String severity, Object detail) {
        DefenseViolation event = new DefenseViolation();
        event.setDefenseId(session.getId());
        event.setStudentId(session.getStudentId());
        event.setViolationType(type);
        event.setSeverity(severity);
        event.setDetail(detail == null ? null : json.write(detail));
        HttpServletRequest request = currentRequest();
        if (request != null) {
            event.setIp(clientIpResolver.resolve(request));
            event.setUserAgent(clip(request.getHeader("User-Agent"), 500));
        }
        event.setOccurredAt(Times.now());
        event.setCreatedAt(Times.now());
        violationMapper.insert(event);
    }

    private DefenseDtos.ViolationItemVO toItemVo(DefenseViolation row) {
        return new DefenseDtos.ViolationItemVO(row.getViolationType(), row.getSeverity(),
                Times.offset(row.getOccurredAt()), row.getIp(), json.object(row.getDetail()));
    }

    private Map<Long, SysUser> studentMap(List<Long> ids) {
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return sysUserMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
