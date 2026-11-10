package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.dto.TopicDtos;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.entity.OfferingStudent;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Topic;
import org.spring_web.spring_boot_service.entity.TopicAttachment;
import org.spring_web.spring_boot_service.mapper.FileObjectMapper;
import org.spring_web.spring_boot_service.mapper.OfferingStudentMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.TopicAttachmentMapper;
import org.spring_web.spring_boot_service.mapper.TopicMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 题目管理（教师）与题目浏览（学生）。
 *
 * <p>{@code selected_count} 是容量预占字段，所有增减都由 SelectionService 用原子 SQL 完成，
 * 这里只读不写，避免出现「两个服务各自改计数」导致超卖。</p>
 */
@Service
@RequiredArgsConstructor
public class TopicService {

    private final TopicMapper topicMapper;
    private final TopicAttachmentMapper attachmentMapper;
    private final FileObjectMapper fileObjectMapper;
    private final SelectionMapper selectionMapper;
    private final OfferingStudentMapper offeringStudentMapper;
    private final OfferingService offeringService;
    private final AuditLogService auditLog;
    private final JsonCodec json;

    // ---------------------------------------------------------------- 教师端

    public PageResult<TopicDtos.TopicListItemVO> teacherTopics(
            String offeringId, String status, String keyword, long page, long size) {
        Long id = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        Page<Topic> result = topicMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<Topic>()
                        .eq(Topic::getOfferingId, id)
                        .eq(StringUtils.hasText(status), Topic::getStatus, status)
                        .like(StringUtils.hasText(keyword), Topic::getTitle, keyword)
                        .orderByDesc(Topic::getId));
        return PageResult.of(result.getRecords().stream().map(this::listItemVo).toList(),
                result.getTotal(), page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public TopicDtos.TopicVO create(String offeringId, TopicDtos.TopicCreateRequest request) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        Long duplicate = topicMapper.selectCount(new LambdaQueryWrapper<Topic>()
                .eq(Topic::getOfferingId, oid).eq(Topic::getTitle, request.title()));
        BizAssert.state(duplicate == null || duplicate == 0, "同一开课内题目名称不能重复");

        Topic topic = new Topic();
        topic.setOfferingId(oid);
        topic.setTitle(request.title());
        topic.setDescription(request.description());
        topic.setRequirements(request.requirements());
        topic.setDifficulty(StringUtils.hasText(request.difficulty()) ? request.difficulty() : "MEDIUM");
        topic.setMaxSelectCount(request.maxSelectCount() == null ? 1 : request.maxSelectCount());
        topic.setSelectedCount(0);
        topic.setTags(json.write(request.tags()));
        topic.setExpectedTech(json.write(request.expectedTech()));
        topic.setStatus(StringUtils.hasText(request.status()) ? request.status() : "DRAFT");
        if ("PUBLISHED".equals(topic.getStatus())) {
            validatePublishable(topic);
            topic.setPublishedAt(Times.now());
        }
        topic.setCreatedBy(SecurityUtils.userId());
        topic.setVersion(0);
        topic.setCreatedAt(Times.now());
        topic.setUpdatedAt(Times.now());
        topicMapper.insert(topic);

        saveAttachments(topic.getId(), request.attachments());
        auditLog.record("TOPIC_CREATE", "TOPIC", topic.getId(), null, toVo(topic), null);
        return toVo(topic);
    }

    /** 教师本人，或已选该题的学生可读。 */
    public TopicDtos.TopicVO detail(String topicId) {
        Topic topic = load(topicId);
        Long uid = SecurityUtils.userId();
        boolean owner = offeringService.isOwner(topic.getOfferingId(), uid);
        if (!owner) {
            Long selected = selectionMapper.selectCount(new LambdaQueryWrapper<Selection>()
                    .eq(Selection::getTopicId, topic.getId())
                    .eq(Selection::getStudentId, uid)
                    .ne(Selection::getStatus, "CANCELED"));
            BizAssert.forbidden(selected != null && selected > 0, "题目");
        }
        return toVo(topic);
    }

