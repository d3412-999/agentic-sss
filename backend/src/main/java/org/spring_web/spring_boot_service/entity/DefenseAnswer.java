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
 * 答辩作答。
 */
@Data
@TableName("defense_answer")
public class DefenseAnswer {

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
     * 答辩题目 ID
     */
    private Long questionId;

    /**
     * 音频文件 ID
     */
    private Long audioFileId;

    /**
     * 作答时长（毫秒）
     */
    private Long durationMs;

    /**
     * 转写文本
     */
    private String transcript;

    /**
     * ASR 平均置信度
     */
    private BigDecimal asrConfidence;

    /**
     * 转写模型（如 paraformer-zh，便于排障与结果追溯）
     */
    private String asrModel;

    /**
     * 单题得分 0~100（AI 评分后回填）
     */
    private BigDecimal score;

    /**
     * 该维度是否通过（服务端按阈值判定：1 通过 / 0 不通过）
     */
    private Integer passed;

    /**
     * 判定证据：转写片段或要点，供复核与申诉
     */
    private String evidence;

    /**
     * AI 简评
     */
    private String comment;

    /**
     * 学生主动跳过（加分题可跳过）
     */
    private Integer skipped;

    /**
     * 状态：UPLOADED / TRANSCRIBING / TRANSCRIBED / FAILED
     */
    private String status;

    /**
     * 作答时间
     */
    private LocalDateTime answeredAt;

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
