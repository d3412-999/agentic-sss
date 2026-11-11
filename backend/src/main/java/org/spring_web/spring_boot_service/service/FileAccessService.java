package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.entity.OfferingStudent;
import org.spring_web.spring_boot_service.entity.Topic;
import org.spring_web.spring_boot_service.entity.TopicAttachment;
import org.spring_web.spring_boot_service.mapper.OfferingStudentMapper;
import org.spring_web.spring_boot_service.mapper.TopicAttachmentMapper;
import org.spring_web.spring_boot_service.mapper.TopicMapper;
import org.springframework.stereotype.Service;

/**
 * 通用文件下载的数据级鉴权。
 *
 * <p>{@code /api/v1/files/{fileId}/download} 原来的规则是「上传者本人或教师」，
 * 但题目附件是教师上传、由学生下载的：文件 created_by 是教师，学生既不是上传者
 * 也可能不是教师，于是名单内学生从题目详情拿到下载链接后必然 403。</p>
 *
 * <p>这里补充一条最小规则：只要文件被 {@code topic_attachment} 引用，且当前用户
 * 在该题目所属开课的 ACTIVE 名单中，即视为可见。上传者 / 教师的既有规则不变。</p>
 */
@Service
@RequiredArgsConstructor
public class FileAccessService {

    private final TopicAttachmentMapper topicAttachmentMapper;
    private final TopicMapper topicMapper;
    private final OfferingStudentMapper offeringStudentMapper;

    /** 题目附件是否对当前用户可见（当前用户须在题目所属开课的 ACTIVE 名单里）。 */
    public boolean canAccessTopicAttachment(Long fileId, Long userId) {
        if (fileId == null || userId == null) {
            return false;
        }
        List<TopicAttachment> links = topicAttachmentMapper.selectList(
                new LambdaQueryWrapper<TopicAttachment>().eq(TopicAttachment::getFileId, fileId));
        if (links.isEmpty()) {
            return false;
        }
        List<Long> topicIds = links.stream().map(TopicAttachment::getTopicId)
                .filter(Objects::nonNull).distinct().toList();
        if (topicIds.isEmpty()) {
            return false;
        }
        // topic / offering_student 都带逻辑删除，selectBatchIds / selectCount 会自动过滤 deleted = 1
        List<Long> offeringIds = topicMapper.selectBatchIds(topicIds).stream()
                .map(Topic::getOfferingId).filter(Objects::nonNull).distinct().toList();
        if (offeringIds.isEmpty()) {
            return false;
        }
        Long active = offeringStudentMapper.selectCount(new LambdaQueryWrapper<OfferingStudent>()
                .in(OfferingStudent::getOfferingId, offeringIds)
                .eq(OfferingStudent::getStudentId, userId)
                .eq(OfferingStudent::getStatus, "ACTIVE"));
        return active != null && active > 0;
    }
}
