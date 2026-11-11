package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.client.AiServiceClient;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.GradeMath;
import org.spring_web.spring_boot_service.common.ScoreGuard;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.dto.DefenseDtos;
import org.spring_web.spring_boot_service.entity.Course;
import org.spring_web.spring_boot_service.entity.DefenseAnswer;
import org.spring_web.spring_boot_service.entity.DefenseQuestion;
import org.spring_web.spring_boot_service.entity.DefenseSession;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.entity.Offering;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.SubmissionVersion;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.entity.Topic;
import org.spring_web.spring_boot_service.mapper.CourseMapper;
import org.spring_web.spring_boot_service.mapper.DefenseAnswerMapper;
import org.spring_web.spring_boot_service.mapper.DefenseQuestionMapper;
import org.spring_web.spring_boot_service.mapper.DefenseSessionMapper;
import org.spring_web.spring_boot_service.mapper.FileObjectMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionVersionMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.spring_web.spring_boot_service.mapper.TopicMapper;
import org.spring_web.spring_boot_service.security.LoginUser;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 答辩主链路：出题 → 逐题录音作答（ASR 转写）→ 摄像头在场检测 → 结束 → AI 评分。
 *
 * <p>状态机严格单向：{@code CREATED → READY → IN_PROGRESS → COMPLETED → EVALUATED}；
 * 创建会话时同步调用 AI 出题，成功后直接落 {@code READY}（Java 侧不再有 GENERATING 中间态）。
 * 所有写操作先做会话归属校验，再按当前状态放行。</p>
 *
 * <p>两条「不造假」底线：</p>
 * <ul>
 *   <li>ASR 失败：作答标记 FAILED 并抛 50301，绝不写一段假转写；</li>
 *   <li>AI 评分失败 / 分数非法：保持 COMPLETED 原状并抛 50301，绝不落一个占位分。</li>
 * </ul>
 *
 * <p>隐私：人脸只判断「画面里有没有人」，图片用完即弃，不落库、不写磁盘；
 * 仅记录 {@code facePresent / faceCheckedAt / faceMissCount}。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefenseService {

    /** 一个学生一次答辩只允许一个活跃会话。 */
    private static final List<String> ACTIVE_SESSION_STATUSES = List.of("CREATED", "READY", "IN_PROGRESS");
    /** 单题录音上限：契约 B4 为 50MB。 */
    private static final long MAX_AUDIO_BYTES = 50L * 1024 * 1024;
    /** 整场答辩录像上限：300MB（MediaRecorder 的 webm 一般几 MB~几十 MB，留足余量）。 */
    private static final long MAX_VIDEO_BYTES = 300L * 1024 * 1024;
    /** 允许的录像扩展名（浏览器偶尔不给 video/* 的 content-type，用扩展名兜底）。 */
    private static final List<String> VIDEO_EXTENSIONS = List.of(".webm", ".mp4");
    private static final String DEFAULT_LANGUAGE = "zh-CN";
    private static final String DEFAULT_QUESTION_TYPE = "DESIGN";
    private static final String DEFAULT_DIFFICULTY = "MEDIUM";
    private static final int DEFAULT_QUESTION_COUNT = 6;
    private static final int DEFAULT_ANSWER_SECONDS = 180;
    /** 在场检测单帧 base64 长度上限（约 4MB 字符 ≈ 3MB 图片），防止超大 JSON 撑爆内存。 */
    private static final long MAX_FACE_IMAGE_BASE64_CHARS = 4L * 1024 * 1024;
    /** 报告正文塞进 prompt 的上限，避免一份超长报告把出题请求撑爆。 */
    private static final int MAX_REPORT_CHARS = 8000;
    private static final String STATUS_TRANSCRIBED = "TRANSCRIBED";
    private static final String STATUS_FAILED = "FAILED";

    private final DefenseSessionMapper sessionMapper;
    private final DefenseQuestionMapper questionMapper;
    private final DefenseAnswerMapper answerMapper;
    private final SelectionMapper selectionMapper;
    private final TopicMapper topicMapper;
    private final CourseMapper courseMapper;
    private final SysUserMapper sysUserMapper;
    private final SubmissionMapper submissionMapper;
    private final SubmissionVersionMapper versionMapper;
    private final FileObjectMapper fileObjectMapper;
    private final FileStorageService fileStorage;
    private final AiServiceClient aiClient;
    private final NotificationService notificationService;
    private final OfferingService offeringService;
    private final JsonCodec json;
    private final PlatformTransactionManager transactionManager;

    // ---------------------------------------------------------------- 教师端：创建 / 列表 / 候选

    /**
     * 教师为某个选题发起答辩：AI 出题 → 落题目与会话 → 选题置 DEFENDING → 通知学生。
     *
     * <p>AI 调用放在事务外、DB 落库放在 {@link TransactionTemplate} 里：
     * AI 失败（50301）时不会留下半个会话；落库阶段则要么全部成功、要么全部回滚。</p>
     */
    public DefenseDtos.SessionVO createSession(String selectionId, Integer questionCount) {
        Long sid = BizAssert.id(selectionId, "selectionId");
        Selection selection = BizAssert.found(selectionMapper.selectById(sid), "选题");
        BizAssert.rule(!"CANCELED".equals(selection.getStatus()), "该选题已退选，不能发起答辩");
        Offering offering = offeringService.ownedOffering(String.valueOf(selection.getOfferingId()));

        Long active = sessionMapper.selectCount(new LambdaQueryWrapper<DefenseSession>()
                .eq(DefenseSession::getOfferingId, offering.getId())
                .eq(DefenseSession::getStudentId, selection.getStudentId())
                .in(DefenseSession::getStatus, ACTIVE_SESSION_STATUSES));
        BizAssert.state(active == null || active == 0, "该学生已有进行中的答辩会话，请先完成或结束");

        int count = resolveQuestionCount(offering, questionCount);
        Map<String, Object> payload = buildQuestionPayload(offering, selection, count);
        // AI 失败由 AiServiceClient 统一抛 50301：此时还没有任何写入，直接透传即可
        Map<String, Object> response = aiClient.generateDefenseQuestions(payload);
        List<GeneratedQuestion> generated = parseQuestions(response, offering);
        if (generated.isEmpty()) {
            throw new BizException(ErrorCode.AI_UNAVAILABLE, "AI 未返回答辩题目，请稍后重试");
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> persistSession(offering, selection, generated));
    }

    /** 教师端会话列表：按创建时间倒序，带学生信息与作答进度；题目明细留到详情接口。 */
    public List<DefenseDtos.SessionVO> list(String offeringId) {
        Offering offering = offeringService.ownedOffering(offeringId);
        List<DefenseSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<DefenseSession>()
                .eq(DefenseSession::getOfferingId, offering.getId())
                .orderByDesc(DefenseSession::getId));
        if (sessions.isEmpty()) {
            return List.of();
        }
        Map<Long, SysUser> students = studentMap(sessions.stream()
                .map(DefenseSession::getStudentId).filter(Objects::nonNull).distinct().toList());
        Map<Long, Long> answered = answeredCountMap(sessions.stream()
                .map(DefenseSession::getId).filter(Objects::nonNull).toList());
        List<DefenseDtos.SessionVO> result = new ArrayList<>();
        for (DefenseSession session : sessions) {
            result.add(toVo(session, students.get(session.getStudentId()),
                    answered.getOrDefault(session.getId(), 0L).intValue(), null));
        }
        return result;
    }

    /** 可发起答辩的选题（非 CANCELED）+ 学生信息 + 该学生最新的会话状态。 */
    public List<DefenseDtos.CandidateVO> candidates(String offeringId) {
        Offering offering = offeringService.ownedOffering(offeringId);
        List<Selection> selections = selectionMapper.selectList(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getOfferingId, offering.getId())
                .ne(Selection::getStatus, "CANCELED")
                .orderByDesc(Selection::getId));
        if (selections.isEmpty()) {
            return List.of();
        }
        Map<Long, SysUser> students = studentMap(selections.stream()
                .map(Selection::getStudentId).filter(Objects::nonNull).distinct().toList());
        List<Long> topicIds = selections.stream()
                .map(Selection::getTopicId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Topic> topics = topicIds.isEmpty() ? Map.of()
                : topicMapper.selectBatchIds(topicIds).stream()
                        .collect(Collectors.toMap(Topic::getId, Function.identity()));
        // 一个会话只覆盖一个选题（selectionIds 数组本轮固定单元素），按选题匹配才能让
        // 「同一学生的另一个选题」仍显示为可发起答辩
        Map<Long, DefenseSession> sessionsBySelection = sessionsBySelection(offering.getId());
        List<DefenseDtos.CandidateVO> result = new ArrayList<>();
        for (Selection selection : selections) {
            SysUser student = students.get(selection.getStudentId());
            Topic topic = topics.get(selection.getTopicId());
            DefenseSession session = sessionsBySelection.get(selection.getId());
            result.add(new DefenseDtos.CandidateVO(
                    String.valueOf(selection.getId()),
                    student == null || student.getStudentNo() == null ? "" : student.getStudentNo(),
                    student == null || student.getName() == null ? "" : student.getName(),
                    student == null || student.getClassName() == null ? "" : student.getClassName(),
                    topic == null || topic.getTitle() == null ? "" : topic.getTitle(),
                    selection.getStatus(),
                    session == null ? null : String.valueOf(session.getId()),
                    session == null ? null : session.getStatus()));
        }
        return result;
    }

    // ---------------------------------------------------------------- 会话详情 / 开始 / 结束

    /** 会话详情：开课所有者看完整题目（含评分要点 / 参考答案），学生本人看裁剪版。 */
    public DefenseDtos.SessionVO detail(String sessionId) {
        DefenseSession session = loadSession(sessionId);
        assertVisible(session);
        return buildDetailVo(session, isOfferingOwner(session));
    }

    /** 开始答辩：READY → IN_PROGRESS，记录开始时间。 */
    public DefenseDtos.SessionVO start(String sessionId) {
        DefenseSession session = loadSession(sessionId);
        assertVisible(session);
        BizAssert.rule("READY".equals(session.getStatus()), "当前状态不允许开始答辩");
        session.setStatus("IN_PROGRESS");
        session.setStartedAt(Times.now());
        session.setUpdatedAt(Times.now());
        updateSession(session);
        return buildDetailVo(session, isOfferingOwner(session));
    }

    /** 结束答辩：IN_PROGRESS → COMPLETED，回写时长并把选题置 DEFENDED。 */
    @Transactional(rollbackFor = Exception.class)
    public DefenseDtos.SessionVO finish(String sessionId) {
        DefenseSession session = loadSession(sessionId);
        assertVisible(session);
        BizAssert.rule("IN_PROGRESS".equals(session.getStatus()), "只有进行中的答辩可以结束");
        LocalDateTime now = Times.now();
        session.setStatus("COMPLETED");
        session.setFinishedAt(now);
        session.setDurationMs(session.getStartedAt() == null ? 0L
                : Duration.between(session.getStartedAt(), now).toMillis());
        session.setUpdatedAt(now);
        updateSession(session);

        markSelectionsDefended(session, now);
        notificationService.push(session.getStudentId(), "DEFENSE_FINISHED", "答辩已结束",
                "你的答辩已完成，教师评分后可在答辩页查看结果。", "/defense/" + session.getId());
        return buildDetailVo(session, isOfferingOwner(session));
    }

    // ---------------------------------------------------------------- 作答：上传音频 + ASR 转写

    /**
     * 上传一题的录音并同步转写。
     *
     * <p>刻意<b>不加事务</b>：AI 失败时要把作答标记为 FAILED 落库再抛 50301，
     * 如果整段包在一个事务里，抛异常会把「失败状态」一起回滚，前端就只能看到 503 而查不到原因。</p>
     */
    public DefenseDtos.QuestionVO uploadAnswer(String sessionId, String questionId,
                                               MultipartFile file, Long durationMs) {
        DefenseSession session = loadSession(sessionId);
        assertVisible(session);
        BizAssert.rule("IN_PROGRESS".equals(session.getStatus()), "答辩未开始或已结束，不能上传作答");
        Long qid = BizAssert.id(questionId, "questionId");
        DefenseQuestion question = BizAssert.found(questionMapper.selectById(qid), "答辩题目");
        BizAssert.param(question.getDefenseId().equals(session.getId()), "题目不属于该答辩会话");
        BizAssert.param(file != null && !file.isEmpty(), "音频文件不能为空");

        FileObject audio = fileStorage.store(file, "DEFENSE_AUDIO", null, MAX_AUDIO_BYTES);
        DefenseAnswer answer = upsertAnswer(session, question, audio, durationMs);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            // 已落库的作答必须收敛到 FAILED，否则会永远停在 TRANSCRIBING（前端重传可恢复，但不能留脏状态）
            markAnswerFailed(session, question, answer);
            throw new BizException(ErrorCode.FILE_INVALID, "读取音频文件失败");
        }
        Map<String, Object> response;
        try {
            response = aiClient.transcribeAudio(bytes, file.getOriginalFilename(),
                    file.getContentType(), DEFAULT_LANGUAGE);
        } catch (RuntimeException ex) {
            markAnswerFailed(session, question, answer);
            throw ex;
        }

        String transcript = stringValue(response.get("text"));
        answer.setTranscript(transcript == null ? "" : transcript);
        answer.setAsrConfidence(confidence(response.get("confidence")));
        answer.setAsrModel(stringValue(response.get("model")));
        Long audioDuration = longValue(response.get("durationMs"));
        if (audioDuration != null && audioDuration > 0) {
            answer.setDurationMs(audioDuration);
        }
        answer.setStatus(STATUS_TRANSCRIBED);
        answer.setAnsweredAt(Times.now());
        answer.setUpdatedAt(Times.now());
        answerMapper.updateById(answer);
        return toQuestionVo(question, answer, isOfferingOwner(session));
    }

    // ---------------------------------------------------------------- 整场录像：上传 / 回放

    /**
     * 上传整场答辩录像（前端 MediaRecorder 录制的 webm）。
     *
     * <p>允许在 {@code IN_PROGRESS}（结束前上传）或 {@code COMPLETED}（结束补传）两个状态上传；
     * 录像只用于教师复核，与逐题音频（ASR 用）分开存（purpose=DEFENSE_VIDEO），
     * 上传成功只更新会话的录像元数据，不改答辩状态。</p>
     *
     * <p>faceCheck 每 3 秒会更新会话，上传录像很容易撞上乐观锁：这里重载后重试一次，
     * 仍失败才抛 40901（前端可重试上传，文件存储层有秒传不会重复占盘）。</p>
     */
    public DefenseDtos.SessionVO uploadVideo(String sessionId, MultipartFile file, Long durationMs) {
        DefenseSession session = loadSession(sessionId);
        assertVisible(session);
        BizAssert.rule("IN_PROGRESS".equals(session.getStatus()) || "COMPLETED".equals(session.getStatus()),
                "当前状态不允许上传答辩录像");
        BizAssert.param(file != null && !file.isEmpty(), "录像文件不能为空");
        if (!isVideo(file)) {
            throw new BizException(ErrorCode.FILE_INVALID, "只支持视频文件（webm / mp4）");
        }

        FileObject stored = fileStorage.store(file, "DEFENSE_VIDEO", null, MAX_VIDEO_BYTES);
        Long duration = durationMs == null ? null : Math.max(0L, durationMs);
        if (!applyVideo(session, stored, duration)) {
            DefenseSession latest = loadSession(String.valueOf(session.getId()));
            BizAssert.rule("IN_PROGRESS".equals(latest.getStatus()) || "COMPLETED".equals(latest.getStatus()),
                    "当前状态不允许上传答辩录像");
            if (!applyVideo(latest, stored, duration)) {
                throw new BizException(ErrorCode.CONFLICT, "录像信息保存失败，请重试");
            }
            session = latest;
        }
        return buildDetailVo(session, isOfferingOwner(session));
    }

    /**
     * 取答辩录像供回放/下载。
     *
     * <p>返回定位信息而不是字节数组：视频可达数百 MB，由 Controller 用
     * {@code FileSystemResource} 流式输出，避免整段读进内存。</p>
     */
    public VideoDownload streamVideo(String sessionId) {
        DefenseSession session = loadSession(sessionId);
        assertVisible(session);
        if (session.getVideoFileId() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "暂无答辩录像");
        }
        FileObject file = BizAssert.found(fileObjectMapper.selectById(session.getVideoFileId()), "答辩录像");
        Path path = BizAssert.found(fileStorage.locate(file), "答辩录像");
        return new VideoDownload(file.getFilename(), file.getContentType(), path);
    }

    /** 写入录像元数据并 updateById；返回 false 表示乐观锁冲突，由调用方重载重试。 */
    private boolean applyVideo(DefenseSession session, FileObject stored, Long durationMs) {
        LocalDateTime now = Times.now();
        session.setVideoFileId(stored.getId());
        session.setVideoDurationMs(durationMs);
        session.setVideoSizeBytes(stored.getSizeBytes());
        session.setVideoUploadedAt(now);
        session.setUpdatedAt(now);
        return sessionMapper.updateById(session) == 1;
    }

    /** content-type 以 video/ 开头，或扩展名是 webm / mp4。 */
    private boolean isVideo(MultipartFile file) {
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (contentType.startsWith("video/")) {
            return true;
        }
        String filename = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.stream().anyMatch(filename::endsWith);
    }

    // ---------------------------------------------------------------- 在场检测

    /**
     * 摄像头在场检测：只判「画面里有没有人」。
     *
     * <p>AI 不可用时<b>不落库</b>直接抛 50301，前端据此显示「在场检测不可用」而不阻塞答辩；
     * 原始图片不保存、不写磁盘。</p>
     */
    public DefenseDtos.FaceCheckVO faceCheck(String sessionId, String imageBase64) {
        DefenseSession session = loadSession(sessionId);
        assertVisible(session);
        BizAssert.param(StringUtils.hasText(imageBase64), "画面内容不能为空");
        BizAssert.param(imageBase64.length() <= MAX_FACE_IMAGE_BASE64_CHARS, "画面数据过大，请降低摄像头分辨率");
        BizAssert.rule("READY".equals(session.getStatus()) || "IN_PROGRESS".equals(session.getStatus()),
                "答辩已结束，无需在场检测");

        // 失败会在这一步抛出 50301；此时不改任何会话字段
        Map<String, Object> response = aiClient.facePresence(imageBase64);
        boolean present = Boolean.TRUE.equals(response.get("present"));
        int faceCount = intValue(response.get("faceCount"));

        int missCount = session.getFaceMissCount() == null ? 0 : session.getFaceMissCount();
        if (!present) {
            missCount++;
        }
        session.setFacePresent(present ? 1 : 0);
        session.setFaceCheckedAt(Times.now());
        session.setFaceMissCount(missCount);
        session.setUpdatedAt(Times.now());
        updateSession(session);

        String message = present
                ? "已检测到考生" + (faceCount > 1 ? "（" + faceCount + " 张人脸）" : "")
                : "未检测到人脸（第 " + missCount + " 次），请正对摄像头";
        return new DefenseDtos.FaceCheckVO(present, faceCount, message);
    }

    // ---------------------------------------------------------------- AI 评分

    /**
     * 教师评分：COMPLETED（或重评 EVALUATED）→ 调 AI → 保存分数/等级/总评。
     *
     * <p>AI 失败或返回非法分数时抛 50301，会话保持原状态，<b>绝不用占位分冒充真实评分</b>。</p>
     */
    public DefenseDtos.EvaluateResultVO evaluate(String sessionId) {
        DefenseSession session = loadSession(sessionId);
        // 只有开课所有者（数据级校验）能评分；学生即便命中 /defense/** 也会在这里被 403
        offeringService.ownedOffering(String.valueOf(session.getOfferingId()));
        BizAssert.rule("COMPLETED".equals(session.getStatus()) || "EVALUATED".equals(session.getStatus()),
                "答辩尚未结束，不能评分");

        List<DefenseQuestion> questions = questionMapper.selectList(
                new LambdaQueryWrapper<DefenseQuestion>()
                        .eq(DefenseQuestion::getDefenseId, session.getId())
                        .orderByAsc(DefenseQuestion::getOrderNo));
        BizAssert.rule(!questions.isEmpty(), "答辩题目为空，无法评分");
        Map<Long, DefenseAnswer> answers = answerMap(session.getId());

        Map<String, Object> payload = buildEvaluationPayload(session, questions, answers);
        // AI 失败直接抛 50301（AiServiceClient 统一处理）；此时尚未改任何字段
        Map<String, Object> response = aiClient.evaluateDefense(payload);

        BigDecimal rawScore = ScoreGuard.parse(response.get("score"));
        if (rawScore == null) {
            throw new BizException(ErrorCode.AI_UNAVAILABLE, "AI 返回缺少答辩分数");
        }
        if (!ScoreGuard.inRange(rawScore)) {
            throw new BizException(ErrorCode.AI_UNAVAILABLE,
                    "AI 返回的答辩分数不在 0~100 区间：" + response.get("score"));
        }
        BigDecimal score = ScoreGuard.oneDecimal(rawScore);
        String grade = stringValue(response.get("grade"));
        if (!StringUtils.hasText(grade)) {
            grade = GradeMath.level(score);
        }
        String summary = stringValue(response.get("summary"));
        if (summary == null) {
            summary = "";
        }

        LocalDateTime now = Times.now();
        session.setScore(score);
        session.setGrade(grade);
        session.setSummary(summary);
        session.setStatus("EVALUATED");
        session.setEvaluatedAt(now);
        session.setUpdatedAt(now);
        updateSession(session);

        notificationService.push(session.getStudentId(), "DEFENSE_EVALUATED", "答辩评分完成",
                "答辩评分已完成，可在答辩页查看结果。", "/defense/" + session.getId());
        return new DefenseDtos.EvaluateResultVO(score, grade, summary);
    }

    // ---------------------------------------------------------------- 学生端

    /** 学生按选题查自己的答辩会话；没有会话返回 40401，前端据此隐藏「进入答辩」入口。 */
    public DefenseDtos.SessionVO studentSession(String selectionId) {
        Long sid = BizAssert.id(selectionId, "selectionId");
        Selection selection = BizAssert.found(selectionMapper.selectById(sid), "选题");
        LoginUser current = SecurityUtils.required();
        BizAssert.forbidden(current.id().equals(selection.getStudentId()), "选题");
        List<DefenseSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<DefenseSession>()
                .eq(DefenseSession::getOfferingId, selection.getOfferingId())
                .eq(DefenseSession::getStudentId, selection.getStudentId())
                .orderByDesc(DefenseSession::getId));
        DefenseSession matched = null;
        for (DefenseSession candidate : sessions) {
            if (selectionIds(candidate).contains(sid)) {
                matched = candidate;
                break;
            }
        }
        return buildDetailVo(BizAssert.found(matched, "答辩会话"), false);
    }

    // ---------------------------------------------------------------- 创建会话：AI 出题 payload

    private int resolveQuestionCount(Offering offering, Integer requested) {
        int count = requested != null ? requested
                : offering.getQuestionCount() != null ? offering.getQuestionCount() : DEFAULT_QUESTION_COUNT;
        BizAssert.param(count >= 1 && count <= 12, "答辩题目数量需在 1~12 之间");
        return count;
    }

    /** 按 AI 服务 21.1 契约组装出题请求：开课 + 选题 + 报告正文（源码材料本轮缺省）。 */
    private Map<String, Object> buildQuestionPayload(Offering offering, Selection selection, int count) {
        Course course = courseMapper.selectById(offering.getCourseId());
        Topic topic = selection.getTopicId() == null ? null : topicMapper.selectById(selection.getTopicId());

        Map<String, Object> topicBrief = new LinkedHashMap<>();
        topicBrief.put("topicId", String.valueOf(selection.getTopicId()));
        topicBrief.put("title", topic == null ? "" : nullSafe(topic.getTitle()));
        topicBrief.put("requirements", topic == null ? "" : nullSafe(topic.getRequirements()));
        topicBrief.put("difficulty", topic == null || !StringUtils.hasText(topic.getDifficulty())
                ? DEFAULT_DIFFICULTY : topic.getDifficulty());
        if (topic != null) {
            // AI 侧 schema 暂无该字段（extra=ignore），保留是为了提示词后续能用到「期望技术栈」
            topicBrief.put("expectedTech", json.stringList(topic.getExpectedTech()));
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("questionCount", count);
        config.put("focus", List.of());
        config.put("language", StringUtils.hasText(offering.getLanguage())
                ? offering.getLanguage() : DEFAULT_LANGUAGE);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", "defense-" + selection.getId());
        payload.put("offering", Map.of(
                "courseName", course == null ? "" : nullSafe(course.getName()),
                "semester", nullSafe(offering.getSemester())));
        payload.put("topics", List.of(topicBrief));
        // 源码材料本轮不给（缺省即可）：出题主要依据题目要求 + 报告，避免大请求拖慢创建
        payload.put("source", Map.of());
        payload.put("report", buildReportMaterial(selection.getId()));
        payload.put("config", config);
        return payload;
    }

    /** 从 file_object.extracted_text 取报告正文；缺报告 / 缺解析结果时返回空 sections。 */
    private Map<String, Object> buildReportMaterial(Long selectionId) {
        String submissionId = "";
        int pageCount = 0;
        List<Map<String, Object>> sections = new ArrayList<>();
        Submission report = submissionMapper.selectOne(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getSelectionId, selectionId)
                .eq(Submission::getType, "REPORT")
                .last("limit 1"));
        if (report != null) {
            submissionId = String.valueOf(report.getId());
            SubmissionVersion version = report.getLatestVersionId() == null ? null
                    : versionMapper.selectById(report.getLatestVersionId());
            if (version != null) {
                pageCount = version.getPageCount() == null ? 0 : version.getPageCount();
                FileObject file = version.getFileId() == null ? null
                        : fileObjectMapper.selectById(version.getFileId());
                if (file != null && StringUtils.hasText(file.getExtractedText())) {
                    sections.add(Map.of("title", "报告正文",
                            "content", clip(file.getExtractedText(), MAX_REPORT_CHARS)));
                }
            }
        }
        Map<String, Object> reportMaterial = new LinkedHashMap<>();
        reportMaterial.put("submissionId", submissionId);
        reportMaterial.put("sections", sections);
        reportMaterial.put("pageCount", pageCount);
        return reportMaterial;
    }

    /** 解析 AI 出的题；非法题（空题干）直接跳过，全部为空时由调用方抛 50301。 */
    private List<GeneratedQuestion> parseQuestions(Map<String, Object> response, Offering offering) {
        Object raw = response.get("questions");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        int maxAnswerSeconds = offering.getMaxAnswerSeconds() == null
                ? DEFAULT_ANSWER_SECONDS : offering.getMaxAnswerSeconds();
        List<GeneratedQuestion> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String question = stringValue(map.get("question"));
            if (!StringUtils.hasText(question)) {
                continue;
            }
            result.add(new GeneratedQuestion(
                    normalizeType(stringValue(map.get("type"))),
                    normalizeDifficulty(stringValue(map.get("difficulty"))),
                    question.trim(),
                    stringList(map.get("expectedPoints")),
                    stringValue(map.get("referenceAnswer")),
                    map.get("sourceRefs"),
                    maxAnswerSeconds));
        }
        return result;
    }

    private String normalizeType(String type) {
        return StringUtils.hasText(type) && List.of("DESIGN", "CODE", "REPORT", "CONCEPT", "EXTENSION").contains(type)
                ? type : DEFAULT_QUESTION_TYPE;
    }

    private String normalizeDifficulty(String difficulty) {
        return StringUtils.hasText(difficulty) && List.of("EASY", "MEDIUM", "HARD").contains(difficulty)
                ? difficulty : DEFAULT_DIFFICULTY;
    }

    /** 事务内落会话 + 题目 + 选题状态 + 学生通知；返回教师视图（含评分要点/参考答案）。 */
    private DefenseDtos.SessionVO persistSession(Offering offering, Selection selection,
                                                 List<GeneratedQuestion> generated) {
        LocalDateTime now = Times.now();
        DefenseSession session = new DefenseSession();
        session.setOfferingId(offering.getId());
        session.setStudentId(selection.getStudentId());
        session.setMode("PER_STUDENT");
        session.setStatus("READY");
        session.setSelectionIds(json.write(List.of(String.valueOf(selection.getId()))));
        session.setQuestionCount(generated.size());
        session.setResetCount(0);
        session.setVersion(0);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);

        List<DefenseDtos.QuestionVO> questionVos = new ArrayList<>();
        int orderNo = 1;
        for (GeneratedQuestion item : generated) {
            DefenseQuestion question = new DefenseQuestion();
            question.setDefenseId(session.getId());
            question.setOrderNo(orderNo++);
            question.setType(item.type());
            question.setDifficulty(item.difficulty());
            question.setQuestion(item.question());
            question.setExpectedPoints(json.write(item.expectedPoints()));
            question.setReferenceAnswer(item.referenceAnswer());
            question.setSourceRefs(item.sourceRefs() == null ? null : json.write(item.sourceRefs()));
            question.setMaxAnswerSeconds(item.maxAnswerSeconds());
            question.setCreatedAt(now);
            questionMapper.insert(question);
            questionVos.add(toQuestionVo(question, null, true));
        }

        selection.setStatus("DEFENDING");
        selection.setUpdatedAt(now);
        if (selectionMapper.updateById(selection) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "选题状态已变更，请刷新后重试");
        }
        notificationService.push(selection.getStudentId(), "DEFENSE_READY", "答辩已就绪",
                "老师已为你发起答辩，请进入答辩页完成录音作答。", "/defense/" + session.getId());

        SysUser student = sysUserMapper.selectById(selection.getStudentId());
        return toVo(session, student, 0, questionVos);
    }

    // ---------------------------------------------------------------- 评分 payload

    /** 按 AI 服务 21.2 契约组装评分请求：题目 + 每题的转写稿与 ASR 置信度。 */
    private Map<String, Object> buildEvaluationPayload(DefenseSession session,
                                                       List<DefenseQuestion> questions,
                                                       Map<Long, DefenseAnswer> answers) {
        List<Map<String, Object>> questionItems = new ArrayList<>();
        List<Map<String, Object>> transcript = new ArrayList<>();
        List<Map<String, Object>> audio = new ArrayList<>();
        for (DefenseQuestion question : questions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(question.getId()));
            item.put("question", question.getQuestion());
            item.put("expectedPoints", json.stringList(question.getExpectedPoints()));
            questionItems.add(item);

            DefenseAnswer answer = answers.get(question.getId());
            if (answer == null) {
                continue;
            }
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("questionId", String.valueOf(question.getId()));
            text.put("text", answer.getTranscript() == null ? "" : answer.getTranscript());
            text.put("durationMs", answer.getDurationMs() == null ? 0L : answer.getDurationMs());
            transcript.add(text);

            Map<String, Object> audioItem = new LinkedHashMap<>();
            audioItem.put("questionId", String.valueOf(question.getId()));
            audioItem.put("url", answer.getAudioFileId() == null ? ""
                    : "/api/v1/files/" + answer.getAudioFileId() + "/download");
            if (answer.getAsrConfidence() != null) {
                audioItem.put("asrConfidence", answer.getAsrConfidence().doubleValue());
            }
            audio.add(audioItem);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("defenseId", String.valueOf(session.getId()));
        payload.put("questions", questionItems);
        payload.put("transcript", transcript);
        payload.put("audio", audio);
        return payload;
    }

    // ---------------------------------------------------------------- 状态 / 权限 / 装配

    private DefenseSession loadSession(String sessionId) {
        return BizAssert.found(
                sessionMapper.selectById(BizAssert.id(sessionId, "sessionId")), "答辩会话");
    }

    /** 会话级归属：开课所有者或该学生本人，其余 40301。 */
    private void assertVisible(DefenseSession session) {
        LoginUser current = SecurityUtils.required();
        boolean student = current.id().equals(session.getStudentId());
        boolean teacher = isOfferingOwner(session);
        BizAssert.forbidden(student || teacher, "答辩会话");
    }

    private boolean isOfferingOwner(DefenseSession session) {
        LoginUser current = SecurityUtils.current().orElse(null);
        return current != null
                && offeringService.isOwner(session.getOfferingId(), current.id());
    }

    /** 乐观锁更新；并发下已被别人改过则 40901，前端刷新重试。 */
    private void updateSession(DefenseSession session) {
        if (sessionMapper.updateById(session) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "答辩状态已变更，请刷新后重试");
        }
    }

    private DefenseAnswer upsertAnswer(DefenseSession session, DefenseQuestion question,
                                       FileObject audio, Long durationMs) {
        LocalDateTime now = Times.now();
        DefenseAnswer answer = answerMapper.selectOne(new LambdaQueryWrapper<DefenseAnswer>()
                .eq(DefenseAnswer::getQuestionId, question.getId())
                .last("limit 1"));
        boolean insert = answer == null;
        if (insert) {
            answer = new DefenseAnswer();
            answer.setDefenseId(session.getId());
            answer.setQuestionId(question.getId());
            answer.setCreatedAt(now);
        }
        answer.setAudioFileId(audio.getId());
        answer.setDurationMs(durationMs == null ? null : Math.max(0L, durationMs));
        answer.setStatus("TRANSCRIBING");
        answer.setAnsweredAt(now);
        answer.setUpdatedAt(now);
        if (insert) {
            answerMapper.insert(answer);
        } else {
            answerMapper.updateById(answer);
        }
        return answer;
    }

    /**
     * ASR 失败：把作答标记 FAILED 后继续抛 50301，让前端知道「这题没转写成功」。
     * 标记失败本身失败只记日志，不改变主异常类型。
     */
    private void markAnswerFailed(DefenseSession session, DefenseQuestion question, DefenseAnswer answer) {
        try {
            answer.setStatus(STATUS_FAILED);
            answer.setUpdatedAt(Times.now());
            answerMapper.updateById(answer);
        } catch (RuntimeException ex) {
            log.warn("标记答辩作答失败状态时出错，sessionId={}, questionId={}",
                    session.getId(), question.getId(), ex);
        }
    }

    /** 结束答辩时把会话覆盖的选题置 DEFENDED；已退选的跳过，不把退选选题「复活」。 */
    private void markSelectionsDefended(DefenseSession session, LocalDateTime now) {
        List<Long> selectionIds = selectionIds(session);
        if (selectionIds.isEmpty()) {
            return;
        }
        for (Selection selection : selectionMapper.selectBatchIds(selectionIds)) {
            String status = selection.getStatus();
            // 只推进 SUBMITTED → DEFENDING → DEFENDED；已 GRADED 的选题不能被「结束答辩」回退，
            // 已 CANCELED / DEFENDED 的也无需重复写。
            if (!"SUBMITTED".equals(status) && !"DEFENDING".equals(status)) {
                continue;
            }
            selection.setStatus("DEFENDED");
            selection.setUpdatedAt(now);
            if (selectionMapper.updateById(selection) != 1) {
                // 乐观锁冲突（并发改状态）不阻塞答辩结束，只记录日志，避免一半失败把整个 finish 回滚
                log.warn("结束答辩推进选题状态失败（乐观锁冲突），selectionId={}", selection.getId());
            }
        }
    }

    /** 会话覆盖的选题 ID（selectionIds 存的是 JSON 字符串数组）。 */
    private List<Long> selectionIds(DefenseSession session) {
        List<Long> result = new ArrayList<>();
        for (String raw : json.stringList(session.getSelectionIds())) {
            try {
                result.add(Long.parseLong(raw));
            } catch (NumberFormatException ex) {
                log.warn("答辩会话 selectionIds 含非法 ID，sessionId={}, raw={}", session.getId(), raw);
            }
        }
        return result;
    }

    /** 会话详情视图（含题目明细）。 */
    private DefenseDtos.SessionVO buildDetailVo(DefenseSession session, boolean teacher) {
        SysUser student = sysUserMapper.selectById(session.getStudentId());
        int answered = answeredCount(session.getId());
        return toVo(session, student, answered, questionVos(session, teacher));
    }

    private DefenseDtos.SessionVO toVo(DefenseSession session, SysUser student,
                                       int answeredCount, List<DefenseDtos.QuestionVO> questions) {
        return new DefenseDtos.SessionVO(
                String.valueOf(session.getId()),
                session.getOfferingId() == null ? "" : String.valueOf(session.getOfferingId()),
                session.getStudentId() == null ? "" : String.valueOf(session.getStudentId()),
                student == null || student.getStudentNo() == null ? "" : student.getStudentNo(),
                student == null || student.getName() == null ? "" : student.getName(),
                student == null || student.getClassName() == null ? "" : student.getClassName(),
                session.getMode(),
                session.getStatus(),
                session.getQuestionCount() == null ? 0 : session.getQuestionCount(),
                answeredCount,
                session.getScore(),
                session.getGrade(),
                session.getSummary(),
                session.getFacePresent() == null ? null : Integer.valueOf(1).equals(session.getFacePresent()),
                session.getFaceMissCount() == null ? 0 : session.getFaceMissCount(),
                Times.offset(session.getStartedAt()),
                Times.offset(session.getFinishedAt()),
                Times.offset(session.getEvaluatedAt()),
                session.getDurationMs(),
                session.getVersion(),
                questions,
                session.getVideoFileId() == null ? null : String.valueOf(session.getVideoFileId()),
                session.getVideoDurationMs(),
                session.getVideoSizeBytes(),
                session.getVideoFileId() != null);
    }

    private List<DefenseDtos.QuestionVO> questionVos(DefenseSession session, boolean teacher) {
        List<DefenseQuestion> questions = questionMapper.selectList(new LambdaQueryWrapper<DefenseQuestion>()
                .eq(DefenseQuestion::getDefenseId, session.getId())
                .orderByAsc(DefenseQuestion::getOrderNo));
        if (questions.isEmpty()) {
            return List.of();
        }
        Map<Long, DefenseAnswer> answers = answerMap(session.getId());
        List<DefenseDtos.QuestionVO> result = new ArrayList<>();
        for (DefenseQuestion question : questions) {
            result.add(toQuestionVo(question, answers.get(question.getId()), teacher));
        }
        return result;
    }

    private DefenseDtos.QuestionVO toQuestionVo(DefenseQuestion question, DefenseAnswer answer,
                                                boolean teacher) {
        return new DefenseDtos.QuestionVO(
                String.valueOf(question.getId()),
                question.getOrderNo(),
                question.getType(),
                question.getDifficulty(),
                question.getQuestion(),
                teacher ? json.stringList(question.getExpectedPoints()) : null,
                teacher ? question.getReferenceAnswer() : null,
                json.object(question.getSourceRefs()),
                question.getMaxAnswerSeconds(),
                answer == null ? null : answer.getStatus(),
                answer == null ? null : answer.getTranscript(),
                answer == null ? null : answer.getDurationMs());
    }

    // ---------------------------------------------------------------- 批量查询 / 小工具

    private Map<Long, SysUser> studentMap(List<Long> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
    }

    /** 该开课下「选题 → 最新会话」映射；同一选题多次答辩时保留 id 最大的一条。 */
    private Map<Long, DefenseSession> sessionsBySelection(Long offeringId) {
        Map<Long, DefenseSession> result = new HashMap<>();
        List<DefenseSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<DefenseSession>()
                .eq(DefenseSession::getOfferingId, offeringId)
                .orderByDesc(DefenseSession::getId));
        for (DefenseSession session : sessions) {
            for (Long selectionId : selectionIds(session)) {
                result.putIfAbsent(selectionId, session);
            }
        }
        return result;
    }

    private Map<Long, DefenseAnswer> answerMap(Long sessionId) {
        return answerMapper.selectList(new LambdaQueryWrapper<DefenseAnswer>()
                        .eq(DefenseAnswer::getDefenseId, sessionId)).stream()
                .collect(Collectors.toMap(DefenseAnswer::getQuestionId,
                        Function.identity(), (first, second) -> first));
    }

    /** 已成功转写的题数 = 作答进度；TRANSCRIBING/FAILED 不计入，避免进度虚高。 */
    private int answeredCount(Long sessionId) {
        Long count = answerMapper.selectCount(new LambdaQueryWrapper<DefenseAnswer>()
                .eq(DefenseAnswer::getDefenseId, sessionId)
                .eq(DefenseAnswer::getStatus, STATUS_TRANSCRIBED));
        return count == null ? 0 : count.intValue();
    }

    private Map<Long, Long> answeredCountMap(List<Long> sessionIds) {
        Map<Long, Long> result = new HashMap<>();
        if (sessionIds.isEmpty()) {
            return result;
        }
        List<Map<String, Object>> rows = answerMapper.selectMaps(new QueryWrapper<DefenseAnswer>()
                .select("defense_id", "count(*) AS c")
                .in("defense_id", sessionIds)
                .eq("status", STATUS_TRANSCRIBED)
                .groupBy("defense_id"));
        for (Map<String, Object> row : rows) {
            Long key = groupedLong(row, "defense_id");
            if (key != null) {
                Long value = groupedLong(row, "c");
                result.put(key, value == null ? 0L : value);
            }
        }
        return result;
    }

    /** 分组 count 结果取值；列名大小写在 MySQL / H2 下可能不同，忽略大小写。 */
    private Long groupedLong(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (column.equalsIgnoreCase(entry.getKey())) {
                BigDecimal value = ScoreGuard.parse(entry.getValue());
                return value == null ? null : value.longValue();
            }
        }
        return null;
    }

    /** ASR 置信度：兼容数字/字符串，夹到 [0,1] 后保留 3 位小数（DB DECIMAL(4,3)）。 */
    private BigDecimal confidence(Object raw) {
        BigDecimal value = ScoreGuard.parse(raw);
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            value = BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            value = BigDecimal.ONE;
        }
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private String stringValue(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private int intValue(Object raw) {
        BigDecimal value = ScoreGuard.parse(raw);
        return value == null ? 0 : value.intValue();
    }

    private Long longValue(Object raw) {
        BigDecimal value = ScoreGuard.parse(raw);
        return value == null ? null : value.longValue();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String clip(String text, int maxLength) {
        String trimmed = text.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    /** 答辩录像定位信息（Controller 用 FileSystemResource 流式输出，不把视频读进内存）。 */
    public record VideoDownload(String filename, String contentType, Path path) {
    }

    /** AI 返回的单道答辩题（落库前的内存结构）。 */
    private record GeneratedQuestion(
            String type,
            String difficulty,
            String question,
            List<String> expectedPoints,
            String referenceAnswer,
            Object sourceRefs,
            int maxAnswerSeconds) {
    }
}
