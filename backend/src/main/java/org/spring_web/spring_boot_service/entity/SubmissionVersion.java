package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 提交物版本。
 */
@Data
@TableName("submission_version")
public class SubmissionVersion {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 提交物 ID
     */
    private Long submissionId;

    /**
     * 版本号，从 1 开始
     */
    private Integer version;

    /**
     * 文件 ID
     */
    private Long fileId;

    /**
     * 状态：UPLOADED / PARSING / VALID / INVALID / DELETED
     */
    private String status;

    /**
     * 解析结果（文件数、行数、构建方式等）
     */
    private String parseResult;

    /**
     * 报告页数
     */
    private Integer pageCount;

    /**
     * Java 文件数
     */
    private Integer javaFileCount;

    /**
     * 有效代码行数
     */
    private Integer codeLines;

    /**
     * 构建方式：MAVEN / GRADLE / PLAIN
     */
    private String buildTool;

    /**
     * 编译是否通过：0 否 / 1 是 / NULL 未检测
     */
    private Integer compilePassed;

    /**
     * 版本说明
     */
    private String remark;

    /**
     * 上传人
     */
    private Long createdBy;

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
