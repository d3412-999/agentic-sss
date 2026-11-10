package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.client.AiServiceClient;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.ExcellentWorkDtos;
import org.spring_web.spring_boot_service.entity.ExcellentWork;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.entity.Offering;
import org.spring_web.spring_boot_service.entity.ReportEvaluation;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.SubmissionVersion;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.entity.Topic;
import org.spring_web.spring_boot_service.mapper.ExcellentWorkMapper;
import org.spring_web.spring_boot_service.mapper.FileObjectMapper;
import org.spring_web.spring_boot_service.mapper.ReportEvaluationMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionVersionMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.spring_web.spring_boot_service.mapper.TopicMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 优秀作品模板库。
 *
 * <p>教师把某次开课中认为优秀的报告加入模板库；报告正文来自 file_object.extracted_text
 * （上传时由 ReportTextExtractor 写入），再加入 AI 服务的模板库。AI 服务会优先写入
 * LightRAG，未启用时退化为本地模板索引，两种方式对教师端都显示为「已入库」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcellentWorkService {

    /**
     * 索引正文的最大字符数。
     */
    private static final int MAX_CONTENT_CHARS = 50_000;

    private final ExcellentWorkMapper workMapper;
    private final OfferingService offeringService;
    private final SelectionMapper selectionMapper;
    private final TopicMapper topicMapper;
    private final SysUserMapper userMapper;
    private final SubmissionMapper submissionMapper;
    private final SubmissionVersionMapper versionMapper;
    private final ReportEvaluationMapper reportEvaluationMapper;
    private final FileObjectMapper fileObjectMapper;
    private final FileStorageService fileStorage;
    private final ReportTextExtractor reportTextExtractor;
    private final AiServiceClient aiClient;
    private final SssProperties properties;

    /**
     * 列出可加入模板库的候选选题，附带报告评审状态与分数。
     */
    public List<ExcellentWorkDtos.CandidateVO> candidates(String offeringId) {
        Offering offering = offeringService.ownedOffering(offeringId);
        List<Selection> selections = selectionMapper.selectList(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getOfferingId, offering.getId())
                .ne(Selection::getStatus, "CANCELED")
                .orderByAsc(Selection::getCreatedAt));
        Map<Long, ExcellentWork> works = activeWorks(offering.getId());

        List<ExcellentWorkDtos.CandidateVO> result = new ArrayList<>();
        for (Selection selection : selections) {
            Submission report = reportSubmission(selection.getId());
            ReportEvaluation evaluation = evaluationOf(report);
            Topic topic = topicMapper.selectById(selection.getTopicId());
            SysUser student = userMapper.selectById(selection.getStudentId());
            ExcellentWork work = works.get(selection.getId());
            result.add(new ExcellentWorkDtos.CandidateVO(
                    String.valueOf(selection.getId()),
                    String.valueOf(selection.getStudentId()),
                    student == null ? "" : student.getStudentNo(),
                    student == null ? "" : student.getName(),
                    topic == null ? "" : topic.getTitle(),
                    report == null ? null : String.valueOf(report.getId()),
                    report == null ? null : report.getStatus(),
                    evaluation == null ? null : evaluation.getScore(),
                    evaluation == null ? null : evaluation.getPageCount(),
                    work == null ? null : String.valueOf(work.getId()),
                    work == null ? null : work.getIndexStatus()));
        }
        result.sort(Comparator
                .comparing(ExcellentWorkDtos.CandidateVO::reportScore,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ExcellentWorkDtos.CandidateVO::studentNo));
        return result;
    }

    /**
     * 已加入模板库的作品。
     */
    public List<ExcellentWorkDtos.WorkVO> list(String offeringId) {
        Offering offering = offeringService.ownedOffering(offeringId);
        return workMapper.selectList(new LambdaQueryWrapper<ExcellentWork>()
                        .eq(ExcellentWork::getOfferingId, offering.getId())
                        .orderByDesc(ExcellentWork::getCreatedAt))
                .stream().map(this::toVo).toList();
    }

    /**
     * 把选题的报告加入模板库。AI 索引失败时仍保留记录，教师可稍后重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExcellentWorkDtos.WorkVO create(String offeringId, ExcellentWorkDtos.CreateRequest request) {
        Offering offering = offeringService.ownedOffering(offeringId);
        Selection selection = BizAssert.found(
                selectionMapper.selectById(BizAssert.id(request.selectionId(), "selectionId")), "选题");
        BizAssert.state(offering.getId().equals(selection.getOfferingId()), "选题不属于该开课");
        BizAssert.state(!"CANCELED".equals(selection.getStatus()), "该选题已退选，不能加入模板库");

        ExcellentWork exists = workMapper.selectOne(new LambdaQueryWrapper<ExcellentWork>()
                .eq(ExcellentWork::getOfferingId, offering.getId())
                .eq(ExcellentWork::getSelectionId, selection.getId())
                .last("limit 1"));
        BizAssert.state(exists == null, "该作品已经在模板库中");

        Submission report = BizAssert.found(reportSubmission(selection.getId()), "报告提交物");
        FileObject file = BizAssert.found(fileOf(report), "报告文件");
        String text = ensureText(file);
        BizAssert.state(StringUtils.hasText(text), "报告正文为空，请先在提交记录里重新分析报告");

        Topic topic = topicMapper.selectById(selection.getTopicId());
        SysUser student = userMapper.selectById(selection.getStudentId());
        String topicTitle = topic == null ? "" : topic.getTitle();
        String title = (student == null ? "" : student.getName()) + " · " + topicTitle;

        ExcellentWork work = new ExcellentWork();
        work.setOfferingId(offering.getId());
        work.setSelectionId(selection.getId());
        work.setSubmissionId(report.getId());
        work.setTitle(truncate(title, 200));
        work.setStudentName(student == null ? "" : student.getName());
        work.setStudentNo(student == null ? "" : student.getStudentNo());
        work.setTopicTitle(truncate(topicTitle, 200));
        work.setNote(request.note());
        work.setTags(request.tags());
        work.setContentText(truncate(text, MAX_CONTENT_CHARS));
        work.setIndexStatus("PENDING");
        work.setCreatedBy(SecurityUtils.userId());
        work.setCreatedAt(Times.now());
        work.setUpdatedAt(Times.now());
        workMapper.insert(work);

        index(work);
        return toVo(work);
    }

    /**
     * 从模板库移除（软删除），并尽力清理 AI 侧索引。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String offeringId, String workId) {
        Offering offering = offeringService.ownedOffering(offeringId);
        ExcellentWork work = ownedWork(offering, workId);
        workMapper.deleteById(work.getId());
        try {
            aiClient.deleteTemplate("ew-" + work.getId());
        } catch (Exception ex) {
            log.warn("删除 AI 模板失败，workId={}", work.getId(), ex);
        }
    }

    /**
     * 失败或需要更新内容时重新索引。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExcellentWorkDtos.WorkVO reindex(String offeringId, String workId) {
        Offering offering = offeringService.ownedOffering(offeringId);
        ExcellentWork work = ownedWork(offering, workId);
        index(work);
        return toVo(work);
    }

    // ---------------------------------------------------------------- 内部

    private void index(ExcellentWork work) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("docId", "ew-" + work.getId());
            payload.put("title", work.getTitle());
            payload.put("content", StringUtils.hasText(work.getContentText()) ? work.getContentText() : work.getTitle());
            payload.put("category", "EXCELLENT_WORK");
            payload.put("metadata", Map.of(
                    "offeringId", String.valueOf(work.getOfferingId()),
                    "selectionId", String.valueOf(work.getSelectionId()),
                    "studentNo", work.getStudentNo() == null ? "" : work.getStudentNo(),
                    "topicTitle", work.getTopicTitle() == null ? "" : work.getTopicTitle(),
                    "tags", work.getTags() == null ? "" : work.getTags()));
            Map<String, Object> response = aiClient.indexTemplate(payload);
            work.setIndexStatus("INDEXED");
            work.setIndexMethod(String.valueOf(response.getOrDefault("method", "local")));
            work.setExternalDocId(String.valueOf(response.getOrDefault("docId", "ew-" + work.getId())));
            work.setErrorMessage(null);
        } catch (Exception ex) {
            work.setIndexStatus("FAILED");
            work.setErrorMessage(truncate(ex.getMessage(), 500));
            log.warn("优秀作品加入模板库失败，workId={}", work.getId(), ex);
        }
        work.setUpdatedAt(Times.now());
        workMapper.updateById(work);
    }

    /**
     * 确保正文已落库；老数据没有抽取结果时现场解析一次并回写。
     */
    private String ensureText(FileObject file) {
        if (StringUtils.hasText(file.getExtractedText())) {
            return file.getExtractedText();
        }
        Path path = fileStorage.locate(file);
        if (path == null) {
            return "";
        }
        ReportTextExtractor.ExtractedReport extracted = reportTextExtractor.extract(path, file.getFilename());
        if (!StringUtils.hasText(extracted.text())) {
            return "";
        }
        file.setExtractedText(extracted.text());
        file.setPageCount(extracted.pageCount());
        file.setUpdatedAt(Times.now());
        fileObjectMapper.updateById(file);
        return extracted.text();
    }

    private Map<Long, ExcellentWork> activeWorks(Long offeringId) {
        Map<Long, ExcellentWork> map = new LinkedHashMap<>();
        for (ExcellentWork work : workMapper.selectList(new LambdaQueryWrapper<ExcellentWork>()
                .eq(ExcellentWork::getOfferingId, offeringId))) {
            map.put(work.getSelectionId(), work);
        }
        return map;
    }

    private ExcellentWork ownedWork(Offering offering, String workId) {
        ExcellentWork work = BizAssert.found(
                workMapper.selectById(BizAssert.id(workId, "workId")), "优秀作品");
        BizAssert.state(offering.getId().equals(work.getOfferingId()), "作品不属于该开课");
        return work;
    }

    private Submission reportSubmission(Long selectionId) {
        return submissionMapper.selectOne(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getSelectionId, selectionId)
                .eq(Submission::getType, "REPORT")
                .last("limit 1"));
    }

    private ReportEvaluation evaluationOf(Submission report) {
        if (report == null) {
            return null;
        }
        return reportEvaluationMapper.selectOne(new LambdaQueryWrapper<ReportEvaluation>()
                .eq(ReportEvaluation::getSubmissionId, report.getId())
                .last("limit 1"));
    }

    private FileObject fileOf(Submission submission) {
        if (submission.getLatestVersionId() == null) {
            return null;
        }
        SubmissionVersion version = versionMapper.selectById(submission.getLatestVersionId());
        if (version == null || version.getFileId() == null) {
            return null;
        }
        return fileObjectMapper.selectById(version.getFileId());
    }

    private ExcellentWorkDtos.WorkVO toVo(ExcellentWork work) {
        return new ExcellentWorkDtos.WorkVO(
                String.valueOf(work.getId()),
                String.valueOf(work.getOfferingId()),
                String.valueOf(work.getSelectionId()),
                String.valueOf(work.getSubmissionId()),
                work.getTitle(),
                work.getStudentNo(),
                work.getStudentName(),
                work.getTopicTitle(),
                work.getNote(),
                work.getTags(),
                work.getIndexStatus(),
                work.getIndexMethod(),
                work.getErrorMessage(),
                Times.offset(work.getCreatedAt()));
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
