package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.service.AsyncTaskService;
import org.spring_web.spring_boot_service.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知与异步任务轮询。
 */
@Tag(name = "通知与后台任务")
@RestController
@RequiredArgsConstructor
public class MiscController {

    private final NotificationService notificationService;
    private final AsyncTaskService taskService;

    @GetMapping("/api/v1/notifications")
    public Result<CommonDtos.NotificationListVO> notifications(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(notificationService.list(unreadOnly, page, size));
    }

    @PatchMapping("/api/v1/notifications/{notificationId}/read")
    public Result<Void> markRead(@PathVariable String notificationId) {
        notificationService.markRead(notificationId);
        return Result.ok();
    }

    @PostMapping("/api/v1/notifications/read-all")
    public Result<CommonDtos.ReadAllResult> readAll() {
        return Result.ok(new CommonDtos.ReadAllResult(notificationService.readAll()));
    }

    @GetMapping("/api/v1/tasks/{taskId}")
    public Result<CommonDtos.TaskVO> task(@PathVariable String taskId) {
        return Result.ok(taskService.vo(taskService.owned(taskId)));
    }

    @PostMapping("/api/v1/tasks/{taskId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelTask(@PathVariable String taskId) {
        taskService.cancel(taskId);
    }
}
