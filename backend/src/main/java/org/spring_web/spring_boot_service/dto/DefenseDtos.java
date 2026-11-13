package org.spring_web.spring_boot_service.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 答辩契约（语音转写 + 摄像头在场检测）。
 *
 * <p>一个学生一次答辩 = 一个 {@code defense_session}，内含多道 {@code defense_question}，
 * 每题最多一条 {@code defense_answer}（含音频文件、转写文本与置信度）。
 * 状态机：CREATED → READY → IN_PROGRESS → COMPLETED → EVALUATED。</p>
 *
 * <p>隐私边界：人脸只判「画面里有没有人」，不落库原图；学生视图裁剪
 * {@code expectedPoints / referenceAnswer}，防止答辩前泄题。</p>
 */
public final class DefenseDtos {

    private DefenseDtos() {
    }

    /** 教师发起答辩：一次会话针对一个选题；questionCount 省略时用开课默认值。 */
    public record SessionCreateRequest(String selectionId, Integer questionCount,
                                       String overrideReason) {
    }

    /** 教师端「可发起答辩」列表的一行：选题 + 学生 + 已有会话状态 + 发起预检结果。 */
    public record CandidateVO(
            String selectionId,
            String studentNo,
            String studentName,
            String className,
            String topicTitle,
            String selectionStatus,
            String sessionId,
            String sessionStatus,
            BigDecimal codeScore,
            Integer plannedQuestionCount,
            Integer bonusCount,
            boolean eligible,
            String ineligibleReason) {
    }

    /**
     * 答辩会话。列表接口中 questions 为 null，详情/创建/开始/结束后才带题目。
     *
     * <p>录像字段固定在末尾（前端并行开发已按此顺序对接）：{@code videoFileId} 非空即表示
     * 已有整场录像；{@code videoAvailable} 是它的布尔便捷位，避免前端到处判空。</p>
     */
    public record SessionVO(
            String id,
            String offeringId,
            String studentId,
            String studentNo,
            String studentName,
            String className,
            String mode,
            String status,
            Integer questionCount,
            Integer answeredCount,
            BigDecimal score,
            String grade,
            String summary,
            Boolean facePresent,
            Integer faceMissCount,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime evaluatedAt,
            Long durationMs,
            Integer version,
            List<QuestionVO> questions,
            String videoFileId,
            Long videoDurationMs,
            Long videoSizeBytes,
            Boolean videoAvailable,
            BigDecimal codeScoreSnapshot,
            String questionRuleTier,
            String difficultyMix,
            Integer bonusQuestionCount,
            Boolean aiDependent,
            Object principleResult,
            Boolean aiDependentOverridden,
            String overrideReason,
            OffsetDateTime overrideAt,
            String bonusResult,
            BigDecimal bonusScore,
            String bonusComment) {
    }

    /** 答辩题目；学生角色时 expectedPoints / referenceAnswer 为 null。 */
    public record QuestionVO(
            String id,
            Integer orderNo,
            String type,
            String difficulty,
            String question,
            List<String> expectedPoints,
            String referenceAnswer,
            Object sourceRefs,
            Integer maxAnswerSeconds,
            String answerStatus,
            String transcript,
            Long answerDurationMs,
            String dimension,
            Boolean bonus,
            BigDecimal score,
            Boolean passed,
            String evidence,
            String comment,
            Boolean skipped) {
    }

    /**
     * 单原则判定结果（持久化在 {@code defense_session.principle_result}）。
     *
     * @param passCount 该维度通过的题数
     * @param total     该维度的题数
     */
    public record PrincipleResultVO(String dimension, String label, BigDecimal score,
                                    Boolean passed, String evidence,
                                    int passCount, int total) {
    }

    /** 抓住的一帧摄像头画面（base64，不含 data: 前缀也可），服务端不保存。 */
    public record FaceCheckRequest(String imageBase64) {
    }

    /** 在场检测结果：present=false 时前端提示「未检测到人脸（第 N 次）」。 */
    public record FaceCheckVO(boolean present, int faceCount, String message) {
    }

    /** AI 评分结果；AI 失败时不会返回本对象，而是抛 50301，绝不伪造分数。 */
    public record EvaluateResultVO(BigDecimal score, String grade, String summary) {
    }

    // ---------------------------------------------------------------- 只读源码查看

