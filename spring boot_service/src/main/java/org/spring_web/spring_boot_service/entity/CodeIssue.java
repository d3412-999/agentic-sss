package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 源码问题明细。
 */
@Data
@TableName("code_issue")
public class CodeIssue {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 分析结果 ID
     */
    private Long analysisId;

    /**
     * 工具：CHECKSTYLE / PMD / SPOTBUGS / AI
     */
    private String tool;

    /**
     * 规则名
     */
    private String ruleName;

    /**
     * 严重度：HIGH / MEDIUM / LOW / ERROR / WARNING / INFO
     */
    private String severity;

    /**
     * 问题描述
     */
    private String message;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 行号
     */
    private Integer lineNo;

    /**
     * 列号
     */
    private Integer columnNo;

    /**
     * 代码片段
     */
    private String snippet;

    /**
     * 是否被教师忽略
     */
    private Integer ignored;

    /**
     * 忽略原因
     */
    private String ignoreReason;

    /**
     * created_at
     */
    private LocalDateTime createdAt;

}
