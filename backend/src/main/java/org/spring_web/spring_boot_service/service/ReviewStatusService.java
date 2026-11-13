package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.entity.AsyncTask;
import org.spring_web.spring_boot_service.entity.CodeAnalysis;
import org.spring_web.spring_boot_service.entity.ReportEvaluation;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.mapper.AsyncTaskMapper;
import org.spring_web.spring_boot_service.mapper.CodeAnalysisMapper;
import org.spring_web.spring_boot_service.mapper.ReportEvaluationMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.springframework.stereotype.Service;

/**
 * 统一评审状态查询：把「源码评审」和「报告评审」两套异步链路合成一个视图。
 *
 * <p><b>为什么要有这个接口</b>：前端原来只能轮询 {@code async_task} 或反复拉提交物详情，
 * 于是出现「源码评审完成、报告还在 PENDING，页面却已停止刷新」的卡住现象。
 * 现在前端只认这一份状态：{@code finished=true} 才停轮询，其余按退避重试。</p>
 *
 * <p><b>为什么不返回 {@code async_task} 的状态</b>：任务表的 SUCCEEDED 与业务表的
 * SUCCEEDED 不是同一时刻写的（先写业务表、再 markSucceeded）。若前端盯任务表，
 * 可能拿到「任务已成功但分数还没落库」的瞬间；这里只读业务表，保证「有状态就有分数」。</p>
 */
@Service
@RequiredArgsConstructor
public class ReviewStatusService {

    /** 任务类型 → 阶段。 */
    private static final String CODE_TASK = "CODE_ANALYSIS";
    private static final String REPORT_TASK = "REPORT_EVALUATION";

    private final SubmissionMapper submissionMapper;
    private final CodeAnalysisMapper codeAnalysisMapper;
    private final ReportEvaluationMapper reportEvaluationMapper;
    private final AsyncTaskMapper taskMapper;
    private final SubmissionService submissionService;

