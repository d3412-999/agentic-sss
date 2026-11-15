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
     * 混合评审行级问题（JSON 数组：RULE/TOOL/LLM 合并后的 findings）
     */
    private String findings;

    /**
     * 确定性扫描汇总（JSON：计数 / score_cap / verdict）
     */
    private String ruleSummary;

    /**
     * 评审结论：PASS / WARN / BLOCK
     */
    private String verdict;

    /**
     * 工具执行异常
     */
    private String toolErrors;

    /**
     * 面向学生的失败原因（不含堆栈）；成功时为 null
     */
    private String errorMessage;

    /**
     * 重新触发次数
     */
    private Integer retryCount;

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
