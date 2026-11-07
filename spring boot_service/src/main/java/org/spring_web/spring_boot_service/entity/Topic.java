package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 课程设计题目。
 */
@Data
@TableName("topic")
public class Topic {

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
     * 题目名称
     */
    private String title;

    /**
     * 题目描述（Markdown）
     */
    private String description;

    /**
     * 具体要求（Markdown）
     */
    private String requirements;

    /**
     * 难度：EASY / MEDIUM / HARD
     */
    private String difficulty;

    /**
     * 人数上限
     */
    private Integer maxSelectCount;

    /**
     * 已选人数（容量预占）
     */
    private Integer selectedCount;

    /**
     * 标签数组
     */
    private String tags;

    /**
     * 期望技术栈数组
     */
    private String expectedTech;

    /**
     * 状态：DRAFT / PUBLISHED / ARCHIVED
     */
    private String status;

    /**
     * 发布时间
     */
    private LocalDateTime publishedAt;

    /**
     * 创建人
     */
    private Long createdBy;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;

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
