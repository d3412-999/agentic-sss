package org.spring_web.spring_boot_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 题目契约，对应开课工作台的「题目」页签与学生选题页。
 *
 * <p>题目只保留标题、描述、难度、附件四个业务字段：难度用稳定编码（EASY/MEDIUM/HARD），
 * 展示名由后端字典下发；附件是 file_object 的引用，先上传拿 fileId 再挂到题目上。</p>
 */
public final class TopicDtos {

    private TopicDtos() {
    }

    /** 新建：标题、描述、难度、附件，仅此四项。 */
    public record TopicCreateRequest(
            @NotBlank(message = "标题不能为空") @Size(min = 2, max = 100, message = "标题长度需在 2~100 字之间") String title,
            @NotBlank(message = "题目描述不能为空") @Size(max = 5000, message = "题目描述不能超过 5000 字") String description,
            String difficulty,
            List<String> attachments) {
    }

    /** 编辑：只改可改字段；状态、容量等概念已从模型中移除。 */
    public record TopicUpdateRequest(
            @NotNull(message = "version 不能为空") Integer version,
            @Size(min = 2, max = 100, message = "标题长度需在 2~100 字之间") String title,
            @Size(max = 5000, message = "题目描述不能超过 5000 字") String description,
            String difficulty) {
    }

    /** 附件挂载 / 排序：fileIds 的顺序即展示顺序。 */
    public record AttachmentIdsRequest(
            @NotEmpty(message = "至少选择一个附件") List<String> fileIds) {
    }

    /** 附件视图：downloadUrl 走鉴权下载，contentType 供前端决定「内嵌预览」还是「仅下载」。 */
    public record AttachmentVO(
            String fileId,
            String filename,
            String contentType,
            long size,
            String downloadUrl) {
    }

    public record TopicVO(
            String id,
            String offeringId,
            String title,
            String description,
            String difficulty,
            String difficultyLabel,
            Integer selectedCount,
            Integer attachmentCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Integer version,
            List<AttachmentVO> attachments) {
    }

    public record TopicListItemVO(
            String id,
            String title,
            String difficulty,
            String difficultyLabel,
            Integer selectedCount,
            int attachmentCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** 学生选题卡片：不再有容量与剩余名额，只保留「已选人数」这一参考值。 */
    public record StudentTopicVO(
            String id,
            String title,
            String summary,
            String difficulty,
            String difficultyLabel,
            Integer selectedCount,
            int attachmentCount,
            boolean selectedByMe) {
    }

    public record StudentTopicDetailVO(
            String id,
            String title,
            String description,
            String difficulty,
            String difficultyLabel,
            Integer selectedCount,
            boolean selectedByMe,
            List<AttachmentVO> attachments) {
    }

    /** 难度字典项，供前端渲染筛选与下拉。 */
    public record DifficultyVO(String code, String label, int sort) {
    }
}
