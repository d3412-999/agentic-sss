package org.spring_web.spring_boot_service.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 文件、提交物、异步任务、通知等通用契约。
 */
public final class CommonDtos {

    private CommonDtos() {
    }

    public record FileVO(
            String fileId,
            String filename,
            long size,
            String sha256,
            String contentType) {
    }

    public record UploadResultVO(
            String submissionId,
            String selectionId,
            String type,
            Integer version,
            String status,
            FileVO file,
            String parseTaskId,
            Integer pageCount,
            OffsetDateTime createdAt) {
    }

    public record SubmissionVO(
            String id,
            String type,
            Integer version,
            String status,
            FileVO file,
            Object parseResult,
            String analysisId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record SubmissionBriefVO(String id, String type, Integer version, String status, long size,
                                    OffsetDateTime createdAt) {
    }

    public record DownloadUrlVO(String url, int expiresIn) {
    }

    public record TaskAccepted(String taskId, String status) {
    }

    /** 前端轮询 GET /tasks/{taskId} 的结构，result 为 JSON 反序列化后的对象。 */
    public record TaskVO(
            String taskId,
            String type,
            String status,
            Integer progress,
            Object result,
            String error,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt) {
    }

    public record NotificationVO(
            String id,
            String type,
            String title,
            String content,
            String link,
            boolean read,
            OffsetDateTime createdAt) {
    }

    public record NotificationListVO(List<NotificationVO> list, long total, long unreadCount) {
    }

    public record ReadAllResult(int updated) {
    }

    /** 通用字段级错误项，配合 40001 / 42201 的 data.errors。 */
    public record FieldError(String field, String message) {
    }
}
