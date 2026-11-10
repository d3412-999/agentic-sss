package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 题目附件。
 */
@Data
@TableName("topic_attachment")
public class TopicAttachment {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 题目 ID
     */
    private Long topicId;

    /**
     * 文件 ID
     */
    private Long fileId;

    /**
     * 排序号
     */
    private Integer sortNo;

    /**
     * created_at
     */
    private LocalDateTime createdAt;

}
