package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 答辩会话。
 */
@Data
@TableName("defense_session")
public class DefenseSession {

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
     * 组织方式：PER_STUDENT / PER_TOPIC
     */
    private String mode;

    /**
     * 状态：CREATED / GENERATING / READY / IN_PROGRESS / COMPLETED / EVALUATED / EXPIRED
     */
    private String status;

    /**
     * 覆盖的选题 ID 数组
     */
    private String selectionIds;

    /**
     * 题目数量
     */
    private Integer questionCount;

    /**
     * 答辩总分
     */
    private BigDecimal score;

    /**
     * 等级
     */
    private String grade;

    /**
     * 答辩总评
     */
    private String summary;

    /**
     * 最近一次在场检测：1 有人 / 0 无人（null 表示尚未检测）
     */
    private Integer facePresent;

    /**
     * 最近一次在场检测时间
     */
    private LocalDateTime faceCheckedAt;

    /**
     * 累计未检测到人的次数（只增不减，供教师回看）
     */
    private Integer faceMissCount;

    /**
     * 答辩录像文件 ID（file_object.id；null 表示未上传录像）
     */
    private Long videoFileId;

    /**
     * 录像时长（毫秒）
     */
    private Long videoDurationMs;

    /**
     * 录像文件大小（字节）
     */
    private Long videoSizeBytes;

    /**
     * 录像上传时间
     */
    private LocalDateTime videoUploadedAt;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间
     */
    private LocalDateTime finishedAt;

    /**
     * 评分完成时间
     */
    private LocalDateTime evaluatedAt;

    /**
     * 答辩时长（毫秒）
     */
    private Long durationMs;

    /**
     * 教师重置次数
     */
    private Integer resetCount;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;

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
