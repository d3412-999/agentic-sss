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
 *
 * <p>V9 之后题目只保留「标题 / 描述 / 难度 / 附件」四项业务字段：
 * 交付要求、标签、期望技术栈、容量上限、草稿状态都已从模型与表结构中移除，
 * 题目一经创建即对学生可见（不再有草稿/发布/归档状态机）。</p>
 *
 * <p>{@code selected_count} 仍然保留，但语义从「容量预占」变成单纯的「已选人数统计」，
 * 不再参与任何上限判断。</p>
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
     * 难度编码：EASY / MEDIUM / HARD（展示名见 Difficulty 枚举）
     */
    private String difficulty;

    /**
     * 已选人数（仅统计，无上限）
     */
    private Integer selectedCount;

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
