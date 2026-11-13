package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.CsvUtil;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.GradeMath;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.AsyncConfig;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.dto.GradeDtos;
import org.spring_web.spring_boot_service.dto.OfferingDtos;
import org.spring_web.spring_boot_service.entity.CodeAnalysis;
import org.spring_web.spring_boot_service.entity.Course;
import org.spring_web.spring_boot_service.entity.DefenseSession;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.entity.Grade;
import org.spring_web.spring_boot_service.entity.GradeItem;
import org.spring_web.spring_boot_service.entity.Offering;
import org.spring_web.spring_boot_service.entity.OfferingStudent;
import org.spring_web.spring_boot_service.entity.ReportEvaluation;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.entity.Topic;
import org.spring_web.spring_boot_service.mapper.CodeAnalysisMapper;
import org.spring_web.spring_boot_service.mapper.CourseMapper;
import org.spring_web.spring_boot_service.mapper.DefenseSessionMapper;
import org.spring_web.spring_boot_service.mapper.GradeItemMapper;
import org.spring_web.spring_boot_service.mapper.GradeMapper;
import org.spring_web.spring_boot_service.mapper.OfferingMapper;
import org.spring_web.spring_boot_service.mapper.OfferingStudentMapper;
import org.spring_web.spring_boot_service.mapper.ReportEvaluationMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.spring_web.spring_boot_service.mapper.TopicMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 成绩计算与发布。这是全系统「最不能被质疑」的一块，所以规则全部显式写死：
 *
 * <pre>
 * final = 答辩 × w_defense + 源码 × w_code + 报告 × w_report   （权重和 100）
 * 源码 / 报告：多题时取各选题得分的算术平均；缺考 / 未提交记 0
 * 等级：≥90 优秀，≥80 良好，≥70 中等，≥60 及格，否则不及格
 * </pre>
 *
 * <p>使用 BigDecimal 全程计算，最后一步才 HALF_UP 保留 1 位小数，
 * 避免 float/double 造成 87.45 变 87.4 这类争议。改分必须填 reason，
 * 且写入 audit_log 的变更前 / 变更后完整快照。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GradeService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final GradeMapper gradeMapper;
    private final GradeItemMapper gradeItemMapper;
    private final SelectionMapper selectionMapper;
    private final TopicMapper topicMapper;
    private final SubmissionMapper submissionMapper;
    private final CodeAnalysisMapper codeAnalysisMapper;
    private final ReportEvaluationMapper reportEvaluationMapper;
    private final DefenseSessionMapper defenseSessionMapper;
    private final OfferingMapper offeringMapper;
    private final OfferingStudentMapper offeringStudentMapper;
    private final CourseMapper courseMapper;
    private final SysUserMapper sysUserMapper;
    private final OfferingService offeringService;
    private final AsyncTaskService taskService;
    private final PlatformTransactionManager transactionManager;
    private final AuditLogService auditLog;
    private final NotificationService notificationService;
    private final FileStorageService fileStorage;
    private final JsonCodec json;
    private final SssProperties properties;

    // ---------------------------------------------------------------- 教师端查询

    public PageResult<GradeDtos.GradeListItemVO> teacherGrades(
            String offeringId, String keyword, String status, long page, long size) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        // 关键词必须在 SQL 里过滤：先分页再在内存里筛，total 与实际条数会对不上
        List<Long> matchedIds = null;
        if (StringUtils.hasText(keyword)) {
            matchedIds = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .select(SysUser::getId)
                            .and(w -> w.like(SysUser::getName, keyword)
                                    .or().like(SysUser::getStudentNo, keyword)))
                    .stream().map(SysUser::getId).toList();
            if (matchedIds.isEmpty()) {
                return PageResult.of(List.of(), 0, page, size);
            }
        }
        Page<Grade> result = gradeMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<Grade>()
                        .eq(Grade::getOfferingId, oid)
                        .in(matchedIds != null, Grade::getStudentId, matchedIds)
                        .eq(StringUtils.hasText(status) && !"all".equals(status),
                                Grade::getStatus, status)
                        .orderByDesc(Grade::getId));
        // 列表页一次性批量取学生与选题数，避免逐行 selectById / selectCount 的 N+1。
        List<Long> studentIds = result.getRecords().stream()
                .map(Grade::getStudentId).filter(Objects::nonNull).distinct().toList();
        Map<Long, SysUser> students = studentMap(studentIds);
        Map<Long, Long> selectionCounts = selectionCountMap(oid, studentIds);
        List<GradeDtos.GradeListItemVO> list = result.getRecords().stream()
                .map(grade -> listItemVo(grade, students, selectionCounts))
                .toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    public GradeDtos.GradeSummary summary(String offeringId) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        List<Grade> grades = gradeMapper.selectList(new LambdaQueryWrapper<Grade>()
                .eq(Grade::getOfferingId, oid));
        List<BigDecimal> finals = grades.stream().map(Grade::getFinalScore)
                .filter(Objects::nonNull).toList();
        if (finals.isEmpty()) {
            return new GradeDtos.GradeSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        BigDecimal sum = finals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        long pass = finals.stream().filter(v -> v.compareTo(BigDecimal.valueOf(60)) >= 0).count();
        return new GradeDtos.GradeSummary(
                sum.divide(BigDecimal.valueOf(finals.size()), 1, RoundingMode.HALF_UP),
                finals.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO),
                finals.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO),
                Math.round((double) pass / finals.size() * 1000) / 10.0);
    }

    public GradeDtos.GradeDetailVO detail(String gradeId) {
        Grade grade = ownedGrade(gradeId);
        Offering offering = BizAssert.found(offeringMapper.selectById(grade.getOfferingId()), "开课");
        SysUser student = sysUserMapper.selectById(grade.getStudentId());
        List<GradeItem> items = gradeItemMapper.selectList(new LambdaQueryWrapper<GradeItem>()
                .eq(GradeItem::getGradeId, grade.getId()));
        return new GradeDtos.GradeDetailVO(String.valueOf(grade.getId()),
                new GradeDtos.StudentBrief(student == null ? "" : student.getStudentNo(),
                        student == null ? "" : student.getName(), student == null ? "" : student.getClassName()),
                weights(offering),
                items.stream().map(i -> new GradeDtos.GradeItemVO(String.valueOf(i.getSelectionId()),
                        i.getTopicTitle(), i.getCodeScore(), i.getReportScore(),
                        i.getDefenseScore(), i.getFinalScore())).toList(),
                grade.getFinalScore(), grade.getLevel(), grade.getStatus(), grade.getVersion());
    }

    // ---------------------------------------------------------------- 改分 / 解锁

    @Transactional(rollbackFor = Exception.class)
    public GradeDtos.GradeDetailVO adjust(String gradeId, GradeDtos.GradeAdjustRequest request) {
        Grade grade = ownedGrade(gradeId);
        BizAssert.state(!"LOCKED".equals(grade.getStatus()), "成绩已发布，请先撤销发布");
        BizAssert.state(request.version().equals(grade.getVersion()), "成绩已被他人修改，请刷新后重试");
        String reason = request.reason() == null ? "" : request.reason().trim();
        // 最短字数由配置驱动（sss.limits.grade-adjust-reason-min-length），不再写死 5
        int minReasonLength = properties.getLimits().getGradeAdjustReasonMinLength();
        BizAssert.param(reason.length() >= minReasonLength, "调整原因至少 " + minReasonLength + " 个字");
        Offering offering = BizAssert.found(offeringMapper.selectById(grade.getOfferingId()), "开课");

        GradeDtos.GradeDetailVO before = detail(String.valueOf(grade.getId()));
        BigDecimal defense = GradeMath.nz(request.defenseScore() != null ? request.defenseScore()
                : grade.getDefenseScore());
        BigDecimal code = GradeMath.nz(request.codeScore() != null ? request.codeScore()
                : grade.getCodeScore());
        BigDecimal report = GradeMath.nz(request.reportScore() != null ? request.reportScore()
                : grade.getReportScore());
        // 人工改分拒绝越界入参；随后入库前再做一次夹取，保证 DB 里永远是 0~100
        validateScore(defense);
        validateScore(code);
        validateScore(report);

        grade.setDefenseScore(GradeMath.clamp(defense, BigDecimal.ZERO, HUNDRED));
        grade.setCodeScore(GradeMath.clamp(code, BigDecimal.ZERO, HUNDRED));
        grade.setReportScore(GradeMath.clamp(report, BigDecimal.ZERO, HUNDRED));
        BigDecimal finalScore = GradeMath.clamp(weighted(offering, defense, code, report),
                BigDecimal.ZERO, HUNDRED);
        grade.setFinalScore(finalScore);
        grade.setLevel(GradeMath.level(finalScore));
        grade.setStatus("ADJUSTED");
        grade.setAdjusted(1);
        grade.setVersion(request.version());
        grade.setUpdatedAt(Times.now());
        if (gradeMapper.updateById(grade) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "成绩已被他人修改，请刷新后重试");
        }
        auditLog.record("GRADE_ADJUST", "GRADE", grade.getId(), before,
                detail(String.valueOf(grade.getId())), reason);
        return detail(String.valueOf(grade.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void unlock(String gradeId) {
        Grade grade = ownedGrade(gradeId);
        BizAssert.state("LOCKED".equals(grade.getStatus()), "成绩未发布，无需解锁");
        grade.setStatus(Integer.valueOf(1).equals(grade.getAdjusted()) ? "ADJUSTED" : "AUTO");
        grade.setPublishedAt(null);
        grade.setUpdatedAt(Times.now());
        if (gradeMapper.updateById(grade) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "成绩状态已变更，请刷新后重试");
        }
        auditLog.record("GRADE_UNLOCK", "GRADE", grade.getId(), null, null, "撤销发布后解锁改分");
    }

    // ---------------------------------------------------------------- 发布 / 撤销

    @Transactional(rollbackFor = Exception.class)
    public GradeDtos.GradePublishResult publish(String offeringId, GradeDtos.GradePublishRequest request) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        Offering offering = offeringService.ownedOffering(offeringId);
        List<Long> targets = request.gradeIds() == null ? List.of()
                : request.gradeIds().stream().map(id -> BizAssert.id(id, "gradeId")).toList();
        List<Grade> grades = gradeMapper.selectList(new LambdaQueryWrapper<Grade>()
                .eq(Grade::getOfferingId, oid)
                .in(!targets.isEmpty(), Grade::getId, targets));

        // 发布前用最新的 AI 评审结果重算，避免“重新评审完成，但发布出去的还是旧分”。
        // 已调整（ADJUSTED）的成绩在 computeForStudent 内会保留；已发布（LOCKED）的跳过。
        for (Grade grade : grades) {
            if (!"LOCKED".equals(grade.getStatus())) {
                computeForStudent(offering, grade.getStudentId(), false);
            }
        }
        grades = gradeMapper.selectList(new LambdaQueryWrapper<Grade>()
                .eq(Grade::getOfferingId, oid)
                .in(!targets.isEmpty(), Grade::getId, targets));

        List<String> skipped = new ArrayList<>();
        int published = 0;
        var now = Times.now();
        for (Grade grade : grades) {
            if ("LOCKED".equals(grade.getStatus())) {
                continue;
            }
            boolean complete = grade.getDefenseScore() != null && grade.getFinalScore() != null;
            if (!request.allowIncomplete() && !complete) {
                SysUser student = sysUserMapper.selectById(grade.getStudentId());
                skipped.add(student == null ? String.valueOf(grade.getStudentId()) : student.getStudentNo());
                continue;
            }
            grade.setStatus("LOCKED");
            grade.setPublishedAt(now);
            grade.setUpdatedAt(now);
            if (gradeMapper.updateById(grade) == 1) {
                published++;
                if (request.notifyStudents()) {
                    notificationService.push(grade.getStudentId(), "GRADE_PUBLISHED", "成绩已发布",
                            "课程设计成绩已发布，请及时查看。", "/student/grades");
                }
            }
        }
        if (!request.allowIncomplete() && !skipped.isEmpty() && published == 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE, "存在未完成答辩的学生，无法发布",
                    Map.of("skippedStudents", skipped));
        }
        auditLog.record("GRADE_PUBLISH", "OFFERING", oid, null,
                Map.of("published", published, "skipped", skipped.size()), null);
        return new GradeDtos.GradePublishResult(published, skipped.size(), skipped, Times.offset(now));
    }

    @Transactional(rollbackFor = Exception.class)
    public int unpublish(String offeringId, GradeDtos.UnpublishRequest request) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        List<Long> targets = request == null || request.gradeIds() == null ? List.of()
                : request.gradeIds().stream().map(id -> BizAssert.id(id, "gradeId")).toList();
        List<Grade> grades = gradeMapper.selectList(new LambdaQueryWrapper<Grade>()
                .eq(Grade::getOfferingId, oid)
                .eq(Grade::getStatus, "LOCKED")
                .in(!targets.isEmpty(), Grade::getId, targets));
        int updated = 0;
        for (Grade grade : grades) {
            if (grade.getPublishedAt() != null
                    && Duration.between(grade.getPublishedAt(), Times.now()).toHours() >= 24) {
                continue;
            }
            grade.setStatus(Integer.valueOf(1).equals(grade.getAdjusted()) ? "ADJUSTED" : "AUTO");
            grade.setPublishedAt(null);
            grade.setUpdatedAt(Times.now());
            updated += gradeMapper.updateById(grade);
        }
        auditLog.record("GRADE_UNPUBLISH", "OFFERING", oid, null, Map.of("updated", updated), null);
        return updated;
    }

    // ---------------------------------------------------------------- 学生端

    public PageResult<GradeDtos.StudentGradeVO> studentGrades(String offeringId) {
        Long uid = SecurityUtils.userId();
        Long oid = (offeringId == null || offeringId.isBlank())
                ? null : BizAssert.id(offeringId, "offeringId");
        List<Grade> grades = gradeMapper.selectList(new LambdaQueryWrapper<Grade>()
                .eq(Grade::getStudentId, uid)
                .eq(oid != null, Grade::getOfferingId, oid)
                .eq(Grade::getStatus, "LOCKED")
                .isNotNull(Grade::getPublishedAt));
        // 批量取开课 / 课程 / 分项成绩，避免每个成绩 3 条查询的 N+1。
        List<Long> offeringIds = grades.stream().map(Grade::getOfferingId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Offering> offerings = offeringIds.isEmpty() ? Map.of()
                : offeringMapper.selectBatchIds(offeringIds).stream()
                        .collect(Collectors.toMap(Offering::getId, Function.identity()));
        List<Long> courseIds = offerings.values().stream().map(Offering::getCourseId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Course> courses = courseIds.isEmpty() ? Map.of()
                : courseMapper.selectBatchIds(courseIds).stream()
                        .collect(Collectors.toMap(Course::getId, Function.identity()));
        Map<Long, List<GradeItem>> items = gradeItemMap(
                grades.stream().map(Grade::getId).filter(Objects::nonNull).toList());
        List<GradeDtos.StudentGradeVO> list = grades.stream()
                .map(grade -> studentVo(grade, offerings, courses, items)).toList();
        return PageResult.of(list, list.size(), 1, Math.max(1, list.size()));
    }

    // ---------------------------------------------------------------- 重算 / 导出（异步）

    @Transactional(rollbackFor = Exception.class)
    public CommonDtos.TaskAccepted recalculate(String offeringId, GradeDtos.RecalcRequest request) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        var task = taskService.create("GRADE_RECALC", "OFFERING", oid, request);
        return new CommonDtos.TaskAccepted(String.valueOf(task.getId()), task.getStatus());
    }

    @Async(AsyncConfig.EXECUTOR)
    public void recalculateAsync(Long taskId, String offeringId, GradeDtos.RecalcRequest request) {
        try {
            taskService.markRunning(taskId, 5);
            Long oid = BizAssert.id(offeringId, "offeringId");
            Offering offering = offeringMapper.selectById(oid);
            List<OfferingStudent> roster = offeringStudentMapper.selectList(
                    new LambdaQueryWrapper<OfferingStudent>().eq(OfferingStudent::getOfferingId, oid));
            List<Long> targetStudents = roster.stream().map(OfferingStudent::getStudentId).distinct().toList();
            if ("STUDENT".equals(request != null ? request.scope() : null)
                    && request != null && request.studentIds() != null) {
                targetStudents = request.studentIds().stream()
                        .map(id -> BizAssert.id(id, "studentId")).toList();
            }
            boolean overwrite = request != null && request.overwriteAdjusted();
            int total = Math.max(1, targetStudents.size());
            int done = 0;
            // computeForStudent 带 @Transactional，但这里是同一个 bean 内的自调用，
            // 注解不会生效；用 TransactionTemplate 显式给每个学生一个事务，
            // 避免「grade 已更新、grade_item 删除后未重建」的中间状态。
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            for (Long studentId : targetStudents) {
                Long sid = studentId;
                tx.executeWithoutResult(status -> computeForStudent(offering, sid, overwrite));
                done++;
                taskService.markProgress(taskId, 5 + (int) (95L * done / total));
            }
            taskService.markSucceeded(taskId, Map.of("recalculated", done));
        } catch (Exception ex) {
            log.error("Grade recalc failed, taskId={}", taskId, ex);
            taskService.markFailed(taskId, ex.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public CommonDtos.TaskAccepted export(String offeringId, GradeDtos.ExportRequest request) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        // 注意：不能在同一个 bean 内直接调用 @Async 方法（自调用不经过代理，会退化成同步）。
        // 由 Controller 在拿到 taskId 后再经代理调用 exportAsync。
        var task = taskService.create("GRADE_EXPORT", "OFFERING", oid, request);
        return new CommonDtos.TaskAccepted(String.valueOf(task.getId()), task.getStatus());
    }

    @Async(AsyncConfig.EXECUTOR)
    public void exportAsync(Long taskId, Long offeringId, Long operatorId) {
        try {
            taskService.markRunning(taskId, 30);
            Offering offering = offeringMapper.selectById(offeringId);
            Course course = offering == null || offering.getCourseId() == null
                    ? null : courseMapper.selectById(offering.getCourseId());
            StringBuilder csv = new StringBuilder();
            csv.append('\ufeff').append("学号,姓名,班级,选题数,答辩分,源码分,报告分,总评,等级,教师评语,发布时间\n");
            List<Grade> grades = gradeMapper.selectList(new LambdaQueryWrapper<Grade>()
                    .eq(Grade::getOfferingId, offeringId));
            // 导出同样批量取学生与选题数；成绩单可能有几百行，逐行查询会拖慢异步任务
            List<Long> studentIds = grades.stream().map(Grade::getStudentId)
                    .filter(Objects::nonNull).distinct().toList();
            Map<Long, SysUser> students = studentMap(studentIds);
            Map<Long, Long> selectionCounts = selectionCountMap(offeringId, studentIds);
            for (Grade grade : grades) {
                SysUser student = students.get(grade.getStudentId());
                Long topicCount = selectionCounts.get(grade.getStudentId());
                csv.append(CsvUtil.field(student == null ? "" : student.getStudentNo())).append(',')
                        .append(CsvUtil.field(student == null ? "" : student.getName())).append(',')
                        .append(CsvUtil.field(student == null ? "" : student.getClassName())).append(',')
                        .append(CsvUtil.field(topicCount == null ? 0L : topicCount)).append(',')
                        .append(CsvUtil.field(grade.getDefenseScore())).append(',')
                        .append(CsvUtil.field(grade.getCodeScore())).append(',')
                        .append(CsvUtil.field(grade.getReportScore())).append(',')
                        .append(CsvUtil.field(grade.getFinalScore())).append(',')
                        .append(CsvUtil.field(grade.getLevel())).append(',')
                        .append(CsvUtil.field(grade.getTeacherComment())).append(',')
                        .append(CsvUtil.field(grade.getPublishedAt())).append('\n');
            }
            String name = (course == null ? "课程设计" : course.getName()) + "_成绩单.csv";
            FileObject file = fileStorage.storeBytes(name, csv.toString().getBytes(StandardCharsets.UTF_8),
                    "text/csv;charset=UTF-8", "EXPORT", operatorId);
            taskService.markSucceeded(taskId, Map.of(
                    "fileId", String.valueOf(file.getId()),
                    "filename", file.getFilename(),
                    "size", file.getSizeBytes(),
                    "downloadUrl", "/api/v1/files/" + file.getId() + "/download"));
        } catch (Exception ex) {
            log.error("Grade export failed, taskId={}", taskId, ex);
            taskService.markFailed(taskId, ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- 计算核心

    /** 按原始评分数据重算一名学生；已发布 LOCKED 的成绩不允许被覆盖。 */
    @Transactional(rollbackFor = Exception.class)
    public void computeForStudent(Offering offering, Long studentId, boolean overwriteAdjusted) {
        Grade grade = gradeMapper.selectOne(new LambdaQueryWrapper<Grade>()
                .eq(Grade::getOfferingId, offering.getId())
                .eq(Grade::getStudentId, studentId).last("limit 1"));
        if (grade != null && "LOCKED".equals(grade.getStatus())) {
            return;
        }
        if (grade != null && "ADJUSTED".equals(grade.getStatus()) && !overwriteAdjusted) {
            return;
        }

        List<Selection> selections = selectionMapper.selectList(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getOfferingId, offering.getId())
                .eq(Selection::getStudentId, studentId)
                .ne(Selection::getStatus, "CANCELED"));
        // 分项分入库前统一夹到 0~100，防止 AI / 人工数据源越界污染总评
        BigDecimal defense = clampScore(defenseScore(offering.getId(), studentId));
        BigDecimal code = clampScore(averageScore(selections, "SOURCE"));
        BigDecimal report = clampScore(averageScore(selections, "REPORT"));
        BigDecimal finalScore = clampScore(weighted(offering, defense, code, report));

        if (grade == null) {
            grade = new Grade();
            grade.setOfferingId(offering.getId());
            grade.setStudentId(studentId);
            grade.setStatus("AUTO");
            grade.setAdjusted(0);
            grade.setVersion(0);
            grade.setCreatedAt(Times.now());
        }
        grade.setDefenseScore(defense);
        grade.setCodeScore(code);
        grade.setReportScore(report);
        grade.setFinalScore(finalScore);
        grade.setLevel(GradeMath.level(finalScore));
        grade.setUpdatedAt(Times.now());
        if (grade.getId() == null) {
            gradeMapper.insert(grade);
        } else {
            gradeMapper.updateById(grade);
        }
        writeItems(offering, grade, selections, defense);
    }

    private void writeItems(Offering offering, Grade grade, List<Selection> selections, BigDecimal defense) {
        // 必须物理删除：逻辑删除的旧行仍会占用 (grade_id, selection_id) 唯一键
        gradeItemMapper.deletePhysicallyByGradeId(grade.getId());
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        snapshot.put("defense", offering.getWeightDefense());
        snapshot.put("code", offering.getWeightCode());
        snapshot.put("report", offering.getWeightReport());
        for (Selection selection : selections) {
            Topic topic = topicMapper.selectById(selection.getTopicId());
            BigDecimal code = clampScore(averageScore(List.of(selection), "SOURCE"));
            BigDecimal report = clampScore(averageScore(List.of(selection), "REPORT"));
            GradeItem item = new GradeItem();
            item.setGradeId(grade.getId());
            item.setSelectionId(selection.getId());
            item.setTopicTitle(topic == null ? "" : topic.getTitle());
            item.setCodeScore(code);
            item.setReportScore(report);
            item.setDefenseScore(clampScore(defense));
            item.setFinalScore(clampScore(weighted(offering, defense, code, report)));
            item.setWeights(json.write(snapshot));
            item.setCreatedAt(Times.now());
            item.setUpdatedAt(Times.now());
            gradeItemMapper.insert(item);
        }
    }

    private BigDecimal averageScore(List<Selection> selections, String type) {
        List<BigDecimal> scores = new ArrayList<>();
        for (Selection selection : selections) {
            Submission submission = submissionMapper.selectOne(new LambdaQueryWrapper<Submission>()
                    .eq(Submission::getSelectionId, selection.getId())
                    .eq(Submission::getType, type).last("limit 1"));
            if (submission == null) {
                continue;
            }
            BigDecimal score = "SOURCE".equals(type)
                    ? analysisScore(submission.getId()) : evaluationScore(submission.getId());
            if (score != null) {
                scores.add(score);
            }
        }
        return GradeMath.average(scores);
    }

    private BigDecimal analysisScore(Long submissionId) {
        CodeAnalysis analysis = codeAnalysisMapper.selectOne(new LambdaQueryWrapper<CodeAnalysis>()
                .eq(CodeAnalysis::getSubmissionId, submissionId).last("limit 1"));
        return analysis == null || !"SUCCEEDED".equals(analysis.getStatus()) ? null : analysis.getScore();
    }

    private BigDecimal evaluationScore(Long submissionId) {
        ReportEvaluation evaluation = reportEvaluationMapper.selectOne(
                new LambdaQueryWrapper<ReportEvaluation>()
                        .eq(ReportEvaluation::getSubmissionId, submissionId).last("limit 1"));
        return evaluation == null || !"SUCCEEDED".equals(evaluation.getStatus()) ? null : evaluation.getScore();
    }

    private BigDecimal defenseScore(Long offeringId, Long studentId) {
        DefenseSession session = defenseSessionMapper.selectOne(new LambdaQueryWrapper<DefenseSession>()
                .eq(DefenseSession::getOfferingId, offeringId)
                .eq(DefenseSession::getStudentId, studentId)
                .eq(DefenseSession::getStatus, "EVALUATED").last("limit 1"));
        return session == null || session.getScore() == null
                ? BigDecimal.ZERO.setScale(1) : session.getScore();
    }

    /** 权重折算统一走 {@link GradeMath#weighted}，本类只保留「开课 → 三个权重」的适配。 */
    private BigDecimal weighted(Offering offering, BigDecimal defense,
                                BigDecimal code, BigDecimal report) {
        return GradeMath.weighted(offering.getWeightDefense(), offering.getWeightCode(),
                offering.getWeightReport(), defense, code, report);
    }

    /** 人工改分只接受 0~100 的显式分数：越界属于客户端错误，直接拒绝而不是静默夹取。 */
    private void validateScore(BigDecimal score) {
        BizAssert.param(score != null
                        && score.compareTo(GradeMath.clamp(score, BigDecimal.ZERO, HUNDRED)) == 0,
                "分数需在 0~100 之间");
    }

    /** 分项分 / 总评入库前的防御性夹取。 */
    private BigDecimal clampScore(BigDecimal score) {
        return GradeMath.clamp(score, BigDecimal.ZERO, HUNDRED);
    }

    private OfferingDtos.Weights weights(Offering offering) {
        return new OfferingDtos.Weights(offering.getWeightDefense(), offering.getWeightCode(),
                offering.getWeightReport());
    }

    private Grade ownedGrade(String gradeId) {
        Grade grade = BizAssert.found(
                gradeMapper.selectById(BizAssert.id(gradeId, "gradeId")), "成绩");
        offeringService.ownedOffering(String.valueOf(grade.getOfferingId()));
        return grade;
    }

    private GradeDtos.GradeListItemVO listItemVo(Grade grade, Map<Long, SysUser> students,
                                                 Map<Long, Long> selectionCounts) {
        SysUser student = students.get(grade.getStudentId());
        Long topicCount = selectionCounts.get(grade.getStudentId());
        return new GradeDtos.GradeListItemVO(String.valueOf(grade.getId()),
                String.valueOf(grade.getStudentId()),
                student == null ? "" : student.getStudentNo(),
                student == null ? "" : student.getName(),
                student == null ? "" : student.getClassName(),
                topicCount == null ? 0 : topicCount.intValue(),
                grade.getDefenseScore(), grade.getCodeScore(), grade.getReportScore(),
                grade.getFinalScore(), grade.getLevel(), grade.getStatus(),
                Integer.valueOf(1).equals(grade.getAdjusted()), grade.getPublishedAt() != null,
                Times.offset(grade.getUpdatedAt()));
    }

    private GradeDtos.StudentGradeVO studentVo(Grade grade, Map<Long, Offering> offerings,
                                               Map<Long, Course> courses,
                                               Map<Long, List<GradeItem>> items) {
        Offering offering = offerings.get(grade.getOfferingId());
        Course course = offering == null ? null : courses.get(offering.getCourseId());
        List<GradeItem> gradeItems = items.getOrDefault(grade.getId(), List.of());
        return new GradeDtos.StudentGradeVO(String.valueOf(grade.getId()),
                course == null ? "" : course.getName(), offering == null ? "" : offering.getSemester(),
                grade.getFinalScore(), grade.getLevel(), offering == null ? null : weights(offering),
                grade.getDefenseScore(), grade.getCodeScore(), grade.getReportScore(),
                gradeItems.stream().map(i -> new GradeDtos.StudentGradeItemVO(i.getTopicTitle(),
                        i.getCodeScore(), i.getReportScore(), i.getDefenseScore(), i.getFinalScore())).toList(),
                grade.getTeacherComment(), Times.offset(grade.getPublishedAt()));
    }

    // ---------------------------------------------------------------- 批量查询（消除 N+1）

    /** 批量取学生；调用方负责传入去重后的 id，缺失的学生不会出现在 Map 里。 */
    private Map<Long, SysUser> studentMap(List<Long> studentIds) {
        if (studentIds == null || studentIds.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
    }

    /**
     * 一次分组查询得到「学生 → 该开课下有效选题数」。
     *
     * <p>按 offering_id 过滤是因为调用方都是单开课场景（成绩列表 / 成绩单导出），
     * 这样查询能命中 idx_selection_offering，且不必把整表选题拉进内存。</p>
     */
    private Map<Long, Long> selectionCountMap(Long offeringId, List<Long> studentIds) {
        if (offeringId == null || studentIds == null || studentIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = selectionMapper.selectMaps(new QueryWrapper<Selection>()
                .select("student_id", "count(*) AS c")
                .eq("offering_id", offeringId)
                .in("student_id", studentIds)
                .ne("status", "CANCELED")
                .groupBy("student_id"));
        return indexGroupedCount(rows, "student_id");
    }

    /** 一次查出本页成绩的全部分项明细，再按 gradeId 分组。 */
    private Map<Long, List<GradeItem>> gradeItemMap(List<Long> gradeIds) {
        if (gradeIds == null || gradeIds.isEmpty()) {
            return Map.of();
        }
        return gradeItemMapper.selectList(new LambdaQueryWrapper<GradeItem>()
                        .in(GradeItem::getGradeId, gradeIds)).stream()
                .collect(Collectors.groupingBy(GradeItem::getGradeId));
    }

    /** 分组 count 结果 → Map<分组键, 计数>；列名大小写在 MySQL / H2 下可能不同。 */
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
}
