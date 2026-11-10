package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 提交物（源码 / 报告）。
 */
@Data
@TableName("submission")
public class Submission {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 选题 ID
     */
    private Long selectionId;

    /**
     * 类型：SOURCE / REPORT
     */
    private String type;

    /**
     * 最新版本号
     */
    private Integer latestVersion;

    /**
     * 最新版本记录 ID
     */
    private Long latestVersionId;

    /**
     * 状态：UPLOADED / PARSING / VALID / INVALID / ANALYZING / ANALYZED
     */
    private String status;

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
