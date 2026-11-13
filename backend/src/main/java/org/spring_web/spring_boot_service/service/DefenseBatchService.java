package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.AsyncConfig;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.DefenseDtos;
import org.spring_web.spring_boot_service.entity.DefenseBatchItem;
import org.spring_web.spring_boot_service.entity.DefenseBatchTask;
import org.spring_web.spring_boot_service.entity.DefenseSession;
import org.spring_web.spring_boot_service.entity.Offering;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.mapper.DefenseBatchItemMapper;
import org.spring_web.spring_boot_service.mapper.DefenseBatchTaskMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 批量发起答辩。
 *
 * <p><b>为什么异步</b>：一位学生一次 AI 出题约 5~10 秒，10 个人串行就是 1 分钟以上，
 * 直接在 HTTP 请求里做完必然撞前端/网关超时。这里受理后立刻返回 batchId，
 * 前端轮询进度；每完成一条就落库，刷新页面也能看到已有结果。</p>
 *
 * <p><b>并发上限 3</b>：再高会把本地 AI 服务打满，导致本来能成功的出题也超时。
 * 单条失败只影响自己（部分成功语义），不会回滚已成功的会话。</p>
 *
 * <p><b>SecurityContext 不会自动跨线程</b>：批量在自己的线程池里跑，
 * 而 {@code createSession} 内部要做「教师只能操作自己的教学班」校验。
 * 所以受理时抓一次 Authentication，在每个工作线程里显式放回 ThreadLocal，
 * 结束后清理——否则所有子任务都会 401。</p>
 */
@Slf4j
@Service
public class DefenseBatchService {

    private static final int MIN_CONCURRENCY = 1;
    private static final int MAX_CONCURRENCY = 3;

    private final OfferingService offeringService;
    private final DefenseService defenseService;
    private final SelectionMapper selectionMapper;
    private final SysUserMapper sysUserMapper;
    private final DefenseBatchTaskMapper taskMapper;
    private final DefenseBatchItemMapper itemMapper;
    private final AuditLogService auditLogService;
    private final SssProperties properties;
    private final java.util.concurrent.Executor executor;

    public DefenseBatchService(OfferingService offeringService,
                               DefenseService defenseService,
                               SelectionMapper selectionMapper,
                               SysUserMapper sysUserMapper,
                               DefenseBatchTaskMapper taskMapper,
                               DefenseBatchItemMapper itemMapper,
                               AuditLogService auditLogService,
                               SssProperties properties,
                               @Qualifier(AsyncConfig.EXECUTOR) java.util.concurrent.Executor executor) {
        this.offeringService = offeringService;
        this.defenseService = defenseService;
        this.selectionMapper = selectionMapper;
        this.sysUserMapper = sysUserMapper;
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.executor = executor;
    }

