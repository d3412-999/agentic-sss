package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 审计日志。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 操作人
     */
    private Long actorId;

    /**
     * 操作人角色
     */
    private String actorRole;

    /**
     * 动作：GRADE_ADJUST / DEFENSE_RESET / GRADE_PUBLISH / STUDENT_IMPORT ...
     */
    private String action;

    /**
     * 对象类型
     */
    private String targetType;

    /**
     * 对象 ID
     */
    private Long targetId;

    /**
     * 变更前快照
     */
    private String beforeJson;

    /**
     * 变更后快照
     */
    private String afterJson;

    /**
     * 原因（改分等必须填写）
     */
    private String reason;

    /**
     * 客户端 IP
     */
    private String ip;

    /**
     * User-Agent
     */
    private String userAgent;

    /**
     * created_at
     */
    private LocalDateTime createdAt;

}
