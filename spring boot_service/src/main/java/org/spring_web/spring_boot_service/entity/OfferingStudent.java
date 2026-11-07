package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 开课学生名单。
 */
@Data
@TableName("offering_student")
public class OfferingStudent {

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
     * 状态：ACTIVE / DISABLED
     */
    private String status;

    /**
     * 加入时间
     */
    private LocalDateTime joinedAt;

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