    /** 受理批量发起：校验 → 建任务 → 丢后台 → 立刻返回 RUNNING。 */
    public DefenseDtos.BatchResultVO start(DefenseDtos.BatchSessionRequest request) {
        Offering offering = offeringService.ownedOffering(request.offeringId());
        List<String> rawIds = request.selectionIds() == null ? List.of() : request.selectionIds();
        List<Long> selectionIds = new ArrayList<>();
        for (String raw : rawIds) {
            if (StringUtils.hasText(raw) && !selectionIds.contains(parseId(raw))) {
                selectionIds.add(parseId(raw));
            }
        }
        SssProperties.Batch config = properties.getDefense().getBatch();
        if (selectionIds.isEmpty()) {
            throw new BizException(org.spring_web.spring_boot_service.common.ErrorCode.PARAM_INVALID,
                    "请至少选择 1 名学生");
        }
        if (selectionIds.size() > config.getMaxStudents()) {
            throw new BizException(org.spring_web.spring_boot_service.common.ErrorCode.PARAM_INVALID,
                    "单次最多批量发起 " + config.getMaxStudents() + " 人，当前 " + selectionIds.size() + " 人");
        }
        if (request.questionCount() != null && !StringUtils.hasText(request.overrideReason())) {
            throw new BizException(org.spring_web.spring_boot_service.common.ErrorCode.PARAM_INVALID,
                    "手动指定题数必须填写理由");
        }

        LocalDateTime now = Times.now();
        DefenseBatchTask task = new DefenseBatchTask();
        task.setOfferingId(offering.getId());
        task.setOperatorId(SecurityUtilsUserId());
        task.setStatus("RUNNING");
        task.setTotal(selectionIds.size());
        task.setSucceeded(0);
        task.setSkipped(0);
        task.setFailed(0);
        task.setConfig("{\"concurrency\":" + concurrency(config) + "}");
        task.setCreatedAt(now);
        taskMapper.insert(task);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        executor.execute(() -> {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                run(task, offering, selectionIds, request.questionCount(), request.overrideReason());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        return view(task, List.of());
    }

    /** 批量进度 / 结果：教师只能看自己发起的批次。 */
    public DefenseDtos.BatchResultVO detail(String batchId) {
        DefenseBatchTask task = taskMapper.selectById(parseId(batchId));
        if (task == null) {
            throw new BizException(org.spring_web.spring_boot_service.common.ErrorCode.NOT_FOUND, "批量任务不存在");
        }
        offeringService.ownedOffering(String.valueOf(task.getOfferingId()));
        List<DefenseBatchItem> items = itemMapper.selectList(new LambdaQueryWrapper<DefenseBatchItem>()
                .eq(DefenseBatchItem::getBatchId, task.getId())
                .orderByAsc(DefenseBatchItem::getId));
        return view(task, items);
    }

    // ---------------------------------------------------------------- 后台执行

    private void run(DefenseBatchTask task, Offering offering, List<Long> selectionIds,
                     Integer questionCount, String overrideReason) {
        long started = System.currentTimeMillis();
        // ThreadLocal 不会跨线程传递：把当前请求的身份显式带进后台线程，
        // 再由 runOne 复制到每个工作线程（扇出到线程池后仍是空上下文）。
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        List<Selection> selections = selectionMapper.selectBatchIds(selectionIds);
        Map<Long, Selection> byId = new LinkedHashMap<>();
        for (Selection selection : selections) {
            byId.put(selection.getId(), selection);
        }
        Map<Long, DefenseSession> sessionIndex = defenseService.sessionsIndex(offering.getId());

        List<Selection> eligible = new ArrayList<>();
        for (Long selectionId : selectionIds) {
            Selection selection = byId.get(selectionId);
            if (selection == null || !offering.getId().equals(selection.getOfferingId())) {
                record(task, selectionId, null, "SKIPPED", null, null, "选题不存在或不属于该教学班");
                continue;
            }
            String reason = defenseService.precheck(selection, sessionIndex.get(selectionId));
            if (reason != null) {
                record(task, selectionId, selection.getStudentId(), "SKIPPED", null, null, reason);
                continue;
            }
            eligible.add(selection);
        }

        int concurrency = concurrency(properties.getDefense().getBatch());
        if (eligible.size() <= 1) {
            for (Selection selection : eligible) {
                runOne(task, selection, questionCount, overrideReason, authentication);
            }
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            try {
                for (Selection selection : eligible) {
                    pool.submit(() -> runOne(task, selection, questionCount, overrideReason, authentication));
                }
                pool.shutdown();
                if (!pool.awaitTermination(10, TimeUnit.MINUTES)) {
                    log.warn("批量答辩超时未完成，batchId={}", task.getId());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                pool.shutdownNow();
            }
        }

        // 计数由 record() 在库里原子累加，这里读回来只用于判定终态与写审计；
        // 绝不能 updateById(task) —— 内存里的 task 还停在创建时的 0，会把累加结果冲掉。
        DefenseBatchTask latest = taskMapper.selectById(task.getId());
        int total = latest == null || latest.getTotal() == null ? 0 : latest.getTotal();
        int failed = latest == null || latest.getFailed() == null ? 0 : latest.getFailed();
        int succeeded = latest == null || latest.getSucceeded() == null ? 0 : latest.getSucceeded();
        int skipped = latest == null || latest.getSkipped() == null ? 0 : latest.getSkipped();
        String finalStatus = failed == 0 ? "DONE" : (succeeded == 0 ? "FAILED" : "PARTIAL");
        LocalDateTime finishedAt = Times.now();
        taskMapper.update(null, new LambdaUpdateWrapper<DefenseBatchTask>()
                .eq(DefenseBatchTask::getId, task.getId())
                .set(DefenseBatchTask::getStatus, finalStatus)
                .set(DefenseBatchTask::getFinishedAt, finishedAt));
        // 同步回内存对象，返回给调用方的视图才是最新值
        task.setStatus(finalStatus);
        task.setFinishedAt(finishedAt);
        task.setSucceeded(succeeded);
        task.setSkipped(skipped);
        task.setFailed(failed);
        auditLogService.record("DEFENSE_BATCH_CREATE", "OFFERING", offering.getId(),
                Map.of("requested", total),
                Map.of("succeeded", succeeded, "failed", failed, "skipped", skipped),
                "批量发起答辩，耗时 " + (System.currentTimeMillis() - started) + "ms");
    }

    private void runOne(DefenseBatchTask task, Selection selection,
                        Integer questionCount, String overrideReason, Authentication authentication) {
        // 每个工作线程各自绑定一次身份：池化线程不会继承提交者的 ThreadLocal
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            DefenseDtos.SessionVO session = defenseService.createSession(
                    String.valueOf(selection.getId()), questionCount, overrideReason);
            record(task, selection.getId(), selection.getStudentId(), "SUCCEEDED",
                    parseId(session.id()), session.questionCount(), null);
        } catch (BizException ex) {
            record(task, selection.getId(), selection.getStudentId(), "FAILED", null, null, ex.getMessage());
        } catch (RuntimeException ex) {
            // 单条失败不能中断整批：记下原因，继续下一个学生
            log.warn("批量发起答辩单条失败，selectionId={}", selection.getId(), ex);
            record(task, selection.getId(), selection.getStudentId(), "FAILED", null, null,
                    ex.getMessage() == null ? "创建失败" : ex.getMessage());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 落一条结果并原子累加计数：前端轮询时能实时看到进度。 */
    private synchronized void record(DefenseBatchTask task, Long selectionId, Long studentId,
                                     String status, Long sessionId, Integer questionCount, String reason) {
        DefenseBatchItem item = new DefenseBatchItem();
        item.setBatchId(task.getId());
        item.setSelectionId(selectionId);
        item.setStudentId(studentId);
        item.setStatus(status);
        item.setSessionId(sessionId);
        item.setQuestionCount(questionCount);
        item.setReason(reason);
        item.setCreatedAt(Times.now());
        itemMapper.insert(item);

        DefenseBatchTask latest = taskMapper.selectById(task.getId());
        if (latest == null) {
            return;
        }
        switch (status) {
            case "SUCCEEDED" -> latest.setSucceeded(next(latest.getSucceeded()));
            case "SKIPPED" -> latest.setSkipped(next(latest.getSkipped()));
            default -> latest.setFailed(next(latest.getFailed()));
        }
        taskMapper.updateById(latest);
    }

    private int next(Integer value) {
        return value == null ? 1 : value + 1;
    }

    // ---------------------------------------------------------------- 装配

    private DefenseDtos.BatchResultVO view(DefenseBatchTask task, List<DefenseBatchItem> items) {
        List<Long> studentIds = items.stream().map(DefenseBatchItem::getStudentId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, SysUser> students = studentIds.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(studentIds).stream()
                .collect(java.util.stream.Collectors.toMap(SysUser::getId, user -> user));
        List<DefenseDtos.BatchItemVO> rows = new ArrayList<>();
        for (DefenseBatchItem item : items) {
            SysUser student = item.getStudentId() == null ? null : students.get(item.getStudentId());
            rows.add(new DefenseDtos.BatchItemVO(
                    String.valueOf(item.getSelectionId()),
                    student == null || student.getStudentNo() == null ? "" : student.getStudentNo(),
                    student == null || student.getName() == null ? "" : student.getName(),
                    item.getStatus(),
                    item.getSessionId() == null ? null : String.valueOf(item.getSessionId()),
                    item.getQuestionCount(),
                    item.getReason()));
        }
        return new DefenseDtos.BatchResultVO(
                String.valueOf(task.getId()), task.getStatus(),
                task.getTotal() == null ? 0 : task.getTotal(),
                task.getSucceeded() == null ? 0 : task.getSucceeded(),
                task.getSkipped() == null ? 0 : task.getSkipped(),
                task.getFailed() == null ? 0 : task.getFailed(),
                rows);
    }

    private int concurrency(SssProperties.Batch config) {
        return Math.max(MIN_CONCURRENCY, Math.min(MAX_CONCURRENCY, config.getConcurrency()));
    }

    private Long parseId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (RuntimeException ex) {
            throw new BizException(org.spring_web.spring_boot_service.common.ErrorCode.PARAM_INVALID,
                    "ID 格式不正确：" + raw);
        }
    }

    private Long SecurityUtilsUserId() {
        return org.spring_web.spring_boot_service.security.SecurityUtils.userId();
    }
}
