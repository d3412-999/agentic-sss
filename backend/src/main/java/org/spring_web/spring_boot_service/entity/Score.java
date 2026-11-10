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
 * 评分明细。
 */
@Data
@TableName("score")
public class Score {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务类型：DEFENSE / DEFENSE_QUESTION / REPORT / CODE
     */
    private String bizType;

    /**
     * 业务 ID
     */
    private Long bizId;

    /**
     * 维度或题目键
     */
    private String itemKey;

    /**
     * 维度或题目名称
     */
    private String itemName;

    /**
     * 权重（百分制维度用）
     */
    private BigDecimal weight;

    /**
     * 得分
     */
    private BigDecimal score;

    /**
     * 评语
     */
    private String comment;

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
