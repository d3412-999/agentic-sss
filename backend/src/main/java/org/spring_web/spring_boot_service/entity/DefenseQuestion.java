package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 答辩题目。
 */
@Data
@TableName("defense_question")
public class DefenseQuestion {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 答辩会话 ID
     */
    private Long defenseId;

    /**
     * 题目序号，从 1 开始
     */
    private Integer orderNo;

    /**
     * 类型：DESIGN / CODE / REPORT / CONCEPT / EXTENSION
     */
    private String type;

    /**
     * 判别维度：P1_RETELL（复述）/ P2_REASON（讲理由）/ P3_MODIFY（现场改）/ IS_BONUS；历史数据为 null
     */
    private String dimension;

    /**
     * 是否加分题：不参与总分，独立字段存结果
     */
    private Integer isBonus;

    /**
     * 难度
     */
    private String difficulty;

    /**
     * 题干
     */
    private String question;

    /**
     * 评分要点（教师端可见）
     */
    private String expectedPoints;

    /**
     * 参考答案（教师端可见）
     */
    private String referenceAnswer;

    /**
     * 题目来源引用（文件行号 / 报告章节 / 问题 ID）
     */
    private String sourceRefs;

    /**
     * 作答时限（秒）
     */
    private Integer maxAnswerSeconds;

    /**
     * created_at
     */
    private LocalDateTime createdAt;

}
