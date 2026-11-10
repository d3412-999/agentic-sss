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
