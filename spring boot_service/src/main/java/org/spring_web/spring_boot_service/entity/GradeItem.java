package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 成绩明细。
 */
@Data
@TableName("grade_item")
public class GradeItem {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 成绩 ID
     */
    private Long gradeId;

    /**
     * 选题 ID
     */
    private Long selectionId;

    /**
     * 题目名称快照
     */
    private String topicTitle;

    /**
     * 答辩分
     */
    private BigDecimal defenseScore;

    /**
     * 源码分
     */
    private BigDecimal codeScore;

    /**
     * 报告分
     */
    private BigDecimal reportScore;

    /**
     * 小计
     */
    private BigDecimal finalScore;

    /**
     * 计算时的权重快照
     */
    private String weights;

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
