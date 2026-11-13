package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 批量发起答辩的单条结果：成功拿 sessionId，跳过 / 失败拿原因。
 */
@Data
@TableName("defense_batch_item")
public class DefenseBatchItem {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long batchId;

    private Long selectionId;

    private Long studentId;

    /** SUCCEEDED / SKIPPED / FAILED */
    private String status;

    private Long sessionId;

    /** 本次生成题数（不含加分题） */
    private Integer questionCount;

    private String reason;

    private LocalDateTime createdAt;
}
