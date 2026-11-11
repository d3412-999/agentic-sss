package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.entity.AsyncTask;
import org.spring_web.spring_boot_service.mapper.AsyncTaskMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 异步任务：受理（202 + taskId）、进度、成功/失败、取消、轮询。
 *
 * <p>所有查询都校验 operatorId，避免学生 A 通过 taskId 看到学生 B 的评审结果。</p>
 *
 * <p><b>状态推进用带条件的 UPDATE，而不是「先查后改」</b>：任务可能在被取消的同一时刻
 * 被后台线程标记成功，若先 select 再 updateById，就会把 CANCELED 覆盖成 SUCCEEDED。
 * 这里把守卫条件 {@code status IN ('PENDING','RUNNING')} 塞进 UPDATE 的 WHERE，
 * 由 MySQL 行锁串行化：影响行数为 0 说明任务已进入终态（含取消），静默忽略即可。
 * 终态是单向的——SUCCEEDED / FAILED / CANCELED 之间绝不互相改写。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTaskService {

    /** 仍可被推进 / 取消的状态；其余（SUCCEEDED / FAILED / CANCELED）都是终态。 */
    private static final List<String> LIVE_STATUSES = List.of("PENDING", "RUNNING");

    private final AsyncTaskMapper taskMapper;
    private final JsonCodec json;

    public AsyncTask create(String type, String bizType, Long bizId, Object payload) {
        AsyncTask task = new AsyncTask();
        task.setType(type);
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setBizType(bizType);
        task.setBizId(bizId);
        task.setOperatorId(SecurityUtils.userId());
        task.setPayload(json.write(payload));
        task.setCreatedAt(Times.now());
        task.setUpdatedAt(Times.now());
        taskMapper.insert(task);
        return task;
    }

    public AsyncTask owned(String taskId) {
        Long id = BizAssert.id(taskId, "taskId");
        AsyncTask task = BizAssert.found(taskMapper.selectById(id), "任务");
        BizAssert.forbidden(task.getOperatorId() != null
                && task.getOperatorId().equals(SecurityUtils.userId()), "任务");
        return task;
    }

    public CommonDtos.TaskVO vo(AsyncTask task) {
        return new CommonDtos.TaskVO(
                String.valueOf(task.getId()), task.getType(), task.getStatus(), task.getProgress(),
                json.object(task.getResult()), task.getErrorMessage(),
                Times.offset(task.getCreatedAt()), Times.offset(task.getFinishedAt()));
    }

    public void markRunning(Long id, int progress) {
        LambdaUpdateWrapper<AsyncTask> wrapper = liveUpdate(id)
                .set(AsyncTask::getStatus, "RUNNING")
                .set(AsyncTask::getProgress, clampProgress(progress));
        if (!apply(wrapper)) {
            log.debug("任务 {} 已进入终态，忽略 markRunning", id);
        }
    }

    public void markProgress(Long id, int progress) {
        LambdaUpdateWrapper<AsyncTask> wrapper = liveUpdate(id)
                .set(AsyncTask::getProgress, clampProgress(progress));
        if (!apply(wrapper)) {
            log.debug("任务 {} 已进入终态，忽略 markProgress", id);
        }
    }

    public void markSucceeded(Long id, Object result) {
        LambdaUpdateWrapper<AsyncTask> wrapper = liveUpdate(id)
                .set(AsyncTask::getStatus, "SUCCEEDED")
                .set(AsyncTask::getProgress, 100)
                .set(AsyncTask::getFinishedAt, Times.now());
        String payload = json.write(result);
        if (payload != null) {
            wrapper.set(AsyncTask::getResult, payload);
        }
        if (!apply(wrapper)) {
            log.debug("任务 {} 已进入终态，忽略 markSucceeded", id);
        }
    }

    public void markFailed(Long id, String message) {
        LambdaUpdateWrapper<AsyncTask> wrapper = liveUpdate(id)
                .set(AsyncTask::getStatus, "FAILED")
                .set(AsyncTask::getFinishedAt, Times.now());
        // ex.getMessage() 可能为 null / 空白：不能把空串写进 error_message（前端会显示空错误）
        if (StringUtils.hasText(message)) {
            wrapper.set(AsyncTask::getErrorMessage, message);
        }
        if (!apply(wrapper)) {
            log.debug("任务 {} 已进入终态，忽略 markFailed", id);
        }
    }

    public void cancel(String taskId) {
        AsyncTask task = owned(taskId);
        BizAssert.state("PENDING".equals(task.getStatus()) || "RUNNING".equals(task.getStatus()),
                "任务已完成，无法取消");
        LambdaUpdateWrapper<AsyncTask> wrapper = liveUpdate(task.getId())
                .set(AsyncTask::getStatus, "CANCELED")
                .set(AsyncTask::getFinishedAt, Times.now());
        if (task.getProgress() != null) {
            wrapper.set(AsyncTask::getProgress, task.getProgress());
        }
        // 预检查与 UPDATE 之间任务可能刚好完成：以影响行数为准，抛「已完成」而不是覆盖终态
        BizAssert.state(apply(wrapper), "任务已完成，无法取消");
    }

    /**
     * 只允许从非终态推进的原子更新条件；配合 {@link #apply} 使用。
     *
     * <p>守卫条件不能退化成 {@code updateById} —— 后者没有 WHERE 状态条件，正是覆盖 CANCELED 的根因。</p>
     */
    private LambdaUpdateWrapper<AsyncTask> liveUpdate(Long id) {
        return new LambdaUpdateWrapper<AsyncTask>()
                .eq(AsyncTask::getId, id)
                .in(AsyncTask::getStatus, LIVE_STATUSES)
                .set(AsyncTask::getUpdatedAt, Times.now());
    }

    /** 返回 false 表示任务已进入终态（含已取消）或不存在，调用方静默忽略。 */
    private boolean apply(LambdaUpdateWrapper<AsyncTask> wrapper) {
        return taskMapper.update(null, wrapper) > 0;
    }

    /** 进度统一夹到 0-100，避免后台线程传入越界值污染前端进度条。 */
    private static int clampProgress(int progress) {
        return Math.max(0, Math.min(100, progress));
    }

    public CommonDtos.TaskVO accepted(AsyncTask task) {
        return new CommonDtos.TaskVO(String.valueOf(task.getId()), task.getType(),
                task.getStatus(), task.getProgress(), null, null,
                Times.offset(task.getCreatedAt()), null);
    }

    public static BizException unavailable(String message) {
        return new BizException(ErrorCode.AI_UNAVAILABLE, message);
    }
}
