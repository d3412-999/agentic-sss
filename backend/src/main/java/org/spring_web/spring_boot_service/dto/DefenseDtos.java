package org.spring_web.spring_boot_service.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

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
    public record SessionCreateRequest(String selectionId, Integer questionCount) {
    }

    /** 教师端「可发起答辩」列表的一行：选题 + 学生 + 已有会话状态。 */
    public record CandidateVO(
            String selectionId,
            String studentNo,
            String studentName,
            String className,
            String topicTitle,
            String selectionStatus,
            String sessionId,
            String sessionStatus) {
    }

    /** 答辩会话。列表接口中 questions 为 null，详情/创建/开始/结束后才带题目。 */
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
            List<QuestionVO> questions) {
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
            Long answerDurationMs) {
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
}
