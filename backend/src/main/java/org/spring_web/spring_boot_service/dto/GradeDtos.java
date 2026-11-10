package org.spring_web.spring_boot_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.spring_web.spring_boot_service.dto.OfferingDtos.Weights;

/**
 * 成绩契约，对应 GradeTableView（教师）与 GradeView（学生）。
 *
 * <p>学生的「分项成绩」和「权重构成」都在这里；成绩未发布时学生端一律不可见。</p>
 */
public final class GradeDtos {

    private GradeDtos() {
    }

    /** 只传需要调整的维度；reason 必填且会写入审计日志。 */
    public record GradeAdjustRequest(
            BigDecimal defenseScore,
            BigDecimal codeScore,
            BigDecimal reportScore,
            @NotBlank(message = "调整原因不能为空")
            @Size(min = 5, message = "调整原因至少 5 个字") String reason,
            @NotNull(message = "version 不能为空") Integer version) {
    }

    /** gradeIds 为空表示全部学生。 */
    public record GradePublishRequest(
            List<String> gradeIds,
            boolean allowIncomplete,
            boolean notifyStudents) {
    }

    public record GradePublishResult(
            int published,
            int skipped,
            List<String> skippedStudents,
            OffsetDateTime publishedAt) {
    }

    public record UnpublishRequest(List<String> gradeIds) {
    }

    public record GradeListItemVO(
            String gradeId,
            String studentId,
            String studentNo,
            String name,
            String className,
            int topicCount,
            BigDecimal defenseScore,
            BigDecimal codeScore,
            BigDecimal reportScore,
            BigDecimal finalScore,
            String level,
            String status,
            boolean adjusted,
            boolean published,
            OffsetDateTime updatedAt) {
    }

    public record StudentBrief(String studentNo, String name, String className) {
    }

    public record GradeItemVO(
            String selectionId,
            String topicTitle,
            BigDecimal codeScore,
            BigDecimal reportScore,
            BigDecimal defenseScore,
            BigDecimal finalScore) {
    }

    public record GradeDetailVO(
            String gradeId,
            StudentBrief student,
            Weights weights,
            List<GradeItemVO> items,
            BigDecimal finalScore,
            String level,
            String status,
            Integer version) {
    }

    /** GradeTableView 顶部的统计卡片。 */
    public record GradeSummary(BigDecimal average, BigDecimal max, BigDecimal min, double passRate) {
    }

    /** 学生端一行成绩。 */
    public record StudentGradeVO(
            String gradeId,
            String courseName,
            String semester,
            BigDecimal finalScore,
            String level,
            Weights weights,
            BigDecimal defenseScore,
            BigDecimal codeScore,
            BigDecimal reportScore,
            List<StudentGradeItemVO> items,
            String teacherComment,
            OffsetDateTime publishedAt) {
    }

    public record StudentGradeItemVO(
            String topicTitle,
            BigDecimal codeScore,
            BigDecimal reportScore,
            BigDecimal defenseScore,
            BigDecimal finalScore) {
    }

    public record RecalcRequest(String scope, boolean overwriteAdjusted, List<String> studentIds) {
    }

    public record ExportRequest(
            String format,
            boolean includeEvidence,
            boolean includeComments,
            boolean includeIssues) {
    }
}
