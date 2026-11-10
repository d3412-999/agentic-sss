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
 * 源码分析结果。
 */
@Data
@TableName("code_analysis")
public class CodeAnalysis {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 源码提交物 ID
     */
    private Long submissionId;

    /**
     * 状态：PENDING / RUNNING / SUCCEEDED / FAILED
     */
    private String status;

    /**
     * 总分（百分制）
     */
    private BigDecimal score;

    /**
     * 等级
     */
    private String grade;

    /**
     * 编译是否通过
     */
    private Integer compilePassed;

    /**
     * 总体评价
     */
    private String summary;

    /**
     * 度量指标（复杂度、重复率、注释率等）
     */
    private String metrics;

    /**
     * 工具执行异常
     */
    private String toolErrors;

    /**
     * AI 评审模型
     */
    private String model;

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
