package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 异步任务。
 */
@Data
@TableName("async_task")
public class AsyncTask {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 任务类型：PARSE_SOURCE / PARSE_REPORT / CODE_ANALYSIS / REPORT_EVALUATION / DEFENSE_QUESTION_GEN / DEFENSE_EVALUATION / GRADE_EXPORT / GRADE_RECALC
     */
    private String type;

    /**
     * 状态：PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED
     */
    private String status;

    /**
     * 进度 0-100
     */
    private Integer progress;

    /**
     * 业务类型
     */
    private String bizType;

    /**
     * 业务 ID
     */
    private Long bizId;

    /**
     * 发起人
     */
    private Long operatorId;

    /**
     * 任务入参
     */
    private String payload;

    /**
     * 任务结果
     */
    private String result;

    /**
     * 失败原因
     */
    private String errorMessage;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间
     */
    private LocalDateTime finishedAt;

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
