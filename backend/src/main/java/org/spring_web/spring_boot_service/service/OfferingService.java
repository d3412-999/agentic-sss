package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.dto.OfferingDtos;
import org.spring_web.spring_boot_service.entity.Course;
import org.spring_web.spring_boot_service.entity.Grade;
import org.spring_web.spring_boot_service.entity.Offering;
import org.spring_web.spring_boot_service.entity.OfferingStudent;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.mapper.CourseMapper;
import org.spring_web.spring_boot_service.mapper.GradeMapper;
import org.spring_web.spring_boot_service.mapper.OfferingMapper;
import org.spring_web.spring_boot_service.mapper.OfferingStudentMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.spring_web.spring_boot_service.mapper.TopicMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 课程模板、开课生命周期与学生端可选开课。
 *
 * <p>状态机：DRAFT → PUBLISHED → CLOSED → ARCHIVED（单向）。
 * 所有教师侧方法都先经 {@link #ownedOffering} 校验 teacherId，URL 层的 hasRole 只是第一道门。</p>
 */
@Service
@RequiredArgsConstructor
public class OfferingService {

    private final CourseMapper courseMapper;
    private final OfferingMapper offeringMapper;
    private final OfferingStudentMapper offeringStudentMapper;
    private final TopicMapper topicMapper;
    private final SelectionMapper selectionMapper;
    private final SubmissionMapper submissionMapper;
    private final GradeMapper gradeMapper;
    private final SysUserMapper sysUserMapper;
    private final AuditLogService auditLog;
    private final NotificationService notificationService;

    // ---------------------------------------------------------------- 课程模板

    public PageResult<OfferingDtos.CourseVO> courses(String keyword, long page, long size) {
        Page<Course> result = courseMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<Course>()
                        .like(StringUtils.hasText(keyword), Course::getName, keyword)
                        .orderByDesc(Course::getId));
        List<OfferingDtos.CourseVO> list = result.getRecords().stream().map(this::courseVo).toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    public OfferingDtos.CourseVO createCourse(OfferingDtos.CourseCreateRequest request) {
        Long exists = courseMapper.selectCount(new LambdaQueryWrapper<Course>()
                .eq(Course::getCode, request.code()));
        BizAssert.state(exists == null || exists == 0, "课程代码已存在");
        Course course = new Course();
        course.setCode(request.code());
        course.setName(request.name());
        course.setCredit(request.credit() == null ? BigDecimal.ZERO : request.credit());
        course.setDescription(request.description());
        course.setCreatedBy(SecurityUtils.userId());
        course.setCreatedAt(Times.now());
        course.setUpdatedAt(Times.now());
        courseMapper.insert(course);
        return courseVo(course);
    }

    private OfferingDtos.CourseVO courseVo(Course course) {
        return new OfferingDtos.CourseVO(String.valueOf(course.getId()), course.getCode(),
                course.getName(), course.getCredit(), course.getDescription());
    }

    // ---------------------------------------------------------------- 教师开课

    public PageResult<OfferingDtos.OfferingListItemVO> teacherOfferings(
            String semester, String status, String keyword, long page, long size) {
        Page<Offering> result = offeringMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<Offering>()
                        .eq(Offering::getTeacherId, SecurityUtils.userId())
                        .eq(StringUtils.hasText(semester), Offering::getSemester, semester)
                        .eq(StringUtils.hasText(status), Offering::getStatus, status)
                        .like(StringUtils.hasText(keyword), Offering::getClassName, keyword)
                        .orderByDesc(Offering::getId));
        List<OfferingDtos.OfferingListItemVO> list = result.getRecords().stream()
                .map(this::listItemVo).toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    public OfferingDtos.OfferingVO detail(String offeringId) {
        return toVo(ownedOffering(offeringId));
    }

    /** 数据级越权校验：教师只能碰自己的开课。其他 Service 也复用这个方法。 */
    public Offering ownedOffering(String offeringId) {
        Long id = BizAssert.id(offeringId, "offeringId");
        Offering offering = BizAssert.found(offeringMapper.selectById(id), "开课");
        SecurityUtils.assertOwner(offering.getTeacherId(), "开课");
        return offering;
    }

    /** 只判断不抛异常，供「教师本人或已选该题学生」这类二选一可见性场景使用。 */
    public boolean isOwner(Long offeringId, Long userId) {
        Offering offering = offeringMapper.selectById(offeringId);
        return offering != null && offering.getTeacherId() != null
                && offering.getTeacherId().equals(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public OfferingDtos.OfferingVO create(OfferingDtos.OfferingCreateRequest request) {
        validateWeights(request.weights());
        BizAssert.param(Times.validSemester(request.semester()), "学期格式应为 YYYY-YYYY-N，且年份连续");
        Long courseId = BizAssert.id(request.courseId(), "courseId");
        BizAssert.found(courseMapper.selectById(courseId), "课程");

        int limit = request.topicSelectLimit() == null ? 3 : request.topicSelectLimit();
        BizAssert.param(limit >= 1 && limit <= 10, "每人限选题目数需在 1~10 之间");

        Long duplicate = offeringMapper.selectCount(new LambdaQueryWrapper<Offering>()
                .eq(Offering::getTeacherId, SecurityUtils.userId())
                .eq(Offering::getSemester, request.semester())
                .eq(Offering::getClassName, request.className()));
        BizAssert.state(duplicate == null || duplicate == 0, "该学期下已存在同名教学班");

        Offering offering = new Offering();
        offering.setCourseId(courseId);
        offering.setTeacherId(SecurityUtils.userId());
        offering.setSemester(request.semester());
        offering.setClassName(request.className());
        offering.setDescription(request.description());
        offering.setStatus("DRAFT");
        offering.setTopicSelectLimit(limit);
        offering.setVersion(0);
        applyWeights(offering, request.weights());
        applyDefenseConfig(offering, request.defenseConfig());
        applyTimeline(offering, request.timeline());
        validateTimeline(offering);
        offering.setCreatedAt(Times.now());
        offering.setUpdatedAt(Times.now());
        offeringMapper.insert(offering);

        auditLog.record("OFFERING_CREATE", "OFFERING", offering.getId(), null, toVo(offering), null);
        return toVo(offering);
    }

    @Transactional(rollbackFor = Exception.class)
    public OfferingDtos.OfferingVO patch(String offeringId, OfferingDtos.OfferingPatchRequest request) {
        Offering offering = ownedOffering(offeringId);
        BizAssert.state(request.version().equals(offering.getVersion()),
                "开课配置已被他人修改，请刷新后重试");
        OfferingDtos.OfferingVO before = toVo(offering);
        boolean published = "PUBLISHED".equals(offering.getStatus());

        if (published) {
            // 已发布：只允许延后时间、调整答辩配置与说明，禁止改权重/限选数/班级
            BizAssert.state(request.weights() == null && request.topicSelectLimit() == null
                            && request.className() == null,
                    "已发布的开课不可修改权重、限选数或教学班");
            if (request.timeline() != null) {
                validateTimelineExtension(offering, request.timeline());
                applyTimeline(offering, request.timeline());
            }
        } else {
            BizAssert.state("DRAFT".equals(offering.getStatus()), "当前状态不允许修改");
            if (request.weights() != null) {
                validateWeights(request.weights());
                applyWeights(offering, request.weights());
            }
            if (request.topicSelectLimit() != null) {
                BizAssert.param(request.topicSelectLimit() >= 1 && request.topicSelectLimit() <= 10,
                        "每人限选题目数需在 1~10 之间");
                offering.setTopicSelectLimit(request.topicSelectLimit());
            }
            if (request.className() != null) {
                offering.setClassName(request.className());
            }
            if (request.timeline() != null) {
                applyTimeline(offering, request.timeline());
                validateTimeline(offering);
            }
        }
        if (request.description() != null) {
            offering.setDescription(request.description());
        }
        if (request.defenseConfig() != null) {
            applyDefenseConfig(offering, request.defenseConfig());
        }
        offering.setVersion(request.version());
        offering.setUpdatedAt(Times.now());
        if (offeringMapper.updateById(offering) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "开课配置已被他人修改，请刷新后重试");
        }
        auditLog.record("OFFERING_UPDATE", "OFFERING", offering.getId(), before, toVo(offering), null);
        return toVo(offering);
    }

    @Transactional(rollbackFor = Exception.class)
    public OfferingDtos.OfferingVO publish(String offeringId) {
        Offering offering = ownedOffering(offeringId);
        BizAssert.state("DRAFT".equals(offering.getStatus()), "只有草稿状态的开课可以发布");

        List<OfferingDtos.Blocker> blockers = publishBlockers(offering);
        if (!blockers.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE, "开课发布条件未满足",
                    Map.of("blockers", blockers));
        }
        OfferingDtos.OfferingVO before = toVo(offering);
        offering.setStatus("PUBLISHED");
        offering.setPublishedAt(Times.now());
        offering.setUpdatedAt(Times.now());
        if (offeringMapper.updateById(offering) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "开课状态已变更，请刷新后重试");
        }
        auditLog.record("OFFERING_PUBLISH", "OFFERING", offering.getId(), before, toVo(offering), null);
        notifyStudents(offering, "SELECTION_OPEN", "选题已开放", "课程设计选题窗口已开放，请及时选题。");
        return toVo(offering);
    }

    private List<OfferingDtos.Blocker> publishBlockers(Offering offering) {
        List<OfferingDtos.Blocker> blockers = new ArrayList<>();
        Long students = offeringStudentMapper.selectCount(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, offering.getId())
                .eq(OfferingStudent::getStatus, "ACTIVE"));
        if (students == null || students == 0) {
            blockers.add(new OfferingDtos.Blocker("NO_STUDENT", "尚未导入学生名单"));
        }
        Long topics = topicMapper.selectCount(new LambdaQueryWrapper<org.spring_web.spring_boot_service.entity.Topic>()
                .eq(org.spring_web.spring_boot_service.entity.Topic::getOfferingId, offering.getId())
                .eq(org.spring_web.spring_boot_service.entity.Topic::getStatus, "PUBLISHED"));
        if (topics == null || topics == 0) {
            blockers.add(new OfferingDtos.Blocker("NO_TOPIC", "尚未发布任何题目"));
        }
        if (offering.getSubmitDeadline() == null || !offering.getSubmitDeadline().isAfter(Times.now())) {
            blockers.add(new OfferingDtos.Blocker("DEADLINE_INVALID", "提交截止时间必须晚于当前时间"));
        }
        if (sum(offering.getWeightDefense(), offering.getWeightCode(), offering.getWeightReport()) != 100) {
            blockers.add(new OfferingDtos.Blocker("WEIGHT_INVALID", "三项权重之和必须为 100"));
        }
        return blockers;
    }

    @Transactional(rollbackFor = Exception.class)
    public OfferingDtos.OfferingVO close(String offeringId) {
        return transition(offeringId, "PUBLISHED", "CLOSED", "OFFERING_CLOSE");
    }

    @Transactional(rollbackFor = Exception.class)
    public OfferingDtos.OfferingVO archive(String offeringId) {
        Offering offering = ownedOffering(offeringId);
        BizAssert.state("CLOSED".equals(offering.getStatus()) || "PUBLISHED".equals(offering.getStatus()),
                "只有已发布或已结课的开课可以归档");
        OfferingDtos.OfferingVO before = toVo(offering);
        offering.setStatus("ARCHIVED");
        offering.setUpdatedAt(Times.now());
        if (offeringMapper.updateById(offering) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "开课状态已变更，请刷新后重试");
        }
        auditLog.record("OFFERING_ARCHIVE", "OFFERING", offering.getId(), before, toVo(offering), null);
        return toVo(offering);
    }

    private OfferingDtos.OfferingVO transition(String id, String expected, String target, String action) {
        Offering offering = ownedOffering(id);
        BizAssert.state(expected.equals(offering.getStatus()),
                "当前状态不允许此操作，需要 " + expected);
        OfferingDtos.OfferingVO before = toVo(offering);
        offering.setStatus(target);
        offering.setUpdatedAt(Times.now());
        if (offeringMapper.updateById(offering) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "开课状态已变更，请刷新后重试");
        }
        auditLog.record(action, "OFFERING", offering.getId(), before, toVo(offering), null);
        return toVo(offering);
    }

    // ---------------------------------------------------------------- 仪表盘

    public OfferingDtos.DashboardVO dashboard(String offeringId) {
        Offering offering = ownedOffering(offeringId);
        long studentCount = count(offeringStudentMapper.selectCount(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, offering.getId())));
        List<Selection> selections = selectionMapper.selectList(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getOfferingId, offering.getId())
                .ne(Selection::getStatus, "CANCELED"));
        long selectionCount = selections.size();
        long selectedStudentCount = selections.stream().map(Selection::getStudentId).distinct().count();
        long sourceSubmitted = 0;
        long reportSubmitted = 0;
        if (!selections.isEmpty()) {
            List<Long> ids = selections.stream().map(Selection::getId).toList();
            sourceSubmitted = count(submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                    .in(Submission::getSelectionId, ids).eq(Submission::getType, "SOURCE")));
            reportSubmitted = count(submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                    .in(Submission::getSelectionId, ids).eq(Submission::getType, "REPORT")));
        }
        double submissionRate = studentCount == 0 ? 0
                : round((double) (sourceSubmitted + reportSubmitted) / (studentCount * 2) * 100);

        List<Grade> grades = gradeMapper.selectList(new LambdaQueryWrapper<Grade>()
                .eq(Grade::getOfferingId, offering.getId()));
        long defenseFinished = grades.stream().filter(g -> g.getDefenseScore() != null).count();
        BigDecimal avgFinal = average(grades, Grade::getFinalScore);
        BigDecimal avgDefense = average(grades, Grade::getDefenseScore);
        BigDecimal avgCode = average(grades, Grade::getCodeScore);
        BigDecimal avgReport = average(grades, Grade::getReportScore);

        Map<String, Long> distribution = new LinkedHashMap<>();
        for (String range : List.of("0-59", "60-69", "70-79", "80-89", "90-100")) {
            distribution.put(range, 0L);
        }
        for (Grade grade : grades) {
            if (grade.getFinalScore() != null) {
                distribution.merge(bucket(grade.getFinalScore()), 1L, Long::sum);
            }
        }
        return new OfferingDtos.DashboardVO(studentCount, selectedStudentCount, selectionCount,
                submissionRate, sourceSubmitted, reportSubmitted, defenseFinished,
                Math.max(0, studentCount - defenseFinished),
                new OfferingDtos.AverageScore(avgDefense, avgCode, avgReport, avgFinal),
                distribution.entrySet().stream()
                        .map(e -> new OfferingDtos.Distribution(e.getKey(), e.getValue())).toList(),
                List.of());
    }

    // ---------------------------------------------------------------- 学生端

    public PageResult<OfferingDtos.StudentOfferingVO> studentOfferings(
            String semester, String keyword, long page, long size) {
        Long studentId = SecurityUtils.userId();
        List<OfferingStudent> relations = offeringStudentMapper.selectList(
                new LambdaQueryWrapper<OfferingStudent>()
                        .eq(OfferingStudent::getStudentId, studentId)
                        .eq(OfferingStudent::getStatus, "ACTIVE"));
        if (relations.isEmpty()) {
            return PageResult.of(List.of(), 0, page, size);
        }
        List<Long> ids = relations.stream().map(OfferingStudent::getOfferingId).distinct().toList();
        Page<Offering> result = offeringMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<Offering>()
                        .in(Offering::getId, ids)
                        .eq(Offering::getStatus, "PUBLISHED")
                        .eq(StringUtils.hasText(semester), Offering::getSemester, semester)
                        .like(StringUtils.hasText(keyword), Offering::getClassName, keyword)
                        .orderByDesc(Offering::getId));
        List<OfferingDtos.StudentOfferingVO> list = result.getRecords().stream()
                .map(o -> studentOfferingVo(o, studentId)).toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    private OfferingDtos.StudentOfferingVO studentOfferingVo(Offering offering, Long studentId) {
        Course course = courseMapper.selectById(offering.getCourseId());
        SysUser teacher = sysUserMapper.selectById(offering.getTeacherId());
        Long myCount = selectionMapper.selectCount(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getOfferingId, offering.getId())
                .eq(Selection::getStudentId, studentId)
                .ne(Selection::getStatus, "CANCELED"));
        int selected = myCount == null ? 0 : myCount.intValue();
        String reason = null;
        if (!inSelectWindow(offering)) {
            reason = "NOT_IN_WINDOW";
        } else if (selected >= offering.getTopicSelectLimit()) {
            reason = "LIMIT_REACHED";
        }
        if (!"PUBLISHED".equals(offering.getStatus())) {
            reason = "CLOSED";
        }
        return new OfferingDtos.StudentOfferingVO(String.valueOf(offering.getId()),
                course == null ? "" : course.getName(),
                teacher == null ? "" : teacher.getName(), offering.getSemester(),
                offering.getClassName(), Times.offset(offering.getSelectStart()),
                Times.offset(offering.getSelectEnd()), Times.offset(offering.getSubmitDeadline()),
                offering.getTopicSelectLimit(), selected, reason == null, reason);
    }

    public boolean inSelectWindow(Offering offering) {
        var now = Times.now();
        return offering.getSelectStart() != null && offering.getSelectEnd() != null
                && !now.isBefore(offering.getSelectStart()) && !now.isAfter(offering.getSelectEnd());
    }

    // ---------------------------------------------------------------- 内部工具

    private void notifyStudents(Offering offering, String type, String title, String content) {
        List<OfferingStudent> students = offeringStudentMapper.selectList(
                new LambdaQueryWrapper<OfferingStudent>()
                        .eq(OfferingStudent::getOfferingId, offering.getId())
                        .eq(OfferingStudent::getStatus, "ACTIVE"));
        for (OfferingStudent student : students) {
            notificationService.push(student.getStudentId(), type, title, content,
                    "/student/courses/" + offering.getId() + "/topics");
        }
    }

    private void validateWeights(OfferingDtos.Weights weights) {
        if (weights == null || sum(weights.defense(), weights.code(), weights.report()) != 100) {
            // 与文档一致：40001 且 data.errors 给出字段级原因，前端表单可直接回显
            throw new BizException(ErrorCode.PARAM_INVALID, "参数校验失败",
                    Map.of("errors", List.of(
                            new CommonDtos.FieldError("weights", "三项权重之和必须为 100"))));
        }
    }

    private int sum(Integer... values) {
        int total = 0;
        for (Integer value : values) {
            total += value == null ? 0 : value;
        }
        return total;
    }

    private void applyWeights(Offering offering, OfferingDtos.Weights weights) {
        offering.setWeightDefense(weights.defense());
        offering.setWeightCode(weights.code());
        offering.setWeightReport(weights.report());
    }

    private void applyDefenseConfig(Offering offering, OfferingDtos.DefenseConfig config) {
        OfferingDtos.DefenseConfig effective = config == null
                ? new OfferingDtos.DefenseConfig("PER_STUDENT", 6, 180, false, "zh-CN", false)
                : config;
        int questions = effective.questionCount() == null ? 6 : effective.questionCount();
        int seconds = effective.maxAnswerSeconds() == null ? 180 : effective.maxAnswerSeconds();
        BizAssert.param(questions >= 3 && questions <= 12, "答辩题目数需在 3~12 之间");
        BizAssert.param(seconds >= 60 && seconds <= 600, "单题作答时限需在 60~600 秒之间");
        offering.setDefenseMode(StringUtils.hasText(effective.defenseMode())
                ? effective.defenseMode() : "PER_STUDENT");
        offering.setQuestionCount(questions);
        offering.setMaxAnswerSeconds(seconds);
        offering.setAllowRetry(Boolean.TRUE.equals(effective.allowRetry()) ? 1 : 0);
        offering.setEnableVideo(Boolean.TRUE.equals(effective.enableVideo()) ? 1 : 0);
        offering.setLanguage(StringUtils.hasText(effective.language()) ? effective.language() : "zh-CN");
    }

    private void applyTimeline(Offering offering, OfferingDtos.Timeline timeline) {
        if (timeline == null) {
            return;
        }
        offering.setSelectStart(Times.startOfDay(timeline.selectStart()));
        offering.setSelectEnd(Times.endOfDay(timeline.selectEnd()));
        offering.setSubmitDeadline(Times.endOfDay(timeline.submitDeadline()));
        offering.setDefenseStart(Times.startOfDay(timeline.defenseStart()));
        offering.setDefenseEnd(Times.endOfDay(timeline.defenseEnd()));
    }

    /** 时间链单调：选题开始 ≤ 选题结束 ≤ 提交截止 ≤ 答辩开始 ≤ 答辩结束。 */
    private void validateTimeline(Offering offering) {
        var chain = List.of(offering.getSelectStart(), offering.getSelectEnd(),
                offering.getSubmitDeadline(), offering.getDefenseStart(), offering.getDefenseEnd());
        for (int i = 1; i < chain.size(); i++) {
            if (chain.get(i - 1) != null && chain.get(i) != null
                    && chain.get(i).isBefore(chain.get(i - 1))) {
                throw new BizException(ErrorCode.PARAM_INVALID, "时间节点顺序不合法",
                        Map.of("errors", List.of(new CommonDtos.FieldError("timeline",
                                "时间需满足：选题开始 ≤ 选题结束 ≤ 提交截止 ≤ 答辩开始 ≤ 答辩结束"))));
            }
        }
    }

    /** 已发布后时间只能延后，不能提前（否则学生会错过已承诺的窗口）。 */
    private void validateTimelineExtension(Offering offering, OfferingDtos.Timeline timeline) {
        if (timeline.selectEnd() != null && offering.getSelectEnd() != null
                && Times.endOfDay(timeline.selectEnd()).isBefore(offering.getSelectEnd())) {
            BizAssert.state(false, "已发布的选题截止时间只能延后");
        }
        if (timeline.submitDeadline() != null && offering.getSubmitDeadline() != null
                && Times.endOfDay(timeline.submitDeadline()).isBefore(offering.getSubmitDeadline())) {
            BizAssert.state(false, "已发布的提交截止时间只能延后");
        }
    }

    private OfferingDtos.OfferingVO toVo(Offering o) {
        Course course = courseMapper.selectById(o.getCourseId());
        long studentCount = count(offeringStudentMapper.selectCount(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, o.getId())));
        long topicCount = count(topicMapper.selectCount(
                new LambdaQueryWrapper<org.spring_web.spring_boot_service.entity.Topic>()
                        .eq(org.spring_web.spring_boot_service.entity.Topic::getOfferingId, o.getId())));
        long selectionCount = count(selectionMapper.selectCount(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getOfferingId, o.getId()).ne(Selection::getStatus, "CANCELED")));
        long defenseFinished = count(gradeMapper.selectCount(new LambdaQueryWrapper<Grade>()
                .eq(Grade::getOfferingId, o.getId()).isNotNull(Grade::getDefenseScore)));
        return new OfferingDtos.OfferingVO(String.valueOf(o.getId()), String.valueOf(o.getCourseId()),
                course == null ? "" : course.getName(), o.getSemester(), o.getClassName(),
                o.getDescription(), o.getStatus(), o.getTopicSelectLimit(),
                new OfferingDtos.Weights(o.getWeightDefense(), o.getWeightCode(), o.getWeightReport()),
                new OfferingDtos.DefenseConfig(o.getDefenseMode(), o.getQuestionCount(),
                        o.getMaxAnswerSeconds(), Integer.valueOf(1).equals(o.getAllowRetry()),
                        o.getLanguage(), Integer.valueOf(1).equals(o.getEnableVideo())),
                new OfferingDtos.Timeline(date(o.getSelectStart()), date(o.getSelectEnd()),
                        date(o.getSubmitDeadline()), date(o.getDefenseStart()), date(o.getDefenseEnd())),
                Times.offset(o.getPublishedAt()), o.getVersion(),
                new OfferingDtos.Stats(studentCount, topicCount, selectionCount, defenseFinished));
    }

    private OfferingDtos.OfferingListItemVO listItemVo(Offering o) {
        OfferingDtos.OfferingVO vo = toVo(o);
        return new OfferingDtos.OfferingListItemVO(vo.id(), vo.courseName(), vo.semester(),
                vo.className(), vo.status(), vo.stats().studentCount(), vo.stats().topicCount(),
                vo.stats().selectionCount(), vo.stats().defenseFinishedCount(), Times.offset(o.getCreatedAt()));
    }

    private LocalDate date(java.time.LocalDateTime value) {
        return value == null ? null : value.toLocalDate();
    }

    private long count(Long value) {
        return value == null ? 0 : value;
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal average(List<Grade> grades,
                               java.util.function.Function<Grade, BigDecimal> extractor) {
        List<BigDecimal> values = grades.stream().map(extractor)
                .filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) {
            return BigDecimal.ZERO.setScale(1);
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
    }

    private String bucket(BigDecimal score) {
        int value = score.intValue();
        if (value >= 90) {
            return "90-100";
        }
        if (value >= 80) {
            return "80-89";
        }
        if (value >= 70) {
            return "70-79";
        }
        if (value >= 60) {
            return "60-69";
        }
        return "0-59";
    }
}
