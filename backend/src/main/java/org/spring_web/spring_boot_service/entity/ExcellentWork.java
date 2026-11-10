package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 优秀作品模板库条目。
 *
 * <p>教师从某次开课中挑选认为优秀的作品，把报告正文索引到 AI 服务的模板库，
 * 后续报告评审可以把这些作品作为 RAG 参考。这里的 content_text 是索引时的正文快照，
 * 避免原文件被替换后模板内容漂移。</p>
 */
@Data
@TableName("excellent_work")
public class ExcellentWork {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 开课 ID
     */
    private Long offeringId;

    /**
     * 选题 ID
     */
    private Long selectionId;

    /**
     * 报告提交物 ID
     */
    private Long submissionId;

    /**
     * 作品标题
     */
    private String title;

    /**
     * 学生姓名（快照）
     */
    private String studentName;

    /**
     * 学号（快照）
     */
    private String studentNo;

    /**
     * 题目名称（快照）
     */
    private String topicTitle;

    /**
     * 教师推荐理由
     */
    private String note;

    /**
     * 标签，逗号分隔
     */
    private String tags;

    /**
     * 用于模板库索引的正文快照
     */
    private String contentText;

    /**
     * 模板库索引状态：PENDING / INDEXED / FAILED
     */
    private String indexStatus;

    /**
     * 索引方式：local / lightrag+local
     */
    private String indexMethod;

    /**
     * 外部模板库文档 ID
     */
    private String externalDocId;

    /**
     * 索引失败原因
     */
    private String errorMessage;

    /**
     * 操作教师 ID
     */
    private Long createdBy;

    /**
     * created_at
     */
    private LocalDateTime createdAt;

    /**
     * updated_at
     */
    private LocalDateTime updatedAt;

    /**
     * deleted
     */
    @TableLogic
    private Integer deleted;
}
