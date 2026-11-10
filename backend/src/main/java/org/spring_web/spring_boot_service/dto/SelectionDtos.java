package org.spring_web.spring_boot_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 选题契约，对应 SelectionListView / SelectionDetailView / TopicSelectView 的提交动作。
 */
public final class SelectionDtos {

    private SelectionDtos() {
    }

    public record SelectionCreateRequest(
            @NotBlank(message = "offeringId 不能为空") String offeringId,
            @NotEmpty(message = "至少选择一道题目") List<String> topicIds) {
    }

    public record CreatedItemVO(
            String id,
            String topicId,
            String topicTitle,
            String status,
            OffsetDateTime createdAt) {
    }

    public record TopicBrief(String id, String title, String difficulty) {
    }

    public record Progress(
            boolean sourceUploaded,
            boolean reportUploaded,
            boolean codeAnalyzed,
            boolean reportEvaluated,
            String defenseStatus) {
    }

    public record Scores(BigDecimal code, BigDecimal report, BigDecimal defense, BigDecimal finalScore) {
    }

    public record SelectionVO(
            String id,
            String offeringId,
            TopicBrief topic,
            String status,
            Progress progress,
            Scores scores,
            OffsetDateTime submitDeadline,
            Integer version) {
    }

    public record SubmissionBrief(
            String id,
            String type,
            Integer version,
            String status,
            OffsetDateTime createdAt) {
    }

    /** 未发布成绩时 scores 全为 null、scoresVisible=false，避免学生提前看到分数。 */
    public record SelectionDetailVO(
            String id,
            String offeringId,
            String topicTitle,
            String status,
            List<SubmissionBrief> submissions,
            Scores scores,
            boolean scoresVisible,
            BigDecimal finalScore,
            OffsetDateTime submitDeadline,
            Integer version) {
    }
}
