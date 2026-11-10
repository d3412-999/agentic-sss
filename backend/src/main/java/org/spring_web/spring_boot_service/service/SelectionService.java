package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.dto.SelectionDtos;
import org.spring_web.spring_boot_service.entity.CodeAnalysis;
import org.spring_web.spring_boot_service.entity.DefenseSession;
import org.spring_web.spring_boot_service.entity.Grade;
import org.spring_web.spring_boot_service.entity.Offering;
import org.spring_web.spring_boot_service.entity.OfferingStudent;
import org.spring_web.spring_boot_service.entity.ReportEvaluation;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.Topic;
import org.spring_web.spring_boot_service.mapper.CodeAnalysisMapper;
import org.spring_web.spring_boot_service.mapper.DefenseSessionMapper;
import org.spring_web.spring_boot_service.mapper.GradeMapper;
import org.spring_web.spring_boot_service.mapper.OfferingMapper;
import org.spring_web.spring_boot_service.mapper.OfferingStudentMapper;
import org.spring_web.spring_boot_service.mapper.ReportEvaluationMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.TopicExtraMapper;
import org.spring_web.spring_boot_service.mapper.TopicMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学生选题：本项目并发压力最大、最容易出现超卖的地方。
 *
 * <p><b>容量控制为什么不用「先查再改」</b>：两个事务同时读到 selectedCount=9/max=10，
 * 都判断「还有名额」并各自 +1，结果变成 11。正确做法是让数据库用一条带条件的
 * UPDATE 完成判断与递增，MySQL 的行锁天然串行化：</p>
 * <pre>
 * UPDATE topic SET selected_count = selected_count + 1, version = version + 1
 *  WHERE id = ? AND deleted = 0 AND selected_count &lt; max_select_count;
 * </pre>
 * <p>影响行数为 0 就是「已满 / 已下架」。SQL 放在 {@link TopicExtraMapper} 里手写，
 * 不走 {@code update(null, LambdaUpdateWrapper)}——后者会被乐观锁插件与 SQL 拼接规则影响，
 * 行为不够确定。预占与释放是两条独立 SQL：预占卡上限，释放只保证不为负，
 * 这样教师中途调低上限时学生依然能退选。</p>
 *
 * <p>再加上 uk_selection_student_topic 唯一索引兜底（DuplicateKeyException 由全局异常
 * 转成 40902），以及 @Transactional 保证「预占 + 写入选题」同生共死，整条链路才闭环。</p>
 *
 * <p>另一个容易被忽略的点：退选只把 status 改成 CANCELED 而不删记录，
 * 因为唯一索引不认 deleted 标记；重新选同一题时复用旧记录，否则会撞唯一键。</p>
 */
@Service
@RequiredArgsConstructor
public class SelectionService {

    private final SelectionMapper selectionMapper;
    private final TopicMapper topicMapper;
    private final TopicExtraMapper topicExtraMapper;
    private final OfferingMapper offeringMapper;
    private final OfferingStudentMapper offeringStudentMapper;
    private final SubmissionMapper submissionMapper;
    private final CodeAnalysisMapper codeAnalysisMapper;
    private final ReportEvaluationMapper reportEvaluationMapper;
    private final DefenseSessionMapper defenseSessionMapper;
    private final GradeMapper gradeMapper;
    private final OfferingService offeringService;
    private final AuditLogService auditLog;

    @Transactional(rollbackFor = Exception.class)
    public List<SelectionDtos.CreatedItemVO> create(SelectionDtos.SelectionCreateRequest request) {
        Long offeringId = BizAssert.id(request.offeringId(), "offeringId");
        Long studentId = SecurityUtils.userId();
        Offering offering = BizAssert.found(offeringMapper.selectById(offeringId), "开课");

        // 1. 开课状态与时间窗
        BizAssert.rule("PUBLISHED".equals(offering.getStatus()), "开课尚未发布或已关闭选题");
        BizAssert.rule(offeringService.inSelectWindow(offering), "当前不在选题时间窗口内");

        // 2. 必须在名单内且为 ACTIVE
        Long inRoster = offeringStudentMapper.selectCount(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, offeringId)
                .eq(OfferingStudent::getStudentId, studentId)
                .eq(OfferingStudent::getStatus, "ACTIVE"));
        BizAssert.forbidden(inRoster != null && inRoster > 0, "开课");

