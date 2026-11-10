package org.spring_web.spring_boot_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 优秀作品模板库契约。
 */
public final class ExcellentWorkDtos {

    private ExcellentWorkDtos() {
    }

    /**
     * 可被选为优秀作品的候选选题（带报告评审状态与得分）。
     */
    public record CandidateVO(
            String selectionId,
            String studentId,
            String studentNo,
            String studentName,
            String topicTitle,
            String reportSubmissionId,
            String reportStatus,
            BigDecimal reportScore,
            Integer pageCount,
            String workId,
            String workIndexStatus) {
    }

    /**
     * 已入库的优秀作品。
     */
    public record WorkVO(
            String workId,
            String offeringId,
            String selectionId,
            String submissionId,
            String title,
            String studentNo,
            String studentName,
            String topicTitle,
            String note,
            String tags,
            String indexStatus,
            String indexMethod,
            String errorMessage,
            OffsetDateTime createdAt) {
    }

    /**
     * 教师把某个选题的报告加入模板库。
     */
    public record CreateRequest(
            @NotBlank(message = "selectionId 不能为空") String selectionId,
            @Size(max = 1000, message = "推荐理由最多 1000 字") String note,
            @Size(max = 500, message = "标签最多 500 字") String tags) {
    }
}
