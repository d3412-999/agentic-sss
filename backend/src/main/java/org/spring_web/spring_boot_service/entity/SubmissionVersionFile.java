package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 提交版本内的文件清单。
 *
 * <p>一个 {@code submission_version} 对应 1~9 个文件。文件挂在版本上而不是提交物上，
 * 是为了让历史版本的多文件组合能被完整还原——「每类仅显示最新版本」只是展示策略，
 * 不等于旧版本的文件清单可以丢。</p>
 */
@Data
@TableName("submission_version_file")
public class SubmissionVersionFile {

    /** 主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 提交版本 ID（submission_version.id）。 */
    private Long versionId;

    /** 冗余的提交物 ID，按提交物聚合时免联表。 */
    private Long submissionId;

    /** 冗余的选题 ID，跨版本/跨学生相似度比对用。 */
    private Long selectionId;

    /** 类别：SOURCE / REPORT。与所属提交物的 type 一致。 */
    private String fileType;

    /** 文件对象 ID（file_object.id）。 */
    private Long fileId;

    /** 原始文件名（含扩展名），仅用于展示与下载名，不参与路径拼接。 */
    private String originalName;

    /** 字节数。 */
    private Long sizeBytes;

    /** 内容 SHA-256，用于秒传去重与「完全相同」检测。 */
    private String sha256;

    /** 结构指纹：源码包内条目名排序后的 SHA-256，用于「改了内容但结构没变」的近似检测。 */
    private String structureKey;

    /**
     * 内容指纹：归一化源码（去注释、去空白、统一小写）的 MinHash 签名，逗号分隔 128 维。
     *
     * <p>补的正是 sha256 与 structureKey 之间的盲区——「结构动了、名字改了、注释重写，
     * 但代码主体还是一样」。为空表示该文件未计算（超过大小上限或非源码类）。</p>
     */
    private String contentSig;

    /** 同版本内的顺序，从 0 开始；排序结果同时决定评审时的文件优先级。 */
    private Integer sortIndex;

    /** 上传人。 */
    private Long createdBy;

    /** created_at。 */
    private LocalDateTime createdAt;

    /** updated_at。 */
    private LocalDateTime updatedAt;

    /** deleted。 */
    @TableLogic
    private Integer deleted;
}
