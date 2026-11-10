package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文件对象。
 */
@Data
@TableName("file_object")
public class FileObject {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 存储类型：LOCAL / MINIO / OSS
     */
    private String storage;

    /**
     * 存储桶
     */
    private String bucket;

    /**
     * 对象键
     */
    private String objectKey;

    /**
     * 原始文件名
     */
    private String filename;

    /**
     * MIME 类型
     */
    private String contentType;

    /**
     * 文件大小（字节）
     */
    private Long sizeBytes;

    /**
     * 文件 SHA-256，用于秒传与去重
     */
    private String sha256;

    /**
     * 抽取的正文（报告正文 / 源码摘要）
     */
    private String extractedText;

    /**
     * 报告页数（PDF 可读，DOCX 通常为空）
     */
    private Integer pageCount;

    /**
     * 上传人
     */
    private Long createdBy;

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
