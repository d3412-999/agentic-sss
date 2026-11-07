package org.spring_web.spring_boot_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 课程模板 / 开课 / 学生名单契约。
 *
 * <p>对应前端 OfferingCreateView 的表单：基本信息、选题与提交、答辩设置、成绩权重。
 * 注意 UI 的日期控件只给到「日」（如 2026-09-10），所以 Timeline 用 LocalDate 接收，
 * 由 Service 统一补全为当天的起止时刻，避免前端时区差异。</p>
 */
public final class OfferingDtos {

    private OfferingDtos() {
    }

    /** 权重和为 100 由 Service 校验（需要返回 data.errors 字段级错误，Bean 注解做不到）。 */
    public record Weights(
            @NotNull @Min(0) @Max(100) Integer defense,
            @NotNull @Min(0) @Max(100) Integer code,
            @NotNull @Min(0) @Max(100) Integer report) {
    }

    public record DefenseConfig(
            String defenseMode,
            Integer questionCount,
            Integer maxAnswerSeconds,
            Boolean allowRetry,
            String language,
            Boolean enableVideo) {
    }

    public record Timeline(
            LocalDate selectStart,
            LocalDate selectEnd,
            LocalDate submitDeadline,
            LocalDate defenseStart,
            LocalDate defenseEnd) {
    }

    public record OfferingCreateRequest(
            @NotBlank(message = "courseId 不能为空") String courseId,
            @NotBlank(message = "学期不能为空") String semester,
            @NotBlank(message = "教学班不能为空") @Size(max = 50) String className,
            String description,
            Integer topicSelectLimit,
            @NotNull @Valid Weights weights,
            DefenseConfig defenseConfig,
            Timeline timeline) {
    }

    /** PATCH：只传需要改的字段；version 必带用于乐观锁。 */
    public record OfferingPatchRequest(
            @NotNull(message = "version 不能为空") Integer version,
            String className,
            String description,
            Integer topicSelectLimit,
            Weights weights,
            DefenseConfig defenseConfig,
            Timeline timeline) {
    }

    public record CourseCreateRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            BigDecimal credit,
            String description) {
    }

    public record CourseVO(String id, String code, String name, BigDecimal credit, String description) {
    }

    public record OfferingVO(
            String id,
            String courseId,
            String courseName,
            String semester,
            String className,
            String description,
            String status,
            Integer topicSelectLimit,
            Weights weights,
            DefenseConfig defenseConfig,
            Timeline timeline,
            OffsetDateTime publishedAt,
            Integer version,
            Stats stats) {
    }

    public record Stats(long studentCount, long topicCount, long selectionCount, long defenseFinishedCount) {
    }

    /** OfferingListView 的列表行。 */
    public record OfferingListItemVO(
            String id,
            String courseName,
            String semester,
            String className,
            String status,
            long studentCount,
            long topicCount,
            long selectionCount,
            long defenseFinishedCount,
            OffsetDateTime createdAt) {
    }

    /** 学生端 CourseListView 的行，canSelect / 原因是 UI 直接显示的。 */
    public record StudentOfferingVO(
            String offeringId,
            String courseName,
            String teacherName,
            String semester,
            String className,
            OffsetDateTime selectStart,
            OffsetDateTime selectEnd,
            OffsetDateTime submitDeadline,
            Integer topicSelectLimit,
            int mySelectionCount,
            boolean canSelect,
            String cannotSelectReason) {
    }

    public record DashboardVO(
            long studentCount,
            long selectedStudentCount,
            long selectionCount,
            double submissionRate,
            long sourceSubmittedCount,
            long reportSubmittedCount,
            long defenseFinishedCount,
            long defensePendingCount,
            AverageScore averageScore,
            List<Distribution> scoreDistribution,
            List<TopIssue> topIssues) {
    }

    public record AverageScore(BigDecimal defense, BigDecimal code, BigDecimal report, BigDecimal finalScore) {
    }

    public record Distribution(String range, long count) {
    }

    public record TopIssue(String tool, String rule, long count) {
    }

    /** 发布开课失败时返回的具体阻塞项，前端逐条展示。 */
    public record Blocker(String code, String message) {
    }

    public record StudentCreateRequest(
            @NotBlank String studentNo,
            @NotBlank String name,
            String className,
            String email) {
    }

    public record StudentVO(
            String studentId,
            String studentNo,
            String name,
            String className,
            String email,
            String status,
            int selectionCount,
            boolean submitted,
            String defenseStatus,
            BigDecimal finalScore,
            OffsetDateTime lastLoginAt,
            String initialPassword) {
    }

    public record StudentStatusPatch(@NotBlank String status) {
    }

    public record ResetPasswordVO(String initialPassword, boolean mustChangePassword) {
    }
}