    /**
     * 以任意一个提交物 ID 为入口，返回该选题下源码与报告的完整状态。
     *
     * <p>用提交物 ID 而不是选题 ID：前端手上通常正是提交物（无论是源码还是报告），
     * 这样不必先查一次选题。返回里同时带 {@code selectionId} 便于前端路由。</p>
     */
    public CommonDtos.ReviewStatusVO status(String submissionId) {
        Submission submission = submissionService.readable(submissionId);
        Long selectionId = submission.getSelectionId();
        List<Submission> siblings = submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getSelectionId, selectionId));

        Submission source = null;
        Submission report = null;
        for (Submission item : siblings) {
            if ("SOURCE".equals(item.getType())) {
                source = item;
            } else if ("REPORT".equals(item.getType())) {
                report = item;
            }
        }

        CommonDtos.ReviewStageVO sourceStage = stage(source);
        CommonDtos.ReviewStageVO reportStage = stage(report);
        String overall = overall(sourceStage, reportStage);
        boolean finished = !"RUNNING".equals(overall);
        OffsetDateTimeHolder holder = new OffsetDateTimeHolder();
        holder.accept(sourceStage);
        holder.accept(reportStage);

        return new CommonDtos.ReviewStatusVO(
                String.valueOf(submission.getId()),
                String.valueOf(selectionId),
                overall,
                finished,
                sourceStage,
                reportStage,
                holder.value);
    }

    /** 单阶段：业务表为准，任务表只用来给前端展示 taskId。 */
    private CommonDtos.ReviewStageVO stage(Submission submission) {
        if (submission == null) {
            return new CommonDtos.ReviewStageVO(null, null, "MISSING", null, null, null,
                    null, false, null, null);
        }
        boolean source = "SOURCE".equals(submission.getType());
        String taskId = latestTaskId(submission.getId(), source ? CODE_TASK : REPORT_TASK);

        if (source) {
            CodeAnalysis analysis = codeAnalysisMapper.selectOne(new LambdaQueryWrapper<CodeAnalysis>()
                    .eq(CodeAnalysis::getSubmissionId, submission.getId()).last("limit 1"));
            if (analysis == null) {
                return pending(submission, taskId);
            }
            boolean failed = "FAILED".equals(analysis.getStatus());
            String error = failed
                    ? firstText(analysis.getErrorMessage(), analysis.getSummary(),
                    "AI 评审失败，可重新触发")
                    : null;
            return new CommonDtos.ReviewStageVO(
                    String.valueOf(submission.getId()), "SOURCE", analysis.getStatus(),
                    analysis.getScore(), analysis.getGrade(), analysis.getSummary(), error,
                    failed, Times.offset(analysis.getUpdatedAt()), taskId);
        }

        ReportEvaluation evaluation = reportEvaluationMapper.selectOne(
                new LambdaQueryWrapper<ReportEvaluation>()
                        .eq(ReportEvaluation::getSubmissionId, submission.getId()).last("limit 1"));
        if (evaluation == null) {
            return pending(submission, taskId);
        }
        boolean failed = "FAILED".equals(evaluation.getStatus());
        String error = failed
                ? firstText(evaluation.getErrorMessage(), evaluation.getSummary(),
                "AI 评审失败，可重新触发")
                : null;
        return new CommonDtos.ReviewStageVO(
                String.valueOf(submission.getId()), "REPORT", evaluation.getStatus(),
                evaluation.getScore(), evaluation.getGrade(), evaluation.getSummary(), error,
                failed, Times.offset(evaluation.getUpdatedAt()), taskId);
    }

    /**
     * 还没有评审记录时的状态：提交物已进入解析/评审链路就是 PENDING，
     * 否则是 MISSING（例如只有文件还没绑定成功）。绝不在这里编造 RUNNING。
     */
    private CommonDtos.ReviewStageVO pending(Submission submission, String taskId) {
        String status = switch (submission.getStatus() == null ? "" : submission.getStatus()) {
            case "ANALYZING", "UPLOADED", "PARSING" -> "PENDING";
            case "INVALID" -> "FAILED";
            default -> "MISSING";
        };
        String error = "INVALID".equals(submission.getStatus()) ? "材料解析未通过，无法评审" : null;
        return new CommonDtos.ReviewStageVO(
                String.valueOf(submission.getId()), submission.getType(), status,
                null, null, null, error, "FAILED".equals(status),
                Times.offset(submission.getUpdatedAt()), taskId);
    }

    /**
     * 汇总状态。
     *
     * <ul>
     *   <li>两边都缺 → NONE；</li>
     *   <li>任一侧仍在 PENDING/RUNNING → RUNNING（另一侧即使失败也不提前收尾）；</li>
     *   <li>全部成功 → DONE；部分成功部分失败 → PARTIAL；全部失败 → FAILED。</li>
     * </ul>
     */
    private String overall(CommonDtos.ReviewStageVO source, CommonDtos.ReviewStageVO report) {
        List<CommonDtos.ReviewStageVO> stages = new ArrayList<>();
        if (source != null && !"MISSING".equals(source.status())) {
            stages.add(source);
        }
        if (report != null && !"MISSING".equals(report.status())) {
            stages.add(report);
        }
        if (stages.isEmpty()) {
            return "NONE";
        }
        boolean running = stages.stream()
                .anyMatch(s -> "PENDING".equals(s.status()) || "RUNNING".equals(s.status()));
        if (running) {
            return "RUNNING";
        }
        long failed = stages.stream().filter(s -> "FAILED".equals(s.status())).count();
        if (failed == 0) {
            return "DONE";
        }
        return failed == stages.size() ? "FAILED" : "PARTIAL";
    }

    private String latestTaskId(Long submissionId, String type) {
        AsyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<AsyncTask>()
                .eq(AsyncTask::getType, type)
                .eq(AsyncTask::getBizType, "SUBMISSION")
                .eq(AsyncTask::getBizId, submissionId)
                .orderByDesc(AsyncTask::getId)
                .last("limit 1"));
        return task == null ? null : String.valueOf(task.getId());
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** 取两个阶段里最近的更新时间；简单容器，避免在 record 上做可变累加。 */
    private static final class OffsetDateTimeHolder {
        private OffsetDateTime value;

        private void accept(CommonDtos.ReviewStageVO stage) {
            if (stage == null || stage.updatedAt() == null) {
                return;
            }
            if (value == null || stage.updatedAt().isAfter(value)) {
                value = stage.updatedAt();
            }
        }
    }
}
