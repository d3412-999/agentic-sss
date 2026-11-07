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
 * 答辩会话。
 */
@Data
@TableName("defense_session")
public class DefenseSession {

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
     * 学生 ID
     */
    private Long studentId;

    /**
     * 组织方式：PER_STUDENT / PER_TOPIC
     */
    private String mode;

    /**
     * 状态：CREATED / GENERATING / READY / IN_PROGRESS / COMPLETED / EVALUATED / EXPIRED
     */
    private String status;

    /**
     * 覆盖的选题 ID 数组
     */
    private String selectionIds;

    /**
     * 题目数量
     */
    private Integer questionCount;

    /**
     * 答辩总分
     */
    private BigDecimal score;

    /**
     * 等级
     */
    private String grade;

    /**
     * 答辩总评
     */
    private String summary;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间
     */
    private LocalDateTime finishedAt;

    /**
     * 评分完成时间
     */
    private LocalDateTime evaluatedAt;

    /**
     * 答辩时长（毫秒）
     */
    private Long durationMs;

    /**
     * 教师重置次数
     */
    private Integer resetCount;

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
