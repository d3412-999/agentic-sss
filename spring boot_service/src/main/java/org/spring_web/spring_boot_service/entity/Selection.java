package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 学生选题。
 */
@Data
@TableName("selection")
public class Selection {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 开课 ID（冗余，便于按开课查询）
     */
    private Long offeringId;

    /**
     * 题目 ID
     */
    private Long topicId;

    /**
     * 学生 ID
     */
    private Long studentId;

    /**
     * 状态：SELECTED / SUBMITTED / DEFENDING / DEFENDED / GRADED / CANCELED
     */
    private String status;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;

    /**
     * 退选时间
     */
    private LocalDateTime canceledAt;

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
