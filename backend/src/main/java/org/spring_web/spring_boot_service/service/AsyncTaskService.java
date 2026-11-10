package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.entity.AsyncTask;
import org.spring_web.spring_boot_service.mapper.AsyncTaskMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;

/**
 * 异步任务：受理（202 + taskId）、进度、成功/失败、取消、轮询。
 *
 * <p>所有查询都校验 operatorId，避免学生 A 通过 taskId 看到学生 B 的评审结果。</p>
 */
@Service
@RequiredArgsConstructor
public class AsyncTaskService {

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
        update(id, "RUNNING", progress, null, null, false);
    }

    public void markProgress(Long id, int progress) {
        AsyncTask task = BizAssert.found(taskMapper.selectById(id), "任务");
        if ("CANCELED".equals(task.getStatus())) {
            return;
        }
        task.setProgress(progress);
        task.setUpdatedAt(Times.now());
        taskMapper.updateById(task);
    }

    public void markSucceeded(Long id, Object result) {
        update(id, "SUCCEEDED", 100, json.write(result), null, true);
    }

    public void markFailed(Long id, String message) {
        update(id, "FAILED", null, null, message, true);
    }

    public void cancel(String taskId) {
        AsyncTask task = owned(taskId);
        BizAssert.state("PENDING".equals(task.getStatus()) || "RUNNING".equals(task.getStatus()),
                "任务已完成，无法取消");
        update(task.getId(), "CANCELED", task.getProgress(), null, null, true);
    }

    private void update(Long id, String status, Integer progress, String result,
                        String error, boolean finished) {
        LambdaUpdateWrapper<AsyncTask> wrapper = new LambdaUpdateWrapper<AsyncTask>()
                .eq(AsyncTask::getId, id)
                .set(AsyncTask::getStatus, status)
                .set(AsyncTask::getUpdatedAt, Times.now());
        if (progress != null) {
            wrapper.set(AsyncTask::getProgress, progress);
        }
        if (result != null) {
            wrapper.set(AsyncTask::getResult, result);
        }
        if (error != null) {
            wrapper.set(AsyncTask::getErrorMessage, error);
        }
        if (finished) {
            wrapper.set(AsyncTask::getFinishedAt, Times.now());
        }
        taskMapper.update(null, wrapper);
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
