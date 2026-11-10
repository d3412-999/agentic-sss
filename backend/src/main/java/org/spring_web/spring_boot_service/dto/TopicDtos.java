package org.spring_web.spring_boot_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 题目契约，对应 TopicManageView / TopicSelectView。
 *
 * <p>UI 的选题卡片需要 remaining 与 selectedByMe，这两个字段由 Service 结合
 * 当前学生的选题记录实时算出，不直接落库。</p>
 */
public final class TopicDtos {

    private TopicDtos() {
    }

    public record TopicCreateRequest(
            @NotBlank @Size(min = 2, max = 100) String title,
            @NotBlank @Size(max = 5000) String description,
            String requirements,
            String difficulty,
            Integer maxSelectCount,
            List<String> tags,
            List<String> expectedTech,
            List<String> attachments,
            String status) {
    }

    public record TopicUpdateRequest(
            @NotNull(message = "version 不能为空") Integer version,
            String title,
            String description,
            String requirements,
            String difficulty,
            Integer maxSelectCount,
            List<String> tags,
            List<String> expectedTech,
            String status) {
    }

    public record PublishBatchRequest(@NotEmpty List<String> topicIds) {
    }

    public record PublishBatchResult(int published, List<Failed> failed) {
    }

    public record Failed(String topicId, String reason) {
    }

    public record AttachmentVO(String fileId, String filename, String downloadUrl) {
    }

    public record TopicVO(
            String id,
            String offeringId,
            String title,
            String description,
            String requirements,
            String difficulty,
            Integer maxSelectCount,
            Integer selectedCount,
            List<String> tags,
            List<String> expectedTech,
            String status,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Integer version,
            List<AttachmentVO> attachments) {
    }

    public record TopicListItemVO(
            String id,
            String title,
            String difficulty,
            String status,
            Integer maxSelectCount,
            Integer selectedCount,
            List<String> tags,
            int attachmentCount,
            OffsetDateTime publishedAt,
            OffsetDateTime updatedAt) {
    }

    /** TopicSelectView 卡片：id/title/summary/difficulty/tags/selected/max。 */
    public record StudentTopicVO(
            String id,
            String title,
            String summary,
            String difficulty,
            List<String> tags,
            Integer maxSelectCount,
            Integer selectedCount,
            Integer remaining,
            boolean selectedByMe) {
    }

    public record StudentTopicDetailVO(
            String id,
            String title,
            String description,
            String requirements,
            String difficulty,
            List<String> tags,
            Integer maxSelectCount,
            Integer selectedCount,
            Integer remaining,
            boolean selectedByMe,
            List<AttachmentVO> attachments) {
    }
}
