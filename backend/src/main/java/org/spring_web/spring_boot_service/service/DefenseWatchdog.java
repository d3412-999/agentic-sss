package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.entity.DefenseSession;
import org.spring_web.spring_boot_service.mapper.DefenseSessionMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 答辩看门狗：
 *
 * <ol>
 *   <li>批量答辩超过截止时间：强制结束（进行中→COMPLETED，未开始→EXPIRED）；</li>
 *   <li>已结束但还没评分的答辩：自动调用 AI 评分，不再需要教师逐条手点。</li>
 * </ol>
 *
 * <p>自动评分失败时保留 COMPLETED 状态并有限重试；教师仍可在页面手动「重新评分」兜底。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefenseWatchdog {

    private static final int EVALUATE_BATCH = 10;
    private static final int MAX_ATTEMPTS = 3;

    private final DefenseSessionMapper sessionMapper;
    private final DefenseService defenseService;

    /** 防止同一会话被本机多轮扫描并发评分；失败计数避免 AI 故障时无限重试。 */
    private final Map<Long, Integer> evaluateAttempts = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${sss.defense.watchdog-interval-ms:15000}",
            initialDelayString = "${sss.defense.watchdog-initial-delay-ms:15000}")
    public void sweep() {
        expireTimedOutSessions();
        autoEvaluateCompletedSessions();
    }

    private void expireTimedOutSessions() {
        LocalDateTime now = Times.now();
        List<DefenseSession> timedOut = sessionMapper.selectList(new LambdaQueryWrapper<DefenseSession>()
                .isNotNull(DefenseSession::getDeadlineAt)
                .le(DefenseSession::getDeadlineAt, now)
                .in(DefenseSession::getStatus, "READY", "IN_PROGRESS")
                .orderByAsc(DefenseSession::getDeadlineAt)
                .last("limit 20"));
        for (DefenseSession session : timedOut) {
            try {
                defenseService.forceFinish(String.valueOf(session.getId()), "答辩时间已到，系统自动结束");
                log.info("答辩超时自动结束：sessionId={}, deadlineAt={}", session.getId(), session.getDeadlineAt());
            } catch (RuntimeException ex) {
                log.warn("答辩超时自动结束失败：sessionId={}", session.getId(), ex);
            }
        }
    }

    private void autoEvaluateCompletedSessions() {
        List<DefenseSession> pending = sessionMapper.selectList(new LambdaQueryWrapper<DefenseSession>()
                .eq(DefenseSession::getStatus, "COMPLETED")
                .isNull(DefenseSession::getScore)
                .isNotNull(DefenseSession::getFinishedAt)
                .orderByAsc(DefenseSession::getFinishedAt)
                .last("limit " + EVALUATE_BATCH));
        for (DefenseSession session : pending) {
            int attempts = evaluateAttempts.getOrDefault(session.getId(), 0);
            if (attempts >= MAX_ATTEMPTS) {
                continue;
            }
            try {
                defenseService.evaluateSession(session);
                evaluateAttempts.remove(session.getId());
                log.info("答辩自动评分完成：sessionId={}, score={}", session.getId(), session.getScore());
            } catch (RuntimeException ex) {
                int next = attempts + 1;
                evaluateAttempts.put(session.getId(), next);
                log.warn("答辩自动评分失败（第 {}/{} 次）：sessionId={}, reason={}",
                        next, MAX_ATTEMPTS, session.getId(), ex.getMessage());
            }
        }
    }
}
