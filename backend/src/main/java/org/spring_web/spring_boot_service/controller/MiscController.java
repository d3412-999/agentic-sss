package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.Difficulty;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.dto.StudentImportDtos;
import org.spring_web.spring_boot_service.dto.TopicDtos;
import org.spring_web.spring_boot_service.service.AsyncTaskService;
import org.spring_web.spring_boot_service.service.FileUploadService;
import org.spring_web.spring_boot_service.service.NotificationService;
import org.spring_web.spring_boot_service.service.StudentRosterImportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知、异步任务轮询，以及给前端用的只读字典。
 */
@Tag(name = "通知、后台任务与字典")
@RestController
@RequiredArgsConstructor
public class MiscController {

    private final NotificationService notificationService;
    private final AsyncTaskService taskService;
    private final FileUploadService fileUploadService;
    private final StudentRosterImportService rosterImportService;
    private final org.spring_web.spring_boot_service.config.SssProperties properties;

    /** 难度字典：前端筛选页签与下拉都从这里取，避免把中文写死在页面里。 */
    @GetMapping("/api/v1/meta/difficulties")
    public Result<List<TopicDtos.DifficultyVO>> difficulties() {
        return Result.ok(Difficulty.ordered().stream()
                .map(item -> new TopicDtos.DifficultyVO(item.getCode(), item.getLabel(), item.getSort()))
                .toList());
    }

    /** 上传策略：前端 accept、单文件/单次上限提示与后端校验共用同一份配置。 */
    @GetMapping("/api/v1/meta/upload-policy")
    public Result<CommonDtos.UploadPolicyVO> uploadPolicy() {
        return Result.ok(fileUploadService.policy());
    }

    /** 学生名单导入限额：前端 accept、大小与条数提示、后端校验共用同一份配置。 */
    @GetMapping("/api/v1/meta/student-import-policy")
    public Result<StudentImportDtos.ImportPolicyVO> studentImportPolicy() {
        return Result.ok(rosterImportService.policy());
    }

    /**
     * 提交物上传策略：源码 / 报告各自的数量与大小上限。
     *
     * <p>前端拿这份值渲染「最多 9 份」「≤100MB」以及做即时拦截，
     * 后端用同一份做硬校验——改配置只改一处，不存在页面写 9、后端写 5 的漂移。</p>
     */
    @GetMapping("/api/v1/meta/submission-policy")
    public Result<CommonDtos.SubmissionPolicyVO> submissionPolicy() {
        var submission = properties.getUpload().getSubmission();
        return Result.ok(new CommonDtos.SubmissionPolicyVO(
                submission.getSource().getAllowedExtensions(),
                submission.getSource().getMaxFiles(),
                submission.getSource().getMaxFileBytes(),
                submission.getReport().getAllowedExtensions(),
                submission.getReport().getMaxFiles(),
                submission.getReport().getMaxFileBytes(),
                submission.getSimilarity().isEnabled(),
                submission.getSimilarity().getThreshold(),
                submission.getSimilarity().getHighlightThreshold()));
    }

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