    /**
     * 一个源码文件。
     *
     * <p>{@code content} 是纯文本，接口永远不会返回 zip 原始字节或下载 URL；
     * {@code truncated=true} 表示超过单文件展示上限被截断，前端必须显式提示，
     * 不能让用户以为看到的是完整文件。</p>
     */
    public record SourceFileVO(String path, String content, long size, String language,
                               boolean truncated) {
    }

    /**
     * 源码查看结果。
     *
     * @param files        文本文件列表（按路径排序）
     * @param totalFiles   文件数
     * @param totalSize    内容总字节
     * @param truncated    是否存在被截断的文件
     * @param skippedFiles 被跳过的非文本/噪声文件数（如图片、class、node_modules）
     * @param archiveCount 合并了几个源码包
     * @param versionLabel 版本标签，如 {@code v3}
     * @param generatedAt  本次解压时间（命中缓存时为首次解压时间）
     */
    public record SourceCodeVO(
            List<SourceFileVO> files,
            int totalFiles,
            long totalSize,
            boolean truncated,
            int skippedFiles,
            int archiveCount,
            String versionLabel,
            OffsetDateTime generatedAt) {
    }

    // ---------------------------------------------------------------- 违规上报与裁决

    /** 前端上报的违规证据；severity 不由前端决定，服务端按 type 判定。 */
    public record ViolationReportRequest(String type, Map<String, Object> detail) {
    }

    /**
     * 上报结果。
     *
     * @param accepted   是否在有效状态下被受理（答辩已结束时为 false，只留痕不计数）
     * @param count      当前生效的 MAJOR 违规次数（教师恢复后重新计）
     * @param threshold  终止阈值
     * @param terminated 本次是否触发强制终止
     */
    public record ViolationReportVO(int count, int threshold, boolean accepted, boolean terminated,
                                    String message) {
    }

    /** 教师端看到的一条违规证据。 */
    public record ViolationItemVO(String type, String severity, OffsetDateTime occurredAt, String ip,
                                  Object detail) {
    }

    /**
     * 教师端「某会话的违规汇总」。
     *
     * @param count    当前累计 MAJOR 次数（恢复答辩后从 0 重新计）
     * @param items    最近若干条证据
     */
    public record SessionViolationVO(
            String sessionId,
            String studentId,
            String studentNo,
            String studentName,
            String className,
            String sessionStatus,
            int majorCount,
            int minorCount,
            int count,
            int threshold,
            OffsetDateTime latestAt,
            List<ViolationItemVO> items) {
    }

    /** 教师裁决结果：恢复答辩或维持终止后返回新的会话状态。 */
    public record ViolationDecisionVO(String sessionId, String status) {
    }

    // ---------------------------------------------------------------- 批量发起 / 题数规则 / 三原则

    /**
     * 批量发起答辩。
     *
     * <p>{@code questionCount} 与 {@code overrideReason} 只对「全部学生统一指定题数」生效；
     * 默认 null 表示按源码评分分档——这也是推荐用法。</p>
     */
    public record BatchSessionRequest(String offeringId, List<String> selectionIds,
                                      Integer questionCount, String overrideReason) {
    }

    /** 批量发起的一条结果。 */
    public record BatchItemVO(String selectionId, String studentNo, String studentName,
                              String status, String sessionId, Integer questionCount,
                              String reason) {
    }

    /** 批量发起汇总。 */
    public record BatchResultVO(String batchId, String status, int total, int succeeded,
                                int skipped, int failed, List<BatchItemVO> items) {
    }

    /** 一个分值档位。 */
    public record TierVO(String code, int min, int max, int add) {
    }

    /** 题数规则 / 三原则阈值 / 加分题配置：前端预览与「规则说明」共用这一份。 */
    public record QuestionRuleVO(int baseCount, int maxCount, int bonusCount,
                                 int bonusMaxAnswerSeconds, boolean bonusSkippable,
                                 List<TierVO> tiers, int passThreshold,
                                 List<String> dimensions, int failCountForAiDependent,
                                 List<Integer> baseMix, List<Integer> highTierMix,
                                 int batchMaxStudents, int batchConcurrency,
                                 boolean batchAllowWhenGradePublished) {
    }

    /** 教师覆盖 AI_DEPENDENT 判定：理由必填（≥5 字），仅留痕不做二次审批。 */
    public record OverrideAiDependentRequest(Boolean aiDependent, String reason) {
    }

    /** 覆盖结果。 */
    public record OverrideAiDependentVO(String sessionId, Boolean aiDependent,
                                        boolean overridden, String reason,
                                        OffsetDateTime overrideAt) {
    }
}
