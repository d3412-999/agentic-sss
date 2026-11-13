package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 提交文件相似度命中记录。
 *
 * <p>只记录「达到阈值」的命中。全量两两比对的结果量太大，而教师真正关心的
 * 只有「这两份高度雷同」这个事实，以及据此发起的一次人工复核。</p>
 */
@Data
@TableName("submission_similarity")
public class SubmissionSimilarity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 题目 ID：只有同一题目下的提交才有可比性。 */
    private Long topicId;

    /** 类别：SOURCE / REPORT。 */
    private String fileType;

    /** 命中方选题 ID。 */
    private Long selectionId;

    /** 被比对选题 ID。 */
    private Long otherSelectionId;

    /** 命中方提交物 ID。 */
    private Long submissionId;

    /** 被比对提交物 ID。 */
    private Long otherSubmissionId;

    /** 命中方版本文件 ID。 */
    private Long versionFileId;

    /** 被比对版本文件 ID。 */
    private Long otherVersionFileId;

    /** 命中方文件名。 */
    private String originalName;

    /** 被比对文件名。 */
    private String otherOriginalName;

    /** 相似度 0.000~1.000。 */
    private BigDecimal score;

    /** IDENTICAL（内容相同）/ SAME_STRUCTURE（结构相同）。 */
    private String kind;

    /** created_at。 */
    private LocalDateTime createdAt;
}