    @Transactional(rollbackFor = Exception.class)
    public TopicDtos.TopicVO update(String topicId, TopicDtos.TopicUpdateRequest request) {
        Topic topic = owned(topicId);
        BizAssert.state(request.version().equals(topic.getVersion()), "题目已被他人修改，请刷新后重试");
        TopicDtos.TopicVO before = toVo(topic);
        boolean hasSelection = topic.getSelectedCount() != null && topic.getSelectedCount() > 0;

        if (request.title() != null) {
            BizAssert.state(!hasSelection || request.title().equals(topic.getTitle()),
                    "已有学生选择该题，不能修改题目名称");
            Long duplicate = topicMapper.selectCount(new LambdaQueryWrapper<Topic>()
                    .eq(Topic::getOfferingId, topic.getOfferingId())
                    .eq(Topic::getTitle, request.title())
                    .ne(Topic::getId, topic.getId()));
            BizAssert.state(duplicate == null || duplicate == 0, "同一开课内题目名称不能重复");
            topic.setTitle(request.title());
        }
        if (request.maxSelectCount() != null) {
            BizAssert.state(!hasSelection || request.maxSelectCount() >= topic.getSelectedCount(),
                    "人数上限不能低于已选人数");
            topic.setMaxSelectCount(request.maxSelectCount());
        }
        if (request.description() != null) {
            topic.setDescription(request.description());
        }
        if (request.requirements() != null) {
            topic.setRequirements(request.requirements());
        }
        if (request.difficulty() != null) {
            topic.setDifficulty(request.difficulty());
        }
        if (request.tags() != null) {
            topic.setTags(json.write(request.tags()));
        }
        if (request.expectedTech() != null) {
            topic.setExpectedTech(json.write(request.expectedTech()));
        }
        // 状态只能走 publish / archive，避免绕过「发布前置校验」直接改状态
        BizAssert.param(request.status() == null || request.status().equals(topic.getStatus()),
                "题目状态请使用发布 / 归档接口变更");
        topic.setVersion(request.version());
        topic.setUpdatedAt(Times.now());
        if (topicMapper.updateById(topic) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "题目已被他人修改，请刷新后重试");
        }
        auditLog.record("TOPIC_UPDATE", "TOPIC", topic.getId(), before, toVo(topic), null);
        return toVo(topic);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String topicId) {
        Topic topic = owned(topicId);
        Long used = selectionMapper.selectCount(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getTopicId, topic.getId())
                .ne(Selection::getStatus, "CANCELED"));
        BizAssert.rule(used == null || used == 0, "已有学生选择该题，请先联系学生退选或改为归档");
        topicMapper.deleteById(topic.getId());
        auditLog.record("TOPIC_DELETE", "TOPIC", topic.getId(), toVo(topic), null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public TopicDtos.TopicVO publish(String topicId) {
        Topic topic = owned(topicId);
        BizAssert.state(!"ARCHIVED".equals(topic.getStatus()), "已归档题目不能发布");
        validatePublishable(topic);
        topic.setStatus("PUBLISHED");
        topic.setPublishedAt(Times.now());
        topic.setUpdatedAt(Times.now());
        if (topicMapper.updateById(topic) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "题目状态已变更，请刷新后重试");
        }
        return toVo(topic);
    }

    @Transactional(rollbackFor = Exception.class)
    public TopicDtos.PublishBatchResult publishBatch(String offeringId, TopicDtos.PublishBatchRequest request) {
        offeringService.ownedOffering(offeringId);
        List<TopicDtos.Failed> failed = new ArrayList<>();
        int published = 0;
        for (String rawId : request.topicIds()) {
            Topic topic;
            try {
                topic = topicMapper.selectById(BizAssert.id(rawId, "topicId"));
            } catch (BizException ex) {
                failed.add(new TopicDtos.Failed(rawId, ex.getMessage()));
                continue;
            }
            if (topic == null || !topic.getOfferingId().equals(BizAssert.id(offeringId, "offeringId"))) {
                failed.add(new TopicDtos.Failed(rawId, "题目不存在或不属于该开课"));
                continue;
            }
            if (!StringUtils.hasText(topic.getDescription())
                    || topic.getMaxSelectCount() == null || topic.getMaxSelectCount() < 1) {
                failed.add(new TopicDtos.Failed(rawId, "描述为空或人数上限小于 1"));
                continue;
            }
            topic.setStatus("PUBLISHED");
            topic.setPublishedAt(Times.now());
            topic.setUpdatedAt(Times.now());
            if (topicMapper.updateById(topic) == 0) {
                // 乐观锁冲突：不能再静默丢弃，否则调用方既不算成功也不算失败
                failed.add(new TopicDtos.Failed(rawId, "题目状态已变更，请刷新后重试"));
            } else {
                published++;
            }
        }
        return new TopicDtos.PublishBatchResult(published, failed);
    }

    @Transactional(rollbackFor = Exception.class)
    public TopicDtos.TopicVO archive(String topicId) {
        Topic topic = owned(topicId);
        topic.setStatus("ARCHIVED");
        topic.setUpdatedAt(Times.now());
        if (topicMapper.updateById(topic) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "题目状态已变更，请刷新后重试");
        }
        return toVo(topic);
    }

    // ---------------------------------------------------------------- 学生端

    public PageResult<TopicDtos.StudentTopicVO> studentTopics(
            String offeringId, String keyword, String difficulty, long page, long size) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        assertInRoster(oid);
        Page<Topic> result = topicMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<Topic>()
                        .eq(Topic::getOfferingId, oid)
                        .eq(Topic::getStatus, "PUBLISHED")
                        .eq(StringUtils.hasText(difficulty) && !"all".equals(difficulty),
                                Topic::getDifficulty, difficulty)
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(Topic::getTitle, keyword).or().like(Topic::getTags, keyword))
                        .orderByDesc(Topic::getId));
        Long uid = SecurityUtils.userId();
        List<TopicDtos.StudentTopicVO> list = result.getRecords().stream()
                .map(t -> studentTopicVo(t, uid)).toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    public TopicDtos.StudentTopicDetailVO studentTopicDetail(String offeringId, String topicId) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        assertInRoster(oid);
        Topic topic = load(topicId);
        BizAssert.param(topic.getOfferingId().equals(oid), "题目不属于该开课");
        BizAssert.rule("PUBLISHED".equals(topic.getStatus()), "题目未发布");
        TopicDtos.StudentTopicVO base = studentTopicVo(topic, SecurityUtils.userId());
        return new TopicDtos.StudentTopicDetailVO(base.id(), base.title(), topic.getDescription(),
                topic.getRequirements(), base.difficulty(), base.tags(), base.maxSelectCount(),
                base.selectedCount(), base.remaining(), base.selectedByMe(), attachments(topic.getId()));
    }

    // ---------------------------------------------------------------- 内部

    private Topic load(String topicId) {
        return BizAssert.found(topicMapper.selectById(BizAssert.id(topicId, "topicId")), "题目");
    }

    private Topic owned(String topicId) {
        Topic topic = load(topicId);
        offeringService.ownedOffering(String.valueOf(topic.getOfferingId()));
        return topic;
    }

    private void assertInRoster(Long offeringId) {
        Long count = offeringStudentMapper.selectCount(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, offeringId)
                .eq(OfferingStudent::getStudentId, SecurityUtils.userId())
                .eq(OfferingStudent::getStatus, "ACTIVE"));
        BizAssert.forbidden(count != null && count > 0, "开课");
    }

    private void validatePublishable(Topic topic) {
        BizAssert.rule(StringUtils.hasText(topic.getDescription()), "题目描述不能为空");
        BizAssert.rule(topic.getMaxSelectCount() != null && topic.getMaxSelectCount() >= 1,
                "人数上限至少为 1");
    }

    private void saveAttachments(Long topicId, List<String> fileIds) {
        if (fileIds == null) {
            return;
        }
        int sort = 0;
        for (String raw : fileIds) {
            Long fileId = BizAssert.id(raw, "fileId");
            TopicAttachment attachment = new TopicAttachment();
            attachment.setTopicId(topicId);
            attachment.setFileId(fileId);
            attachment.setSortNo(sort++);
            attachment.setCreatedAt(Times.now());
            attachmentMapper.insert(attachment);
        }
    }

    private List<TopicDtos.AttachmentVO> attachments(Long topicId) {
        List<TopicAttachment> links = attachmentMapper.selectList(
                new LambdaQueryWrapper<TopicAttachment>().eq(TopicAttachment::getTopicId, topicId));
        List<TopicDtos.AttachmentVO> list = new ArrayList<>();
        for (TopicAttachment link : links) {
            FileObject file = fileObjectMapper.selectById(link.getFileId());
            if (file != null) {
                list.add(new TopicDtos.AttachmentVO(String.valueOf(file.getId()), file.getFilename(),
                        "/api/v1/files/" + file.getId() + "/download"));
            }
        }
        return list;
    }

    private boolean selectedByMe(Long topicId, Long uid) {
        Long count = selectionMapper.selectCount(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getTopicId, topicId)
                .eq(Selection::getStudentId, uid)
                .ne(Selection::getStatus, "CANCELED"));
        return count != null && count > 0;
    }

    private TopicDtos.StudentTopicVO studentTopicVo(Topic topic, Long uid) {
        int max = topic.getMaxSelectCount() == null ? 0 : topic.getMaxSelectCount();
        int selected = topic.getSelectedCount() == null ? 0 : topic.getSelectedCount();
        String description = topic.getDescription() == null ? "" : topic.getDescription();
        String summary = description.length() > 120 ? description.substring(0, 120) + "…" : description;
        return new TopicDtos.StudentTopicVO(String.valueOf(topic.getId()), topic.getTitle(), summary,
                topic.getDifficulty(), json.stringList(topic.getTags()), max, selected,
                Math.max(0, max - selected), selectedByMe(topic.getId(), uid));
    }

    private TopicDtos.TopicListItemVO listItemVo(Topic topic) {
        Long attachmentCount = attachmentMapper.selectCount(
                new LambdaQueryWrapper<TopicAttachment>().eq(TopicAttachment::getTopicId, topic.getId()));
        return new TopicDtos.TopicListItemVO(String.valueOf(topic.getId()), topic.getTitle(),
                topic.getDifficulty(), topic.getStatus(), topic.getMaxSelectCount(),
                topic.getSelectedCount(), json.stringList(topic.getTags()),
                attachmentCount == null ? 0 : attachmentCount.intValue(),
                Times.offset(topic.getPublishedAt()), Times.offset(topic.getUpdatedAt()));
    }

    private TopicDtos.TopicVO toVo(Topic topic) {
        return new TopicDtos.TopicVO(String.valueOf(topic.getId()),
                String.valueOf(topic.getOfferingId()), topic.getTitle(), topic.getDescription(),
                topic.getRequirements(), topic.getDifficulty(), topic.getMaxSelectCount(),
                topic.getSelectedCount(), json.stringList(topic.getTags()),
                json.stringList(topic.getExpectedTech()), topic.getStatus(),
                Times.offset(topic.getPublishedAt()), Times.offset(topic.getCreatedAt()),
                Times.offset(topic.getUpdatedAt()), topic.getVersion(), attachments(topic.getId()));
    }
}
