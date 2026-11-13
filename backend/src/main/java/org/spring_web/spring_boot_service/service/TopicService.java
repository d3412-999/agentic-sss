package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.Difficulty;
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
 * <p>V9 之后题目模型只剩「标题 / 描述 / 难度 / 附件」，没有草稿状态、没有容量上限：
 * 创建即对学生可见，{@code selected_count} 退化为纯统计字段。附件以 file_object 引用方式挂载，
 * 文件本身的合法性在 {@link FileTypeValidator} 里校验，本类只负责「谁能挂哪个文件」。</p>
 */
@Service
@RequiredArgsConstructor
public class TopicService {

    private static final String ATTACHMENT_BUCKET = FileUploadService.TOPIC_ATTACHMENT;

    private final TopicMapper topicMapper;
    private final TopicAttachmentMapper attachmentMapper;
    private final FileObjectMapper fileObjectMapper;
    private final FileStorageService fileStorage;
    private final FileTypeValidator fileTypeValidator;
    private final SelectionMapper selectionMapper;
    private final OfferingStudentMapper offeringStudentMapper;
    private final OfferingService offeringService;
    private final AuditLogService auditLog;

    // ---------------------------------------------------------------- 教师端

    public PageResult<TopicDtos.TopicListItemVO> teacherTopics(
            String offeringId, String difficulty, String keyword, long page, long size) {
        Long id = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        String difficultyCode = Difficulty.filterCode(difficulty);
        Page<Topic> result = topicMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<Topic>()
                        .eq(Topic::getOfferingId, id)
                        .eq(difficultyCode != null, Topic::getDifficulty, difficultyCode)
                        .like(StringUtils.hasText(keyword), Topic::getTitle, keyword)
                        .orderByDesc(Topic::getId));
        Map<Long, Long> counts = attachmentCounts(result.getRecords().stream().map(Topic::getId).toList());
        List<TopicDtos.TopicListItemVO> list = result.getRecords().stream()
                .map(topic -> listItemVo(topic, counts.getOrDefault(topic.getId(), 0L).intValue()))
                .toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public TopicDtos.TopicVO create(String offeringId, TopicDtos.TopicCreateRequest request) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        String title = request.title().trim();
        Long duplicate = topicMapper.selectCount(new LambdaQueryWrapper<Topic>()
                .eq(Topic::getOfferingId, oid).eq(Topic::getTitle, title));
        BizAssert.state(duplicate == null || duplicate == 0, "同一开课内题目名称不能重复");

        // 先把附件解析完再落库：附件非法时整体失败，不会留下一个没有附件的半成品题目
        List<FileObject> files = resolveOwnedFiles(request.attachments());
        BizAssert.param(files.size() <= fileTypeValidator.policy().getMaxPerTopic(),
                "单个题目最多 " + fileTypeValidator.policy().getMaxPerTopic() + " 个附件");

        Topic topic = new Topic();
        topic.setOfferingId(oid);
        topic.setTitle(title);
        topic.setDescription(request.description().trim());
        topic.setDifficulty(Difficulty.require(request.difficulty()));
        topic.setSelectedCount(0);
        topic.setCreatedBy(SecurityUtils.userId());
        topic.setVersion(0);
        topic.setCreatedAt(Times.now());
        topic.setUpdatedAt(Times.now());
        topicMapper.insert(topic);

        link(topic.getId(), files, 0);
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

