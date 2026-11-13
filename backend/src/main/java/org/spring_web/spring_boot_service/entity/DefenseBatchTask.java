package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 批量发起答辩任务（一次请求一条）。
 *
 * <p>逐条结果落在 {@link DefenseBatchItem}：批量是「部分成功」语义，
 * 不能把整批塞进一个事务里——某个学生 AI 出题失败不该让其余 9 个人都回滚。</p>
 */
@Data
@TableName("defense_batch_task")
public class DefenseBatchTask {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long offeringId;

    private Long operatorId;

    /** RUNNING / DONE / PARTIAL / FAILED */
    private String status;

    private Integer total;

    private Integer succeeded;

    private Integer skipped;

    private Integer failed;

    /** 本次批量参数（并发数等） */
    private String config;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
