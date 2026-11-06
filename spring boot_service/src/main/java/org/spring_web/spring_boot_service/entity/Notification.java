package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 站内通知。
 */
@Data
@TableName("notification")
public class Notification {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 接收人
     */
    private Long userId;

    /**
     * 类型：SELECTION_OPEN / SUBMIT_DEADLINE / DEFENSE_READY / ANALYSIS_DONE / GRADE_PUBLISHED / TEACHER_MESSAGE
     */
    private String type;

    /**
     * 标题
     */
    private String title;

    /**
     * 正文
     */
    private String content;

    /**
     * 前端跳转路径
     */
    private String link;

    /**
     * 是否已读
     */
    private Integer readFlag;

    /**
     * 阅读时间
     */
    private LocalDateTime readAt;

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