        // 2.1 串行化同一学生的并发选题：对名单行加锁，让下面的限额判断看到最新数据。
        //     若不加锁，「已有 + 本次 ≤ 上限」是读后写判断，并发请求会同时通过。
        offeringStudentMapper.lockStudentRelation(offeringId, studentId);

        // 3. 去重 + 单次批量上限
        Set<String> distinct = new LinkedHashSet<>(request.topicIds());
        BizAssert.param(distinct.size() == request.topicIds().size(), "不能重复选择同一题目");
        BizAssert.param(!distinct.isEmpty() && distinct.size() <= 10, "单次最多选择 10 道题目");

        // 4. 已有 + 本次不能超过每人限额
        List<Selection> existing = selectionMapper.selectList(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getOfferingId, offeringId)
                .eq(Selection::getStudentId, studentId)
                .ne(Selection::getStatus, "CANCELED"));
        BizAssert.rule(existing.size() + distinct.size() <= offering.getTopicSelectLimit(),
                "超出每人限选题目数（上限 " + offering.getTopicSelectLimit() + "）");

        List<SelectionDtos.CreatedItemVO> created = new ArrayList<>();
        for (String rawTopicId : distinct) {
            Long topicId = BizAssert.id(rawTopicId, "topicId");
            Topic topic = BizAssert.found(topicMapper.selectById(topicId), "题目");
            BizAssert.rule(topic.getOfferingId().equals(offeringId), "题目不属于该开课");
            BizAssert.rule("PUBLISHED".equals(topic.getStatus()), "题目未发布：" + topic.getTitle());

            // 5. 原子预占名额（判断 + 递增在同一条 SQL 的 WHERE 内完成，MySQL 行锁串行化）
            //    0 行 = 名额已满 / 已下架 / 已删除，统一按 TOPIC_FULL 反馈给前端刷新题目列表
            if (topicExtraMapper.reserveOne(topicId) != 1) {
                throw new BizException(ErrorCode.BUSINESS_RULE,
                        "题目名额已满或已下架：" + topic.getTitle(), Map.of("reason", "TOPIC_FULL"));
            }

            // 6. 已退选过同一题则复用记录，避开 (student_id, topic_id) 唯一索引
            Selection old = selectionMapper.selectOne(new LambdaQueryWrapper<Selection>()
                    .eq(Selection::getStudentId, studentId)
                    .eq(Selection::getTopicId, topicId)
                    .last("limit 1"));
            Selection selection;
            if (old != null && "CANCELED".equals(old.getStatus())) {
                selection = old;
                selection.setStatus("SELECTED");
                selection.setCanceledAt(null);
                selection.setOfferingId(offeringId);
                selection.setUpdatedAt(Times.now());
                if (selectionMapper.updateById(selection) == 0) {
                    throw new BizException(ErrorCode.CONFLICT, "选题状态已变更，请刷新后重试");
                }
            } else if (old != null) {
                throw new BizException(ErrorCode.DUPLICATE_OPERATION, "不能重复选择同一题目");
            } else {
                selection = new Selection();
                selection.setOfferingId(offeringId);
                selection.setTopicId(topicId);
                selection.setStudentId(studentId);
                selection.setStatus("SELECTED");
                selection.setVersion(0);
                selection.setCreatedAt(Times.now());
                selection.setUpdatedAt(Times.now());
                selectionMapper.insert(selection);
            }
            created.add(new SelectionDtos.CreatedItemVO(String.valueOf(selection.getId()),
                    String.valueOf(topicId), topic.getTitle(), selection.getStatus(),
                    Times.offset(selection.getCreatedAt())));
        }
        auditLog.record("SELECTION_CREATE", "OFFERING", offeringId, null,
                created.stream().map(SelectionDtos.CreatedItemVO::topicId).toList(), null);
        return created;
    }

    public PageResult<SelectionDtos.SelectionVO> mine(String offeringId, long page, long size) {
        Long studentId = SecurityUtils.userId();
        // 先算好 id 再传给 eq：Java 参数会无条件求值，直接内联会在 offeringId 为空时抛错
        Long oid = (offeringId == null || offeringId.isBlank())
                ? null : BizAssert.id(offeringId, "offeringId");
        LambdaQueryWrapper<Selection> wrapper = new LambdaQueryWrapper<Selection>()
                .eq(Selection::getStudentId, studentId)
                .eq(oid != null, Selection::getOfferingId, oid)
                .ne(Selection::getStatus, "CANCELED")
                .orderByDesc(Selection::getId);
        Page<Selection> result = selectionMapper.selectPage(Page.of(page, size), wrapper);
        List<SelectionDtos.SelectionVO> list = result.getRecords().stream().map(this::toVo).toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    public SelectionDtos.SelectionDetailVO detail(String selectionId) {
        Selection selection = load(selectionId);
        Long uid = SecurityUtils.userId();
        boolean self = selection.getStudentId().equals(uid);
        if (!self) {
            offeringService.ownedOffering(String.valueOf(selection.getOfferingId()));
        }
        Topic topic = topicMapper.selectById(selection.getTopicId());
        Offering offering = BizAssert.found(
                offeringMapper.selectById(selection.getOfferingId()), "开课");
        boolean published = published(offering.getId(), selection.getStudentId());
        List<SelectionDtos.SubmissionBrief> submissions = submissionMapper.selectList(
                        new LambdaQueryWrapper<Submission>().eq(Submission::getSelectionId, selection.getId()))
                .stream()
                .map(s -> new SelectionDtos.SubmissionBrief(String.valueOf(s.getId()), s.getType(),
                        s.getLatestVersion(), s.getStatus(), Times.offset(s.getCreatedAt())))
                .toList();
        Grade grade = gradeOf(offering.getId(), selection.getStudentId());
        return new SelectionDtos.SelectionDetailVO(String.valueOf(selection.getId()),
                String.valueOf(offering.getId()), topic == null ? "" : topic.getTitle(),
                selection.getStatus(), submissions,
                published ? scoresOf(grade) : new SelectionDtos.Scores(null, null, null, null),
                published, published && grade != null ? grade.getFinalScore() : null,
                Times.offset(offering.getSubmitDeadline()), selection.getVersion());
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(String selectionId) {
        Selection selection = load(selectionId);
        BizAssert.forbidden(selection.getStudentId().equals(SecurityUtils.userId()), "选题");
        Offering offering = BizAssert.found(
                offeringMapper.selectById(selection.getOfferingId()), "开课");
        BizAssert.state("SELECTED".equals(selection.getStatus()), "已提交材料或已评分的选题不能退选");
        BizAssert.rule(offeringService.inSelectWindow(offering), "已超过选题时间窗口，不能退选");
        Long submissions = submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getSelectionId, selection.getId()));
        BizAssert.rule(submissions == null || submissions == 0, "已有提交材料，不能退选");

        selection.setStatus("CANCELED");
        selection.setCanceledAt(Times.now());
        selection.setUpdatedAt(Times.now());
        if (selectionMapper.updateById(selection) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "选题状态已变更，请刷新后重试");
        }
        // 释放名额：SQL 自带 selected_count > 0 防止减成负数。
        // 返回 0 说明计数本来就是 0（数据不一致），不应该因此阻塞学生退选，故忽略返回值。
        topicExtraMapper.releaseOne(selection.getTopicId());
        auditLog.record("SELECTION_CANCEL", "SELECTION", selection.getId(), null, null, null);
    }

    /** 上传材料后把选题状态推进到 SUBMITTED，供 SubmissionService 复用。 */
    @Transactional(rollbackFor = Exception.class)
    public void markSubmitted(Long selectionId) {
        Selection selection = BizAssert.found(selectionMapper.selectById(selectionId), "选题");
        if ("SELECTED".equals(selection.getStatus())) {
            selection.setStatus("SUBMITTED");
            selection.setUpdatedAt(Times.now());
            if (selectionMapper.updateById(selection) == 0) {
                // 并发下状态已被别的请求推进，不算失败；只有写入异常才需要暴露
                throw new BizException(ErrorCode.CONFLICT, "选题状态已变更，请刷新后重试");
            }
        }
    }

    private Selection load(String selectionId) {
        return BizAssert.found(selectionMapper.selectById(BizAssert.id(selectionId, "selectionId")), "选题");
    }

    private SelectionDtos.SelectionVO toVo(Selection selection) {
        Topic topic = topicMapper.selectById(selection.getTopicId());
        Offering offering = offeringMapper.selectById(selection.getOfferingId());
        Grade grade = gradeOf(selection.getOfferingId(), selection.getStudentId());
        boolean published = published(selection.getOfferingId(), selection.getStudentId());
        return new SelectionDtos.SelectionVO(String.valueOf(selection.getId()),
                String.valueOf(selection.getOfferingId()),
                new SelectionDtos.TopicBrief(String.valueOf(selection.getTopicId()),
                        topic == null ? "" : topic.getTitle(), topic == null ? null : topic.getDifficulty()),
                selection.getStatus(), progressOf(selection),
                published ? scoresOf(grade) : new SelectionDtos.Scores(null, null, null, null),
                offering == null ? null : Times.offset(offering.getSubmitDeadline()),
                selection.getVersion());
    }

    private SelectionDtos.Progress progressOf(Selection selection) {
        List<Submission> submissions = submissionMapper.selectList(
                new LambdaQueryWrapper<Submission>().eq(Submission::getSelectionId, selection.getId()));
        boolean source = false;
        boolean report = false;
        boolean codeAnalyzed = false;
        boolean reportEvaluated = false;
        for (Submission submission : submissions) {
            if ("SOURCE".equals(submission.getType())) {
                source = true;
                CodeAnalysis analysis = codeAnalysisMapper.selectOne(
                        new LambdaQueryWrapper<CodeAnalysis>()
                                .eq(CodeAnalysis::getSubmissionId, submission.getId()).last("limit 1"));
                codeAnalyzed = analysis != null && "SUCCEEDED".equals(analysis.getStatus());
            } else {
                report = true;
                ReportEvaluation evaluation = reportEvaluationMapper.selectOne(
                        new LambdaQueryWrapper<ReportEvaluation>()
                                .eq(ReportEvaluation::getSubmissionId, submission.getId()).last("limit 1"));
                reportEvaluated = evaluation != null && "SUCCEEDED".equals(evaluation.getStatus());
            }
        }
        DefenseSession defense = defenseSessionMapper.selectOne(new LambdaQueryWrapper<DefenseSession>()
                .eq(DefenseSession::getOfferingId, selection.getOfferingId())
                .eq(DefenseSession::getStudentId, selection.getStudentId())
                .last("limit 1"));
        return new SelectionDtos.Progress(source, report, codeAnalyzed, reportEvaluated,
                defense == null ? null : defense.getStatus());
    }

    private Grade gradeOf(Long offeringId, Long studentId) {
        return gradeMapper.selectOne(new LambdaQueryWrapper<Grade>()
                .eq(Grade::getOfferingId, offeringId)
                .eq(Grade::getStudentId, studentId).last("limit 1"));
    }

    private boolean published(Long offeringId, Long studentId) {
        Grade grade = gradeOf(offeringId, studentId);
        return grade != null && grade.getPublishedAt() != null;
    }

    private SelectionDtos.Scores scoresOf(Grade grade) {
        return grade == null ? new SelectionDtos.Scores(null, null, null, null)
                : new SelectionDtos.Scores(grade.getCodeScore(), grade.getReportScore(),
                        grade.getDefenseScore(), grade.getFinalScore());
    }
}