        if (request.title() != null && !request.title().trim().equals(topic.getTitle())) {
            String title = request.title().trim();
            BizAssert.state(!hasSelection, "已有学生选择该题，不能修改题目名称");
            Long duplicate = topicMapper.selectCount(new LambdaQueryWrapper<Topic>()
                    .eq(Topic::getOfferingId, topic.getOfferingId())
                    .eq(Topic::getTitle, title)
                    .ne(Topic::getId, topic.getId()));
            BizAssert.state(duplicate == null || duplicate == 0, "同一开课内题目名称不能重复");
            topic.setTitle(title);
        }
        if (request.description() != null) {
            topic.setDescription(request.description().trim());
        }
        if (StringUtils.hasText(request.difficulty())) {
            topic.setDifficulty(Difficulty.require(request.difficulty()));
        }
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
        BizAssert.rule(used == null || used == 0, "已有学生选择该题，请先联系学生退选");
        // 逻辑删除题目后，附件引用一并解绑，但文件本体保留（可能被其他题目秒传复用）
        attachmentMapper.delete(new LambdaQueryWrapper<TopicAttachment>()
                .eq(TopicAttachment::getTopicId, topic.getId()));
        topicMapper.deleteById(topic.getId());
        auditLog.record("TOPIC_DELETE", "TOPIC", topic.getId(), toVo(topic), null, null);
    }

    // ---------------------------------------------------------------- 附件

    /** 追加附件（编辑时使用）。返回挂载后的完整列表。 */
    @Transactional(rollbackFor = Exception.class)
    public List<TopicDtos.AttachmentVO> addAttachments(String topicId, List<String> fileIds) {
        Topic topic = owned(topicId);
        List<TopicAttachment> existing = links(topic.getId());
        List<FileObject> files = resolveOwnedFiles(fileIds);
        int limit = fileTypeValidator.policy().getMaxPerTopic();
        BizAssert.param(existing.size() + files.size() <= limit,
                "单个题目最多 " + limit + " 个附件，当前已有 " + existing.size() + " 个");

        Set<Long> already = new LinkedHashSet<>(existing.stream().map(TopicAttachment::getFileId).toList());
        List<FileObject> fresh = files.stream().filter(f -> !already.contains(f.getId())).toList();
        link(topic.getId(), fresh, existing.size());
        if (!fresh.isEmpty()) {
            auditLog.record("TOPIC_ATTACHMENT_ADD", "TOPIC", topic.getId(), null,
                    fresh.stream().map(FileObject::getFilename).toList(), null);
        }
        return attachments(topic.getId());
    }

    /** 解绑附件；该文件不再被任何题目引用时，连同磁盘文件一起清理。 */
    @Transactional(rollbackFor = Exception.class)
    public List<TopicDtos.AttachmentVO> removeAttachment(String topicId, String fileId) {
        Topic topic = owned(topicId);
        Long fid = BizAssert.id(fileId, "fileId");
        int removed = attachmentMapper.delete(new LambdaQueryWrapper<TopicAttachment>()
                .eq(TopicAttachment::getTopicId, topic.getId())
                .eq(TopicAttachment::getFileId, fid));
        BizAssert.state(removed > 0, "附件不存在或已被移除");

        Long refs = attachmentMapper.selectCount(new LambdaQueryWrapper<TopicAttachment>()
                .eq(TopicAttachment::getFileId, fid));
        if (refs == null || refs == 0) {
            FileObject file = fileObjectMapper.selectById(fid);
            if (file != null && ATTACHMENT_BUCKET.equals(file.getBucket())) {
                fileStorage.deleteQuietly(file);
            }
        }
        auditLog.record("TOPIC_ATTACHMENT_REMOVE", "TOPIC", topic.getId(), fid, null, null);
        return attachments(topic.getId());
    }

    /** 重新排序：fileIds 必须与当前附件集合完全一致，顺序即新的 sort_no。 */
    @Transactional(rollbackFor = Exception.class)
    public List<TopicDtos.AttachmentVO> reorderAttachments(String topicId, List<String> fileIds) {
        Topic topic = owned(topicId);
        List<TopicAttachment> existing = links(topic.getId());
        List<Long> wanted = fileIds.stream().map(raw -> BizAssert.id(raw, "fileId")).toList();
        Set<Long> current = new LinkedHashSet<>(existing.stream().map(TopicAttachment::getFileId).toList());
        BizAssert.param(wanted.size() == current.size() && current.containsAll(wanted),
                "附件列表已变更，请刷新后重试");
        int sort = 0;
        for (Long fileId : wanted) {
            for (TopicAttachment link : existing) {
                if (link.getFileId().equals(fileId)) {
                    link.setSortNo(sort++);
                    attachmentMapper.updateById(link);
                    break;
                }
            }
        }
        // 不动 topic.version：排序只影响附件表，若在这里 bump 版本会让正在编辑的教师下一次保存 409
        return attachments(topic.getId());
    }

    // ---------------------------------------------------------------- 学生端

    public PageResult<TopicDtos.StudentTopicVO> studentTopics(
            String offeringId, String keyword, String difficulty, long page, long size) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        assertInRoster(oid);
        String difficultyCode = Difficulty.filterCode(difficulty);
        Page<Topic> result = topicMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<Topic>()
                        .eq(Topic::getOfferingId, oid)
                        .eq(difficultyCode != null, Topic::getDifficulty, difficultyCode)
                        .like(StringUtils.hasText(keyword), Topic::getTitle, keyword)
                        .orderByDesc(Topic::getId));
        List<Long> ids = result.getRecords().stream().map(Topic::getId).toList();
        Map<Long, Long> counts = attachmentCounts(ids);
        Set<Long> mine = myTopicIds(oid, SecurityUtils.userId());
        List<TopicDtos.StudentTopicVO> list = result.getRecords().stream()
                .map(topic -> studentTopicVo(topic, counts.getOrDefault(topic.getId(), 0L).intValue(),
                        mine.contains(topic.getId())))
                .toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    public TopicDtos.StudentTopicDetailVO studentTopicDetail(String offeringId, String topicId) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        assertInRoster(oid);
        Topic topic = load(topicId);
        BizAssert.param(topic.getOfferingId().equals(oid), "题目不属于该开课");
        TopicDtos.StudentTopicVO base = studentTopicVo(topic, attachments(topic.getId()).size(),
                selectedByMe(topic.getId(), SecurityUtils.userId()));
        return new TopicDtos.StudentTopicDetailVO(base.id(), base.title(), topic.getDescription(),
                base.difficulty(), base.difficultyLabel(), base.selectedCount(), base.selectedByMe(),
                attachments(topic.getId()));
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

    /**
     * 把前端传来的 fileId 解析成文件对象，并做三重归属校验：
     * 文件必须存在、必须是「题目附件」用途、必须是当前教师上传的。
     * 少了第三条，教师就能把学生的提交物挂到自己题目上。
     */
    private List<FileObject> resolveOwnedFiles(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        Long uid = SecurityUtils.userId();
        Set<Long> ids = new LinkedHashSet<>();
        for (String raw : fileIds) {
            ids.add(BizAssert.id(raw, "fileId"));
        }
        List<FileObject> files = new ArrayList<>();
        for (Long id : ids) {
            FileObject file = BizAssert.found(fileObjectMapper.selectById(id), "文件");
            BizAssert.param(ATTACHMENT_BUCKET.equals(file.getBucket()), "该文件不是题目附件，请重新上传");
            BizAssert.forbidden(file.getCreatedBy() != null && file.getCreatedBy().equals(uid), "文件");
            files.add(file);
        }
        return files;
    }

    private void link(Long topicId, List<FileObject> files, int startSort) {
        int sort = startSort;
        for (FileObject file : files) {
            TopicAttachment link = new TopicAttachment();
            link.setTopicId(topicId);
            link.setFileId(file.getId());
            link.setSortNo(sort++);
            link.setCreatedAt(Times.now());
            attachmentMapper.insert(link);
        }
    }

    private List<TopicAttachment> links(Long topicId) {
        return attachmentMapper.selectList(new LambdaQueryWrapper<TopicAttachment>()
                .eq(TopicAttachment::getTopicId, topicId)
                .orderByAsc(TopicAttachment::getSortNo)
                .orderByAsc(TopicAttachment::getId));
    }

    private List<TopicDtos.AttachmentVO> attachments(Long topicId) {
        List<TopicDtos.AttachmentVO> list = new ArrayList<>();
        for (TopicAttachment link : links(topicId)) {
            FileObject file = fileObjectMapper.selectById(link.getFileId());
            if (file == null) {
                continue;
            }
            list.add(new TopicDtos.AttachmentVO(String.valueOf(file.getId()), file.getFilename(),
                    file.getContentType(), file.getSizeBytes() == null ? 0 : file.getSizeBytes(),
                    "/api/v1/files/" + file.getId() + "/download"));
        }
        return list;
    }

    private Map<Long, Long> attachmentCounts(List<Long> topicIds) {
        List<Long> ids = topicIds == null ? List.of()
                : topicIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return indexGroupedCount(attachmentMapper.selectMaps(
                new QueryWrapper<TopicAttachment>()
                        .select("topic_id", "count(*) AS c")
                        .in("topic_id", ids)
                        .groupBy("topic_id")), "topic_id");
    }

    private Map<Long, Long> indexGroupedCount(List<Map<String, Object>> rows, String keyColumn) {
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long key = groupedLong(row, keyColumn);
            if (key != null) {
                Long value = groupedLong(row, "c");
                result.put(key, value == null ? 0L : value);
            }
        }
        return result;
    }

    private Long groupedLong(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (column.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() instanceof Number number ? number.longValue() : null;
            }
        }
        return null;
    }

    private Set<Long> myTopicIds(Long offeringId, Long studentId) {
        List<Selection> mine = selectionMapper.selectList(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getOfferingId, offeringId)
                .eq(Selection::getStudentId, studentId)
                .ne(Selection::getStatus, "CANCELED"));
        Set<Long> ids = new LinkedHashSet<>();
        for (Selection selection : mine) {
            ids.add(selection.getTopicId());
        }
        return ids;
    }

    private boolean selectedByMe(Long topicId, Long uid) {
        Long count = selectionMapper.selectCount(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getTopicId, topicId)
                .eq(Selection::getStudentId, uid)
                .ne(Selection::getStatus, "CANCELED"));
        return count != null && count > 0;
    }

    private TopicDtos.StudentTopicVO studentTopicVo(Topic topic, int attachmentCount, boolean selectedByMe) {
        int selected = topic.getSelectedCount() == null ? 0 : topic.getSelectedCount();
        String description = topic.getDescription() == null ? "" : topic.getDescription();
        String summary = description.length() > 120 ? description.substring(0, 120) + "…" : description;
        return new TopicDtos.StudentTopicVO(String.valueOf(topic.getId()), topic.getTitle(), summary,
                topic.getDifficulty(), Difficulty.labelOf(topic.getDifficulty()), selected,
                attachmentCount, selectedByMe);
    }

    private TopicDtos.TopicListItemVO listItemVo(Topic topic, int attachmentCount) {
        return new TopicDtos.TopicListItemVO(String.valueOf(topic.getId()), topic.getTitle(),
                topic.getDifficulty(), Difficulty.labelOf(topic.getDifficulty()),
                topic.getSelectedCount(), attachmentCount,
                Times.offset(topic.getCreatedAt()), Times.offset(topic.getUpdatedAt()));
    }

    private TopicDtos.TopicVO toVo(Topic topic) {
        List<TopicDtos.AttachmentVO> files = attachments(topic.getId());
        return new TopicDtos.TopicVO(String.valueOf(topic.getId()),
                String.valueOf(topic.getOfferingId()), topic.getTitle(), topic.getDescription(),
                topic.getDifficulty(), Difficulty.labelOf(topic.getDifficulty()),
                topic.getSelectedCount(), files.size(),
                Times.offset(topic.getCreatedAt()), Times.offset(topic.getUpdatedAt()),
                topic.getVersion(), files);
    }
}
