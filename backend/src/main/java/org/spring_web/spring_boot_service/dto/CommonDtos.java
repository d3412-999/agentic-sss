package org.spring_web.spring_boot_service.dto;

import java.math.BigDecimal;
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

    /**
     * 文件元数据（只读校验用）。
     *
     * <p>前端把「已上传成功」的文件元数据存进 localStorage，刷新后据此恢复列表。
     * 但文件可能已在服务端被清理，所以恢复时要逐条回查。</p>
     *
     * <p>{@code exists=false} 用 200 返回而不是 404：404 会被前端的统一错误处理
     * 当成「请求失败」弹全局提示，而这里的语义是「这条本地记录该删了」，属于正常结果。
     * 物理文件丢失（库里有记录、盘上没有）也算 {@code exists=false}。</p>
     */
    public record FileInfoVO(
            String fileId,
            String filename,
            long size,
            String sha256,
            String contentType,
            OffsetDateTime createdAt,
            boolean exists) {
    }

    /**
     * 上传结果。
     *
     * <p>{@code file} 是兼容字段，取本次提交的第一个文件；新前端一律读 {@code files}。
     * 保留它是因为旧调用方（以及一部分已存的接口文档）仍按单文件解析，
     * 直接删字段会让它们静默拿到 null。</p>
     */
    public record UploadResultVO(
            String submissionId,
            String selectionId,
            String type,
            Integer version,
            String status,
            FileVO file,
            List<SubmissionFileVO> files,
            String parseTaskId,
            Integer pageCount,
            OffsetDateTime createdAt) {
    }

    /** 提交版本内的单个文件；{@code id} 是版本文件 ID，删除与排序按它定位。 */
    public record SubmissionFileVO(
            String id,
            String fileId,
            String filename,
            long size,
            String sha256,
            String contentType,
            int sortIndex) {
    }

    /** 版本历史条目：前端版本选择器用，只读。 */
    public record SubmissionVersionVO(
            String versionId,
            Integer version,
            String status,
            int fileCount,
            long size,
            boolean latest,
            OffsetDateTime createdAt) {
    }

    /** 两阶段提交的第二步：把已上传的 fileId 列表绑定到一次提交。 */
    public record BindFilesRequest(String type, List<String> fileIds, String remark) {
    }

    /** 提交物上传策略：前端 accept、数量上限与提示文案都从这里取，不写死。 */
    public record SubmissionPolicyVO(
            List<String> sourceExtensions,
            int sourceMaxFiles,
            long sourceMaxFileBytes,
            List<String> reportExtensions,
            int reportMaxFiles,
            long reportMaxFileBytes,
            boolean similarityEnabled,
            double similarityThreshold,
            /** 教师端标红门槛：低于它的命中只提示不标红。 */
            double similarityHighlightThreshold) {
    }

    /** 一条相似度命中：谁的文件和谁的文件、像到什么程度、是内容相同还是结构相同。 */
    /**
     * 相似度命中。
     *
     * <p>带上 {@code versionFileId} / {@code otherVersionFileId} 是为了让前端能把
     * 「A→B」和「B→A」两条记录合并成一行——后端双向各存一条（各查各的），
     * 但老师只想看到「这两个文件像」这一个事实。</p>
     */
    public record SimilarityVO(
            String id,
            String submissionId,
            String selectionId,
            String otherSelectionId,
            String otherStudentNo,
            String otherStudentName,
            String versionFileId,
            String otherVersionFileId,
            String fileName,
            String otherFileName,
            double score,
            String kind,
            OffsetDateTime createdAt) {
    }

    public record SubmissionVO(
            String id,
            String type,
            Integer version,
            String status,
            FileVO file,
            List<SubmissionFileVO> files,
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

    /**
     * 单个评审阶段（源码 / 报告）的状态。
     *
     * <p>状态只用四个值：{@code PENDING / RUNNING / SUCCEEDED / FAILED}，
     * 缺记录时给 {@code MISSING}（例如学生只交了源码没交报告）。
     * 前端不需要理解 {@code async_task} 与业务表两套状态，这里已经合成一套。</p>
     *
     * @param retryable 失败或超时是否允许重新触发
     * @param taskId    最近一次关联的异步任务号（没有则为 null）
     */
    public record ReviewStageVO(
            String submissionId,
            String type,
            String status,
            BigDecimal score,
            String grade,
            String summary,
            String error,
            boolean retryable,
            OffsetDateTime updatedAt,
            String taskId) {
    }

    /**
     * 一次「源码 + 报告」的统一评审状态。
     *
     * @param overall   NONE / RUNNING / DONE / PARTIAL / FAILED
     * @param finished  是否已全部进入终态（前端据此停止轮询）
     */
    public record ReviewStatusVO(
            String submissionId,
            String selectionId,
            String overall,
            boolean finished,
            ReviewStageVO source,
            ReviewStageVO report,
            OffsetDateTime updatedAt) {
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

    /** 批量上传中单个文件的失败原因；成功的文件不受影响，调用方可只重试失败项。 */
    public record FailedUpload(String filename, String reason) {
    }

    /** 批量上传结果：succeeded 里每个文件都有独立 id 与下载地址。 */
    public record BatchUploadVO(List<FileVO> succeeded, List<FailedUpload> failed) {
    }

    /** 上传策略下发，前端 accept / 提示文案与后端校验共用同一份配置。 */
    public record UploadPolicyVO(
            List<String> allowedExtensions,
            long maxFileBytes,
            int maxFilesPerRequest,
            long maxTotalBytes,
            int maxPerTopic) {
    }
}
