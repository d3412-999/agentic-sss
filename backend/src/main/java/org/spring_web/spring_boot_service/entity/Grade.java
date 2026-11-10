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
 * 学生成绩。
 */
@Data
@TableName("grade")
public class Grade {

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
     * 答辩成绩
     */
    private BigDecimal defenseScore;

    /**
     * 源码成绩
     */
    private BigDecimal codeScore;

    /**
     * 报告成绩
     */
    private BigDecimal reportScore;

    /**
     * 最终成绩
     */
    private BigDecimal finalScore;

    /**
     * 等级：优秀 / 良好 / 中等 / 及格 / 不及格
     */
    private String level;

    /**
     * 状态：AUTO / ADJUSTED / LOCKED
     */
    private String status;

    /**
     * 是否被教师调整
     */
    private Integer adjusted;

    /**
     * 教师评语
     */
    private String teacherComment;

    /**
     * 成绩发布时间
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
