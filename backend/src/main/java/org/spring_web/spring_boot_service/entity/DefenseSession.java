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
     * 题目数量（不含加分题）
     */
    private Integer questionCount;

    /**
     * 发起时的源码评分快照：题数档位由它决定，之后源码重评不回写
     */
    private BigDecimal codeScoreSnapshot;

    /**
     * 题数档位：T0 / T1 / T2 / T3 / T4
     */
    private String questionRuleTier;

    /**
     * 难度配比快照，如 {@code EASY:3,MEDIUM:2,HARD:1}
     */
    private String difficultyMix;

    /**
     * 教师手动指定题数时的理由；非空即视为覆盖了分档规则
     */
    private String questionOverrideReason;

    /**
     * 加分题数量（不计入 questionCount）
     */
    private Integer bonusQuestionCount;

    /**
     * 三原则判定：1 完全依赖 AI / 0 未判定为依赖 / null 未判定（历史数据）
     */
    private Integer aiDependent;

    /**
     * P1/P2/P3 逐项结论 JSON 快照
     */
    private String principleResult;

    /**
     * 教师是否覆盖过判定
     */
    private Integer aiDependentOverridden;

    /**
     * 覆盖理由（仅留痕）
     */
    private String overrideReason;

    /**
     * 覆盖人 ID
     */
    private Long overrideBy;

    /**
     * 覆盖时间
     */
    private LocalDateTime overrideAt;

    /**
     * 加分题结果：CORRECT / WRONG / UNANSWERED
     */
    private String bonusResult;

    /**
     * 加分题得分（仅供展示，不进总分）
     */
    private BigDecimal bonusScore;

    /**
     * 加分题 AI 简评（不进总分）
     */
    private String bonusComment;

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
