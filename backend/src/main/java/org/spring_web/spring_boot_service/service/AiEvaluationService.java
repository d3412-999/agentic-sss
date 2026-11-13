package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.client.AiServiceClient;
import org.spring_web.spring_boot_service.common.ArchiveGuard;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ScoreGuard;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.AsyncConfig;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.entity.CodeAnalysis;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.entity.ReportEvaluation;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.SubmissionVersion;
import org.spring_web.spring_boot_service.entity.SubmissionVersionFile;
import org.spring_web.spring_boot_service.entity.Topic;
import org.spring_web.spring_boot_service.mapper.AsyncTaskMapper;
import org.spring_web.spring_boot_service.mapper.CodeAnalysisMapper;
import org.spring_web.spring_boot_service.mapper.FileObjectMapper;
import org.spring_web.spring_boot_service.mapper.ReportEvaluationMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionVersionFileMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionVersionMapper;
import org.spring_web.spring_boot_service.mapper.TopicMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 上传后的异步编排：解析文件 → 调 AI 服务 → 落库 → 更新任务进度 → 通知学生。
 *
 * <p><b>刻意不伪造分数</b>：AI 服务当前 5 个接口还是 501，调用会抛 50301。
 * 这时我们把分析记录标成 FAILED、把原因写进 toolErrors、任务标失败，
 * 而不是随便塞一个「85 分」让界面看起来能用。教育系统里，一个来路不明的分数
 * 比「暂时没结果」危险得多。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiEvaluationService {

    /**
     * 合并后送给 AI 的字符上限（仅用于「不分片」的回退路径与汇总阶段的兜底）。
     *
     * <p>默认走 map-reduce：每份文件单独评一次，`sss.ai.review.per-file-chars` 控制单份预算，
     * 超出只截断那一份并标注 degraded，不再整批截断——9 份工程里靠后的包必须进正文评审。</p>
     */
    private static final int MAX_SNIPPET_CHARS = 60_000;
    private static final int MAX_REPORT_CHARS = 60_000;

    private final SubmissionMapper submissionMapper;
    private final SubmissionVersionMapper versionMapper;
    private final SubmissionVersionFileMapper versionFileMapper;
    private final AsyncTaskMapper taskMapper;
    private final FileObjectMapper fileObjectMapper;
    private final CodeAnalysisMapper codeAnalysisMapper;
    private final ReportEvaluationMapper reportEvaluationMapper;
    private final SelectionMapper selectionMapper;
    private final TopicMapper topicMapper;
    private final FileStorageService fileStorage;
    private final ReportTextExtractor reportTextExtractor;
    private final AiServiceClient aiClient;
    private final AsyncTaskService taskService;
    private final NotificationService notificationService;
    private final JsonCodec json;
    private final SssProperties properties;

    @Async(AsyncConfig.EXECUTOR)
    public void parseAndEvaluateAsync(Long taskId, Long submissionId, Long versionId,
                                     List<Long> fileIds, String type) {
        try {
            taskService.markRunning(taskId, 10);
            Submission submission = submissionMapper.selectById(submissionId);
            SubmissionVersion version = versionId == null ? null : versionMapper.selectById(versionId);
            if (submission == null || version == null || fileIds == null || fileIds.isEmpty()) {
                taskService.markFailed(taskId, "提交物版本不存在");
                return;
            }

            // 保序去重：顺序由学生拖拽决定，评审按这个顺序合并文件
            List<FileObject> files = new ArrayList<>();
            for (Long fileId : new LinkedHashSet<>(fileIds)) {
                FileObject file = fileObjectMapper.selectById(fileId);
                if (file != null) {
                    files.add(file);
                }
            }
            if (files.isEmpty()) {
                version.setStatus("INVALID");
                version.setUpdatedAt(Times.now());
                versionMapper.updateById(version);
                taskService.markFailed(taskId, "文件不存在或已损坏");
                return;
            }

            boolean source = "SOURCE".equals(type);
            Map<String, Object> parseResult = new LinkedHashMap<>();
            List<Map<String, Object>> fileSummaries = new ArrayList<>();
            List<FileMaterial> materials = new ArrayList<>();
            int javaFiles = 0;
            int totalFiles = 0;
            int codeLines = 0;
            int readable = 0;
            String buildTool = "PLAIN";

            if (source) {
                // 每份源码包单独保留自己的片段：不再拼成一大段后整体截断，
                // 否则 9 份工程里靠后的包连一个字都进不了模型。
                for (FileObject file : files) {
                    Path path = fileStorage.locate(file);
                    if (path == null) {
                        continue;
                    }
                    readable++;
                    ZipStats stats = inspectZip(path);
                    javaFiles += stats.javaFiles();
                    codeLines += stats.codeLines();
                    totalFiles += stats.totalFiles();
                    if ("PLAIN".equals(buildTool) && !"PLAIN".equals(stats.buildTool())) {
                        buildTool = stats.buildTool();
                    }
                    Map<String, Object> summary = fileSummary(file, stats.javaFiles(), stats.codeLines());
                    fileSummaries.add(summary);
                    materials.add(new FileMaterial(file, summary, stats.snippet(), null));
                }
                parseResult.put("submissionFileCount", files.size());
                parseResult.put("fileCount", totalFiles);
                parseResult.put("javaFileCount", javaFiles);
                parseResult.put("codeLines", codeLines);
                parseResult.put("buildTool", buildTool);
                version.setJavaFileCount(javaFiles);
                version.setCodeLines(codeLines);
                version.setBuildTool(buildTool);

                // 空包 / 只有非 Java 文件时直接判 INVALID，绝不把空源码交给 AI“凭感觉”打分
                boolean anySnippet = materials.stream()
                        .anyMatch(m -> m.text != null && !m.text.isBlank());
                if (readable == 0 || javaFiles == 0 || !anySnippet) {
                    String note = readable == 0 ? "文件不存在或已损坏"
                            : "源码包中未找到可评审的 Java 文件";
                    parseResult.put("valid", false);
                    parseResult.put("note", note);
                    version.setParseResult(json.write(parseResult));
                    version.setStatus("INVALID");
                    version.setUpdatedAt(Times.now());
                    versionMapper.updateById(version);

                    fail(taskId, submission, version, note + "，无法评审", true);
                    submission.setStatus("INVALID");
                    submission.setUpdatedAt(Times.now());
                    submissionMapper.updateById(submission);
                    return;
                }
            } else {
                // 多份报告：PDF 用 PDFBox、DOCX 用 POI 抽取正文，每份各自成篇送审，
                // 页码累加而不是取最大
                int pages = 0;
                for (FileObject file : files) {
                    Path path = fileStorage.locate(file);
                    if (path == null) {
                        continue;
                    }
                    ReportTextExtractor.ExtractedReport extracted =
                            reportTextExtractor.extract(path, file.getFilename());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", file.getFilename());
                    item.put("size", file.getSizeBytes());
                    item.put("sha256", file.getSha256());
                    item.put("pageCount", extracted.pageCount());
                    item.put("textLength", extracted.text() == null ? 0 : extracted.text().length());
                    item.put("success", extracted.success());
                    if (extracted.error() != null) {
                        item.put("error", extracted.error());
                    }
                    fileSummaries.add(item);
                    materials.add(new FileMaterial(file, item, extracted.text(), extracted.pageCount()));

                    pages += extracted.pageCount() == null ? 0 : extracted.pageCount();
                    file.setExtractedText(extracted.text());
                    file.setPageCount(extracted.pageCount());
                    file.setUpdatedAt(Times.now());
                    fileObjectMapper.updateById(file);
                }
                parseResult.put("submissionFileCount", files.size());
                parseResult.put("sizeBytes", files.stream()
                        .mapToLong(f -> f.getSizeBytes() == null ? 0 : f.getSizeBytes()).sum());
                parseResult.put("textLength", materials.stream()
                        .mapToInt(m -> m.text == null ? 0 : m.text.length()).sum());
                parseResult.put("pageCount", pages);
                version.setPageCount(pages);
            }
            parseResult.put("files", fileSummaries);
            version.setParseResult(json.write(parseResult));
            version.setStatus("VALID");
            version.setUpdatedAt(Times.now());
            versionMapper.updateById(version);

            submission.setStatus("ANALYZING");
            submission.setUpdatedAt(Times.now());
            submissionMapper.updateById(submission);
            taskService.markProgress(taskId, 40);

            Selection selection = selectionMapper.selectById(submission.getSelectionId());
            Topic topic = selection == null ? null : topicMapper.selectById(selection.getTopicId());
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("submissionId", String.valueOf(submissionId));
            base.put("selectionId", selection == null ? null : String.valueOf(selection.getId()));
            base.put("topicTitle", topic == null ? "" : topic.getTitle());
            base.put("buildTool", parseResult.getOrDefault("buildTool", "PLAIN"));
            base.put("pageCount", parseResult.get("pageCount"));

            SssProperties.Review reviewConfig = properties.getAi().getReview();
            try {
                Map<String, Object> response;
                if (reviewConfig.isPerFileEnabled() && !materials.isEmpty()) {
                    // map：逐份评审（并发受配置限制），每份失败只把自己标 degraded，不拖垮整批
                    int succeeded = reviewEach(materials, base, source, reviewConfig);
                    if (succeeded == 0) {
                        throw new BizException(org.spring_web.spring_boot_service.common.ErrorCode.AI_UNAVAILABLE,
                                "AI 服务对本次所有文件均未返回结果");
                    }
                    taskService.markProgress(taskId, 75);
                    // reduce：把各份摘要汇总成总分与总体结论（不送原文，不会爆上下文）
                    response = aggregate(materials, base, source);
                } else {
                    response = mergedReview(materials, base, source);
                }

                // 分文件评审结果回写 parseResult，前端可逐份展开
                parseResult.put("files", fileSummaries);
                version.setParseResult(json.write(parseResult));
                version.setUpdatedAt(Times.now());
                versionMapper.updateById(version);

                BigDecimal score = score(response);
                if (score == null) {
                    fail(taskId, submission, version, "AI 返回缺少 score 字段", source);
                    return;
                }
                if (!ScoreGuard.inRange(score)) {
                    fail(taskId, submission, version,
                            "AI 返回的分数不在 0~100 区间：" + response.get("score"), source);
                    return;
                }
                succeed(taskId, submission, version, response, ScoreGuard.oneDecimal(score), source);
            } catch (BizException ex) {
                fail(taskId, submission, version, ex.getMessage(), source);
            }
        } catch (Exception ex) {
            log.error("AI evaluation failed, taskId={}", taskId, ex);
            taskService.markFailed(taskId, ex.getMessage());
        }
    }

    private void succeed(Long taskId, Submission submission, SubmissionVersion version,
                         Map<String, Object> response, BigDecimal score, boolean source) {
        String summary = String.valueOf(response.getOrDefault("summary", ""));
        String model = String.valueOf(response.getOrDefault("model", "ai-service"));
        Object dimensions = response.getOrDefault("dimensions", List.of());
        Object issues = response.getOrDefault("problems", response.getOrDefault("issues", List.of()));

        String resultId;
        if (source) {
            CodeAnalysis analysis = codeAnalysisMapper.selectOne(new LambdaQueryWrapper<CodeAnalysis>()
                    .eq(CodeAnalysis::getSubmissionId, submission.getId()).last("limit 1"));
            if (analysis == null) {
                analysis = new CodeAnalysis();
                analysis.setSubmissionId(submission.getId());
                // version 是 MyBatis-Plus 的乐观锁列（@Version），新建时初始化为 0
                analysis.setVersion(0);
                analysis.setCreatedAt(Times.now());
            }
            analysis.setStatus("SUCCEEDED");
            analysis.setScore(score);
            analysis.setSummary(summary);
            analysis.setMetrics(json.write(dimensions));
            analysis.setModel(model);
            // 成功必须清空上一次失败留下的原因，否则前端会同时看到「已完成」和一条旧错误
            analysis.setErrorMessage(null);
            analysis.setFinishedAt(Times.now());
            analysis.setUpdatedAt(Times.now());
            if (analysis.getId() == null) {
                codeAnalysisMapper.insert(analysis);
            } else {
                codeAnalysisMapper.updateById(analysis);
            }
            resultId = String.valueOf(analysis.getId());
        } else {
            ReportEvaluation evaluation = reportEvaluationMapper.selectOne(
                    new LambdaQueryWrapper<ReportEvaluation>()
                            .eq(ReportEvaluation::getSubmissionId, submission.getId()).last("limit 1"));
            if (evaluation == null) {
                evaluation = new ReportEvaluation();
                evaluation.setSubmissionId(submission.getId());
                // 同上：乐观锁列，初始 0
                evaluation.setVersion(0);
                evaluation.setCreatedAt(Times.now());
            }
            evaluation.setStatus("SUCCEEDED");
            evaluation.setScore(score);
            evaluation.setSummary(summary);
            evaluation.setProblems(json.write(issues));
            evaluation.setModel(model);
            evaluation.setErrorMessage(null);
            evaluation.setFinishedAt(Times.now());
            evaluation.setUpdatedAt(Times.now());
            if (evaluation.getId() == null) {
                reportEvaluationMapper.insert(evaluation);
            } else {
                reportEvaluationMapper.updateById(evaluation);
            }
            resultId = String.valueOf(evaluation.getId());
        }

        submission.setStatus("ANALYZED");
        submission.setUpdatedAt(Times.now());
        submissionMapper.updateById(submission);
        taskService.markSucceeded(taskId,
                Map.of(source ? "analysisId" : "evaluationId", resultId, "score", score));
        notify(submission, source ? "源码分析完成" : "报告评审完成");
        if (source) {
            enqueueReportIfNeeded(submission);
        }
    }

    /**
     * 源码评审完成后，补一次「报告评审是否漏入队」的检查。
     *
     * <p>正常情况下报告上传时就已经入队，这里是**补偿**而不是主链路：只有报告版本存在、
     * 业务记录缺失或仍是 PENDING、且没有任何在跑的任务时才补一条。
     * 三个条件同时成立才动作，避免并发下重复评审。</p>
     */
    private void enqueueReportIfNeeded(Submission sourceSubmission) {
        try {
            Submission report = submissionMapper.selectOne(new LambdaQueryWrapper<Submission>()
                    .eq(Submission::getSelectionId, sourceSubmission.getSelectionId())
                    .eq(Submission::getType, "REPORT")
                    .last("limit 1"));
            if (report == null || report.getLatestVersionId() == null) {
                return;
            }
            ReportEvaluation existing = reportEvaluationMapper.selectOne(
                    new LambdaQueryWrapper<ReportEvaluation>()
                            .eq(ReportEvaluation::getSubmissionId, report.getId()).last("limit 1"));
            if (existing != null && !"PENDING".equals(existing.getStatus())) {
                return;
            }
            Long live = taskMapper.selectCount(new LambdaQueryWrapper<org.spring_web.spring_boot_service.entity.AsyncTask>()
                    .eq(org.spring_web.spring_boot_service.entity.AsyncTask::getType, "REPORT_EVALUATION")
                    .eq(org.spring_web.spring_boot_service.entity.AsyncTask::getBizType, "SUBMISSION")
                    .eq(org.spring_web.spring_boot_service.entity.AsyncTask::getBizId, report.getId())
                    .in(org.spring_web.spring_boot_service.entity.AsyncTask::getStatus, "PENDING", "RUNNING"));
            if (live != null && live > 0) {
                return;
            }
            List<SubmissionVersionFile> files = versionFileMapper.selectList(
                    new LambdaQueryWrapper<SubmissionVersionFile>()
                            .eq(SubmissionVersionFile::getVersionId, report.getLatestVersionId()));
            if (files.isEmpty()) {
                return;
            }
            org.spring_web.spring_boot_service.entity.AsyncTask task = taskService.create(
                    "REPORT_EVALUATION", "SUBMISSION", report.getId(), Map.of("auto", true));
            // 自查不会重入成功分支（type=REPORT），不会形成递归
            parseAndEvaluateAsync(task.getId(), report.getId(), report.getLatestVersionId(),
                    files.stream().map(SubmissionVersionFile::getFileId).toList(), "REPORT");
            log.info("报告评审补入队：submissionId={}, taskId={}", report.getId(), task.getId());
        } catch (RuntimeException ex) {
            // 补偿失败只影响报告进度，不能把已经成功的源码评审一起拖成异常
            log.warn("报告评审补入队失败，submissionId={}", sourceSubmission.getId(), ex);
        }
    }

    private void fail(Long taskId, Submission submission, SubmissionVersion version,
                      String message, boolean source) {
        if (source) {
            CodeAnalysis analysis = codeAnalysisMapper.selectOne(new LambdaQueryWrapper<CodeAnalysis>()
                    .eq(CodeAnalysis::getSubmissionId, submission.getId()).last("limit 1"));
            if (analysis == null) {
                analysis = new CodeAnalysis();
                analysis.setSubmissionId(submission.getId());
                // version 是 MyBatis-Plus 的乐观锁列（@Version），新建时初始化为 0
                analysis.setVersion(0);
                analysis.setCreatedAt(Times.now());
            }
            analysis.setStatus("FAILED");
            // 失败不落 0 分：0 分是「评了但答得很差」，与「没评出来」是两件事
            analysis.setScore(null);
            analysis.setGrade(null);
            analysis.setModel("failed");
            analysis.setErrorMessage(message);
            analysis.setToolErrors(json.write(List.of(Map.of("tool", "AI", "message", message))));
            analysis.setUpdatedAt(Times.now());
            if (analysis.getId() == null) {
                codeAnalysisMapper.insert(analysis);
            } else {
                codeAnalysisMapper.updateById(analysis);
            }
        } else {
            ReportEvaluation evaluation = reportEvaluationMapper.selectOne(
                    new LambdaQueryWrapper<ReportEvaluation>()
                            .eq(ReportEvaluation::getSubmissionId, submission.getId()).last("limit 1"));
            if (evaluation == null) {
                evaluation = new ReportEvaluation();
                evaluation.setSubmissionId(submission.getId());
                // 同上：乐观锁列，初始 0
                evaluation.setVersion(0);
                evaluation.setCreatedAt(Times.now());
            }
            evaluation.setStatus("FAILED");
            evaluation.setScore(null);
            evaluation.setGrade(null);
            evaluation.setModel("failed");
            evaluation.setSummary(message);
            evaluation.setErrorMessage(message);
            evaluation.setUpdatedAt(Times.now());
            if (evaluation.getId() == null) {
                reportEvaluationMapper.insert(evaluation);
            } else {
                reportEvaluationMapper.updateById(evaluation);
            }
        }
        submission.setStatus("VALID");
        submission.setUpdatedAt(Times.now());
        submissionMapper.updateById(submission);
        taskService.markFailed(taskId, message);
        log.info("AI evaluation degraded, submissionId={}, reason={}", submission.getId(), message);
    }

    private void notify(Submission submission, String title) {
        Selection selection = selectionMapper.selectById(submission.getSelectionId());
        if (selection != null) {
            notificationService.push(selection.getStudentId(), "ANALYSIS_DONE", title,
                    "可前往「我的选题」查看结果。",
                    "/student/selections/" + selection.getId());
        }
    }

    /**
     * 解析 AI 返回的分数：兼容数字与字符串，去掉首尾空白。
     *
     * <p>缺失 / 非数字返回 null，由调用方按「AI 返回缺少 score 字段」失败；
     * 数值是否落在 0~100 由调用方用 {@link ScoreGuard#inRange(BigDecimal)} 判定，
     * 越界分数会显式失败，既不会被夹到合法区间，也不会落成 SUCCEEDED。</p>
     */
    private BigDecimal score(Map<String, Object> response) {
        return ScoreGuard.parse(response.get("score"));
    }

    /** 只读遍历 zip（复用 ArchiveGuard，与上传校验同一套规则）：统计 Java 文件与代码行、识别构建方式、截取代码片段给 AI。 */
    private ZipStats inspectZip(Path path) throws IOException {
        ArchiveGuard.Scan scan;
        try {
            scan = ArchiveGuard.scan(path, ArchiveGuard.Limits.defaults());
        } catch (ArchiveGuard.Violation violation) {
            // 扫描阶段失败（Zip Slip / 压缩炸弹等）本质上是坏包，转成 IOException 交给上层按解析失败降级
            throw new IOException("源码包校验失败：" + violation.message(), violation);
        }
        return new ZipStats(scan.javaFiles(), scan.totalFiles(), scan.codeLines(),
                scan.buildTool(), scan.snippet());
    }

    /** 单个文件在 payload 里的摘要项，AI 侧据此逐份说明贡献。 */
    private Map<String, Object> fileSummary(FileObject file, int javaFiles, int codeLines) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", file.getFilename());
        item.put("size", file.getSizeBytes() == null ? 0 : file.getSizeBytes());
        item.put("sha256", file.getSha256());
        item.put("javaFiles", javaFiles);
        item.put("codeLines", codeLines);
        return item;
    }

    /**
     * map 阶段：逐份文件调一次 AI，并发受 {@code sss.ai.review.max-concurrent} 限制。
     *
     * <p>单份失败只把这一份标成 degraded（并记下原因），不影响其它份；
     * 全部失败才由调用方判定整次评审失败——绝不拿半份结果凑一个总分。</p>
     *
     * @return 成功（未降级）的份数
     */
    private int reviewEach(List<FileMaterial> materials, Map<String, Object> base,
                           boolean source, SssProperties.Review config) {
        int poolSize = Math.max(1, Math.min(config.getMaxConcurrent(), materials.size()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Map.Entry<Future<?>, FileMaterial>> tasks = new ArrayList<>();
        try {
            for (FileMaterial material : materials) {
                Future<?> future = pool.submit(() -> {
                    try {
                        Map<String, Object> payload = perFilePayload(base, material, source, config);
                        Map<String, Object> result = source
                                ? aiClient.reviewCode(payload) : aiClient.evaluateReport(payload);
                        material.review = result;
                        material.summary.put("reviewed", true);
                        material.summary.put("score", result.get("score"));
                        material.summary.put("summary", result.get("summary"));
                        if (result.get("problems") != null) {
                            material.summary.put("problems", result.get("problems"));
                        }
                        if (result.get("highlights") != null) {
                            material.summary.put("highlights", result.get("highlights"));
                        }
                    } catch (RuntimeException ex) {
                        material.degraded = true;
                        material.summary.put("reviewed", false);
                        material.summary.put("reviewError", ex.getMessage());
                        log.warn("分文件评审失败: {} - {}", material.file.getFilename(), ex.getMessage());
                    }
                });
                tasks.add(Map.entry(future, material));
            }
            for (Map.Entry<Future<?>, FileMaterial> task : tasks) {
                try {
                    task.getKey().get(config.getPerFileTimeoutSeconds(), TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException ex) {
                    task.getKey().cancel(true);
                    FileMaterial material = task.getValue();
                    material.degraded = true;
                    material.summary.put("reviewed", false);
                    material.summary.put("reviewError", "单份评审超时");
                    log.warn("分文件评审超时: {}", material.file.getFilename());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    // 线程内已捕获业务异常，这里只可能是池本身异常；按降级处理，不中断其它份
                    log.debug("分文件评审任务异常", ex);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return (int) materials.stream().filter(m -> !m.degraded).count();
    }

    /** reduce 阶段：只送各份摘要，不送原文，因此不会撞上下文上限。 */
    private Map<String, Object> aggregate(List<FileMaterial> materials, Map<String, Object> base,
                                          boolean source) {
        Map<String, Object> payload = new LinkedHashMap<>(base);
        List<Map<String, Object>> briefs = new ArrayList<>();
        for (FileMaterial material : materials) {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("name", material.file.getFilename());
            brief.put("score", material.summary.get("score"));
            brief.put("summary", material.summary.getOrDefault("summary", ""));
            brief.put("problems", problemTexts(material.summary.get("problems")));
            brief.put("degraded", material.degraded);
            if (source) {
                brief.put("javaFiles", material.summary.getOrDefault("javaFiles", 0));
                brief.put("codeLines", material.summary.getOrDefault("codeLines", 0));
            } else {
                brief.put("pageCount", material.pageCount == null ? 0 : material.pageCount);
                brief.put("highlights", material.summary.getOrDefault("highlights", List.of()));
            }
            briefs.add(brief);
        }
        payload.put("files", briefs);
        return source ? aiClient.aggregateCode(payload) : aiClient.aggregateReport(payload);
    }

    /**
     * 把各份的 problems 统一成字符串数组。
     *
     * <p>源码侧 problems 本来就是字符串；报告侧是 {@code {section,message,suggestion}}
     * 结构化对象。汇总接口的 FileReviewBrief.problems 声明为 list[str]，
     * 直接透传会被 pydantic 拒绝（422 string_type），进而让整份报告评审 FAILED
     * ——2026-11 实测踩到过一次，所以在这里做一次显式降级。</p>
     */
    private List<String> problemTexts(Object problems) {
        if (!(problems instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            if (item instanceof Map<?, ?> map) {
                String section = map.get("section") == null ? "" : String.valueOf(map.get("section"));
                String message = map.get("message") == null ? "" : String.valueOf(map.get("message"));
                String suggestion = map.get("suggestion") == null ? "" : String.valueOf(map.get("suggestion"));
                StringBuilder sb = new StringBuilder();
                if (!section.isBlank()) {
                    sb.append(section).append('：');
                }
                sb.append(message.isBlank() ? "（未说明）" : message);
                if (!suggestion.isBlank()) {
                    sb.append("（建议：").append(suggestion).append('）');
                }
                out.add(sb.toString());
            } else {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    /** 单份文件的请求体：只带这一份的正文，附带它自己的摘要。 */
    private Map<String, Object> perFilePayload(Map<String, Object> base, FileMaterial material,
                                               boolean source, SssProperties.Review config) {
        String text = material.text == null ? "" : material.text;
        if (text.length() > config.getPerFileChars()) {
            // 只截这一份并明确标注：不让「某一份特别大」把其它 8 份挤出上下文
            text = text.substring(0, config.getPerFileChars())
                    + "\n\n…（该份超出单次评审预算，仅截取前 " + config.getPerFileChars() + " 字符）";
            material.degraded = true;
            material.summary.put("truncated", true);
        }
        Map<String, Object> payload = new LinkedHashMap<>(base);
        payload.put("codeSnippet", source ? text : "");
        payload.put("reportText", source ? "" : text);
        payload.put("sourceFiles", source ? List.of(material.summary) : List.of());
        payload.put("reportFiles", source ? List.of() : List.of(material.summary));
        payload.put("pageCount", material.pageCount);
        return payload;
    }

    /** 回退路径：不分片时把多份拼成一次调用（保留旧行为，便于用配置关掉分片）。 */
    private Map<String, Object> mergedReview(List<FileMaterial> materials, Map<String, Object> base,
                                             boolean source) {
        int limit = source ? MAX_SNIPPET_CHARS : MAX_REPORT_CHARS;
        StringBuilder merged = new StringBuilder();
        for (FileMaterial material : materials) {
            if (material.text == null || material.text.isBlank()) {
                continue;
            }
            if (merged.length() >= limit) {
                material.degraded = true;
                material.summary.put("truncated", true);
                continue;
            }
            merged.append("===== 文件：").append(material.file.getFilename()).append(" =====\n")
                    .append(material.text).append("\n\n");
        }
        Map<String, Object> payload = new LinkedHashMap<>(base);
        payload.put("codeSnippet", source ? truncate(merged.toString(), limit) : "");
        payload.put("reportText", source ? "" : truncate(merged.toString(), limit));
        List<Map<String, Object>> summaries = materials.stream().map(m -> m.summary).toList();
        payload.put("sourceFiles", source ? summaries : List.of());
        payload.put("reportFiles", source ? List.of() : summaries);
        return source ? aiClient.reviewCode(payload) : aiClient.evaluateReport(payload);
    }

    /** 单份评审材料：原文片段 + 摘要；评审结果回填到 review。 */
    private static final class FileMaterial {
        private final FileObject file;
        private final Map<String, Object> summary;
        private final String text;
        private final Integer pageCount;
        private boolean degraded;
        private Map<String, Object> review;

        private FileMaterial(FileObject file, Map<String, Object> summary, String text, Integer pageCount) {
            this.file = file;
            this.summary = summary;
            this.text = text;
            this.pageCount = pageCount;
        }
    }

    private String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n\n…（超出单次评审上限，后续内容已截断）";
    }

    private record ZipStats(int javaFiles, int totalFiles, int codeLines,
                            String buildTool, String snippet) {
    }
}
