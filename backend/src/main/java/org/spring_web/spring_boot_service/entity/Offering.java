package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 开课。
 */
@Data
@TableName("offering")
public class Offering {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 课程 ID
     */
    private Long courseId;

    /**
     * 任课教师 ID
     */
    private Long teacherId;

    /**
     * 学期，如 2026-2027-1
     */
    private String semester;

    /**
     * 教学班
     */
    private String className;

    /**
     * 开课说明
     */
    private String description;

    /**
     * 状态：DRAFT / PUBLISHED / CLOSED / ARCHIVED
     */
    private String status;

    /**
     * 每人最多可选题目数
     */
    private Integer topicSelectLimit;

    /**
     * 答辩成绩权重（%）
     */
    private Integer weightDefense;

    /**
     * 源码成绩权重（%）
     */
    private Integer weightCode;

    /**
     * 报告成绩权重（%）
     */
    private Integer weightReport;

    /**
     * 答辩题目数量；null = 不限（发起答辩时按需指定）。
     */
    private Integer questionCount;

    /**
     * 单题作答时限（秒）
     */
    private Integer maxAnswerSeconds;

    /**
     * 是否允许跳过/重答：0 否 / 1 是
     */
    private Integer allowRetry;

    /**
     * 是否录制视频：0 否 / 1 是
     */
    private Integer enableVideo;

    /**
     * 答辩语言
     */
    private String language;

    /**
     * 选题开始时间
     */
    private LocalDateTime selectStart;

    /**
     * 选题结束时间
     */
    private LocalDateTime selectEnd;

    /**
     * 材料提交截止时间
     */
    private LocalDateTime submitDeadline;

    /**
     * 答辩开始时间
     */
    private LocalDateTime defenseStart;

    /**
     * 答辩结束时间
     */
    private LocalDateTime defenseEnd;

    /**
     * 发布时间
     */
    private LocalDateTime publishedAt;

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
