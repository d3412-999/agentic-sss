package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.entity.AsyncTask;
import org.spring_web.spring_boot_service.entity.CodeAnalysis;
import org.spring_web.spring_boot_service.entity.ReportEvaluation;
import org.spring_web.spring_boot_service.mapper.AsyncTaskMapper;
import org.spring_web.spring_boot_service.mapper.CodeAnalysisMapper;
import org.spring_web.spring_boot_service.mapper.ReportEvaluationMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 评审看门狗：把「卡住的 RUNNING」收敛成 FAILED，让前端能重试。
 *
 * <p>为什么必须有它：评审跑在 {@code @Async} 线程里，进程重启、OOM、线程被中断都会让
 * 业务表永远停在 RUNNING。用户看到的就是「报告页一直转圈」——前端无论怎么轮询都等不到终态。
 * 前端只能判断「这次没结果」，只有服务端能判断「这条记录已经不可能有结果了」。</p>
 *
 * <p>阈值默认 15 分钟，远大于单份评审超时（120s × 最多 9 份 + 汇总），正常任务不会被误杀。
 * 判定只改状态与原因，不删数据、不重跑；重跑必须由用户点「重新触发」，避免放大 AI 压力。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewWatchdog {

    private static final List<String> LIVE = List.of("PENDING", "RUNNING");

    private final CodeAnalysisMapper codeAnalysisMapper;
    private final ReportEvaluationMapper reportEvaluationMapper;
    private final AsyncTaskMapper taskMapper;
    private final SssProperties properties;

    @Scheduled(fixedDelayString = "${sss.ai.review.watchdog-interval-seconds:120}000",
            initialDelayString = "${sss.ai.review.watchdog-interval-seconds:120}000")
    public void sweep() {
        SssProperties.Review config = properties.getAi().getReview();
        if (!config.isWatchdogEnabled()) {
            return;
        }
        int minutes = Math.max(1, config.getStaleMinutes());
        LocalDateTime threshold = Times.now().minusMinutes(minutes);
        String reason = "评审超时：超过 " + minutes + " 分钟没有进展（服务可能重启或线程中断），可重新触发";

        int staleCode = markStaleCode(threshold, reason);
        int staleReport = markStaleReport(threshold, reason);
        if (staleCode > 0 || staleReport > 0) {
            log.warn("评审看门狗收口：源码 {} 条 / 报告 {} 条标记为 FAILED（阈值 {} 分钟）",
                    staleCode, staleReport, minutes);
        }
    }

    private int markStaleCode(LocalDateTime threshold, String reason) {
        List<CodeAnalysis> stale = codeAnalysisMapper.selectList(new LambdaQueryWrapper<CodeAnalysis>()
                .in(CodeAnalysis::getStatus, LIVE)
                .lt(CodeAnalysis::getUpdatedAt, threshold));
        int count = 0;
        for (CodeAnalysis analysis : stale) {
            LambdaUpdateWrapper<CodeAnalysis> update = new LambdaUpdateWrapper<CodeAnalysis>()
                    .eq(CodeAnalysis::getId, analysis.getId())
                    .in(CodeAnalysis::getStatus, LIVE)
                    .set(CodeAnalysis::getStatus, "FAILED")
                    .set(CodeAnalysis::getErrorMessage, reason)
                    .set(CodeAnalysis::getModel, "timeout")
                    .set(CodeAnalysis::getUpdatedAt, Times.now());
            count += codeAnalysisMapper.update(null, update);
            failTask(analysis.getSubmissionId(), "CODE_ANALYSIS", reason);
        }
        return count;
    }

    private int markStaleReport(LocalDateTime threshold, String reason) {
        List<ReportEvaluation> stale = reportEvaluationMapper.selectList(
                new LambdaQueryWrapper<ReportEvaluation>()
                        .in(ReportEvaluation::getStatus, LIVE)
                        .lt(ReportEvaluation::getUpdatedAt, threshold));
        int count = 0;
        for (ReportEvaluation evaluation : stale) {
            LambdaUpdateWrapper<ReportEvaluation> update = new LambdaUpdateWrapper<ReportEvaluation>()
                    .eq(ReportEvaluation::getId, evaluation.getId())
                    .in(ReportEvaluation::getStatus, LIVE)
                    .set(ReportEvaluation::getStatus, "FAILED")
                    .set(ReportEvaluation::getErrorMessage, reason)
                    .set(ReportEvaluation::getModel, "timeout")
                    .set(ReportEvaluation::getUpdatedAt, Times.now());
            count += reportEvaluationMapper.update(null, update);
            failTask(evaluation.getSubmissionId(), "REPORT_EVALUATION", reason);
        }
        return count;
    }

    /** 把仍在 PENDING/RUNNING 的任务一并标失败，否则任务中心永远显示「进行中」。 */
    private void failTask(Long submissionId, String type, String reason) {
        if (submissionId == null) {
            return;
        }
        taskMapper.update(null, new LambdaUpdateWrapper<AsyncTask>()
                .eq(AsyncTask::getType, type)
                .eq(AsyncTask::getBizType, "SUBMISSION")
                .eq(AsyncTask::getBizId, submissionId)
                .in(AsyncTask::getStatus, LIVE)
                .set(AsyncTask::getStatus, "FAILED")
                .set(AsyncTask::getErrorMessage, reason)
                .set(AsyncTask::getFinishedAt, Times.now())
                .set(AsyncTask::getUpdatedAt, Times.now()));
    }
}
