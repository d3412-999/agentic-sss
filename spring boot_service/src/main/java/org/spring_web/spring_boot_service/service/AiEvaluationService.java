package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.client.AiServiceClient;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.AsyncConfig;
import org.spring_web.spring_boot_service.entity.CodeAnalysis;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.entity.ReportEvaluation;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.SubmissionVersion;
import org.spring_web.spring_boot_service.entity.Topic;
import org.spring_web.spring_boot_service.mapper.CodeAnalysisMapper;
import org.spring_web.spring_boot_service.mapper.FileObjectMapper;
import org.spring_web.spring_boot_service.mapper.ReportEvaluationMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
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

    private static final int MAX_SNIPPET_CHARS = 30_000;

    private final SubmissionMapper submissionMapper;
    private final SubmissionVersionMapper versionMapper;
    private final FileObjectMapper fileObjectMapper;
    private final CodeAnalysisMapper codeAnalysisMapper;
    private final ReportEvaluationMapper reportEvaluationMapper;
    private final SelectionMapper selectionMapper;
    private final TopicMapper topicMapper;
    private final FileStorageService fileStorage;
    private final AiServiceClient aiClient;
    private final AsyncTaskService taskService;
    private final NotificationService notificationService;
    private final JsonCodec json;

    @Async(AsyncConfig.EXECUTOR)
    public void parseAndEvaluateAsync(Long taskId, Long submissionId, Long versionId,
                                     Long fileId, String type) {
        try {
            taskService.markRunning(taskId, 10);
            Submission submission = submissionMapper.selectById(submissionId);
            SubmissionVersion version = versionId == null ? null : versionMapper.selectById(versionId);
            if (submission == null || version == null || fileId == null) {
                taskService.markFailed(taskId, "提交物版本不存在");
                return;
            }
            FileObject file = fileObjectMapper.selectById(fileId);
            Path path = file == null ? null : fileStorage.locate(file);
            if (path == null) {
                version.setStatus("INVALID");
                version.setUpdatedAt(Times.now());
                versionMapper.updateById(version);
                taskService.markFailed(taskId, "文件不存在或已损坏");
                return;
            }

            boolean source = "SOURCE".equals(type);
            Map<String, Object> parseResult = new LinkedHashMap<>();
            String snippet = "";
            if (source) {
                ZipStats stats = inspectZip(path);
                snippet = stats.snippet();
                parseResult.put("javaFileCount", stats.javaFiles());
                parseResult.put("codeLines", stats.codeLines());
                parseResult.put("buildTool", stats.buildTool());
                parseResult.put("fileCount", stats.totalFiles());
                version.setJavaFileCount(stats.javaFiles());
                version.setCodeLines(stats.codeLines());
                version.setBuildTool(stats.buildTool());
            } else {
                // 未引入 PDFBox / POI，暂不做正文抽取；页数保持 null，绝不猜一个数
                parseResult.put("sizeBytes", file.getSizeBytes());
                parseResult.put("textExtracted", false);
                parseResult.put("note", "PDF/DOCX 正文抽取待接入专用解析库");
            }
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
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("submissionId", String.valueOf(submissionId));
            payload.put("selectionId", selection == null ? null : String.valueOf(selection.getId()));
            payload.put("topicTitle", topic == null ? "" : topic.getTitle());
            payload.put("buildTool", parseResult.getOrDefault("buildTool", "PLAIN"));
            payload.put("codeSnippet", source ? snippet : "");
            payload.put("reportText", "");

            try {
                Map<String, Object> response = source
                        ? aiClient.reviewCode(payload) : aiClient.evaluateReport(payload);
                BigDecimal score = score(response);
                if (score == null) {
                    fail(taskId, submission, version, "AI 返回缺少 score 字段", source);
                    return;
                }
                succeed(taskId, submission, version, response, score, source);
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
                analysis.setVersion(0);
                analysis.setCreatedAt(Times.now());
            }
            analysis.setStatus("SUCCEEDED");
            analysis.setScore(score);
            analysis.setSummary(summary);
            analysis.setMetrics(json.write(dimensions));
            analysis.setModel(model);
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
                evaluation.setVersion(0);
                evaluation.setCreatedAt(Times.now());
            }
            evaluation.setStatus("SUCCEEDED");
            evaluation.setScore(score);
            evaluation.setSummary(summary);
            evaluation.setProblems(json.write(issues));
            evaluation.setModel(model);
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
    }

    private void fail(Long taskId, Submission submission, SubmissionVersion version,
                      String message, boolean source) {
        if (source) {
            CodeAnalysis analysis = codeAnalysisMapper.selectOne(new LambdaQueryWrapper<CodeAnalysis>()
                    .eq(CodeAnalysis::getSubmissionId, submission.getId()).last("limit 1"));
            if (analysis == null) {
                analysis = new CodeAnalysis();
                analysis.setSubmissionId(submission.getId());
                analysis.setVersion(0);
                analysis.setCreatedAt(Times.now());
            }
            analysis.setStatus("FAILED");
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
                evaluation.setVersion(0);
                evaluation.setCreatedAt(Times.now());
            }
            evaluation.setStatus("FAILED");
            evaluation.setSummary(message);
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

    private BigDecimal score(Map<String, Object> response) {
        Object raw = response.get("score");
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.toString()).setScale(1, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 只读遍历 zip：统计 Java 文件与代码行、识别构建方式、截取代码片段给 AI。 */
    private ZipStats inspectZip(Path path) throws IOException {
        int javaFiles = 0;
        int totalFiles = 0;
        long codeLines = 0;
        String buildTool = "PLAIN";
        StringBuilder snippet = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                totalFiles++;
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith("pom.xml")) {
                    buildTool = "MAVEN";
                } else if (name.endsWith("build.gradle") || name.endsWith("build.gradle.kts")) {
                    buildTool = "GRADLE";
                }
                if (name.endsWith(".java")) {
                    javaFiles++;
                    StringBuilder content = new StringBuilder();
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        for (int i = 0; i < read; i++) {
                            if (buffer[i] == '\n') {
                                codeLines++;
                            }
                        }
                        if (content.length() < MAX_SNIPPET_CHARS) {
                            content.append(new String(buffer, 0,
                                    Math.min(read, MAX_SNIPPET_CHARS - content.length()),
                                    java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                    if (snippet.length() < MAX_SNIPPET_CHARS) {
                        snippet.append("// ").append(entry.getName()).append('\n')
                                .append(content, 0, Math.min(content.length(),
                                        MAX_SNIPPET_CHARS - snippet.length())).append('\n');
                    }
                }
            }
        }
        return new ZipStats(javaFiles, totalFiles, (int) Math.min(codeLines, Integer.MAX_VALUE),
                buildTool, snippet.toString());
    }

    private record ZipStats(int javaFiles, int totalFiles, int codeLines,
                            String buildTool, String snippet) {
    }
}
