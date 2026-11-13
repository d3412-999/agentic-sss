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
 * 报告评审结果。
 */
@Data
@TableName("report_evaluation")
public class ReportEvaluation {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 报告提交物 ID
     */
    private Long submissionId;

    /**
     * 状态：PENDING / RUNNING / SUCCEEDED / FAILED
     */
    private String status;

    /**
     * 总分
     */
    private BigDecimal score;

    /**
     * 等级
     */
    private String grade;

    /**
     * 总体评价
     */
    private String summary;

    /**
     * 亮点
     */
    private String highlights;

    /**
     * 问题与建议
     */
    private String problems;

    /**
     * 报告-源码一致性检查
     */
    private String consistency;

    /**
     * 查重结果
     */
    private String plagiarism;

    /**
     * 页数
     */
    private Integer pageCount;

    /**
     * 评审模型
     */
    private String model;

    /**
     * 面向学生的失败原因（不含堆栈）；成功时为 null
     */
    private String errorMessage;

    /**
     * 重新触发次数
     */
    private Integer retryCount;

    /**
     * 完成时间
     */
    private LocalDateTime finishedAt;

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
