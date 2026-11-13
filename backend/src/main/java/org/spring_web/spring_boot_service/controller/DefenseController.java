package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.DefenseDtos;
import org.spring_web.spring_boot_service.service.DefenseBatchService;
import org.spring_web.spring_boot_service.service.DefenseService;
import org.spring_web.spring_boot_service.service.DefenseSourceService;
import org.spring_web.spring_boot_service.service.DefenseViolationService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 答辩：教师发起/查看/评分，学生开始作答（录音 + 摄像头在场检测）并查看结果。
 *
 * <p>URL 前缀角色由 {@code SecurityConfig} 兜底：{@code /teacher/**} 只有教师、
 * {@code /student/**} 只有学生、{@code /defense/**} 需登录；会话级归属校验
 * （开课所有者或学生本人）在 {@link DefenseService} 内做。</p>
 */
@Tag(name = "答辩（语音转写 + 在场检测）")
@RestController
@RequiredArgsConstructor
public class DefenseController {

    private final DefenseService defenseService;
    private final DefenseSourceService sourceService;
    private final DefenseViolationService violationService;
    private final DefenseBatchService batchService;

    @Operation(summary = "教师发起答辩：AI 根据选题与已提交材料出题并创建会话")
    @PostMapping("/api/v1/teacher/defense/sessions")
    public Result<DefenseDtos.SessionVO> createSession(
            @RequestBody DefenseDtos.SessionCreateRequest request) {
        return Result.ok(defenseService.createSession(
                request.selectionId(), request.questionCount(), request.overrideReason()));
    }

    @Operation(summary = "批量发起答辩：受理后立即返回 batchId，前端轮询进度")
    @PostMapping("/api/v1/teacher/defenses/batch")
    public Result<DefenseDtos.BatchResultVO> batch(
            @RequestBody DefenseDtos.BatchSessionRequest request) {
        return Result.ok(batchService.start(request));
    }

    @Operation(summary = "批量发起进度 / 逐条结果")
    @GetMapping("/api/v1/teacher/defenses/batch/{batchId}")
    public Result<DefenseDtos.BatchResultVO> batchDetail(@PathVariable String batchId) {
        return Result.ok(batchService.detail(batchId));
    }

    @Operation(summary = "题数分档 / 三原则阈值 / 加分题配置（前端预览与规则说明共用）")
    @GetMapping("/api/v1/teacher/defense/question-rule")
    public Result<DefenseDtos.QuestionRuleVO> questionRule() {
        return Result.ok(defenseService.questionRule());
    }

    @Operation(summary = "教师覆盖「完全依赖 AI」判定：理由必填，仅留痕")
    @PostMapping("/api/v1/teacher/defense/sessions/{sessionId}/ai-dependent-override")
    public Result<DefenseDtos.OverrideAiDependentVO> overrideAiDependent(
            @PathVariable String sessionId,
            @RequestBody DefenseDtos.OverrideAiDependentRequest request) {
        return Result.ok(defenseService.overrideAiDependent(sessionId, request));
    }

    @Operation(summary = "教师端答辩会话列表（按创建时间倒序）")
    @GetMapping("/api/v1/teacher/defense/sessions")
    public Result<List<DefenseDtos.SessionVO>> sessions(@RequestParam String offeringId) {
        return Result.ok(defenseService.list(offeringId));
    }

    @Operation(summary = "可发起答辩的选题与学生（含最新会话状态）")
    @GetMapping("/api/v1/teacher/offerings/{offeringId}/defense/candidates")
    public Result<List<DefenseDtos.CandidateVO>> candidates(@PathVariable String offeringId) {
        return Result.ok(defenseService.candidates(offeringId));
    }

    @Operation(summary = "答辩会话详情：教师看评分要点/参考答案，学生看裁剪版")
    @GetMapping("/api/v1/defense/sessions/{sessionId}")
    public Result<DefenseDtos.SessionVO> detail(@PathVariable String sessionId) {
        return Result.ok(defenseService.detail(sessionId));
    }

    @Operation(summary = "开始答辩（READY → IN_PROGRESS）")
    @PostMapping("/api/v1/defense/sessions/{sessionId}/start")
    public Result<DefenseDtos.SessionVO> start(@PathVariable String sessionId) {
        return Result.ok(defenseService.start(sessionId));
    }

    @Operation(summary = "上传单题录音并转写（multipart file + durationMs）")
    @PostMapping("/api/v1/defense/sessions/{sessionId}/questions/{questionId}/answer")
    public Result<DefenseDtos.QuestionVO> uploadAnswer(
            @PathVariable String sessionId,
            @PathVariable String questionId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "durationMs", required = false) Long durationMs) {
        return Result.ok(defenseService.uploadAnswer(sessionId, questionId, file, durationMs));
    }

    @Operation(summary = "跳过加分题（仅加分题可跳过，记 SKIPPED 留痕）")
    @PostMapping("/api/v1/defense/sessions/{sessionId}/questions/{questionId}/skip")
    public Result<DefenseDtos.QuestionVO> skipBonus(
            @PathVariable String sessionId,
            @PathVariable String questionId) {
        return Result.ok(defenseService.skipBonus(sessionId, questionId));
    }

    @Operation(summary = "摄像头在场检测：只判画面里有没有人，图片不落库")
    @PostMapping("/api/v1/defense/sessions/{sessionId}/face-check")
    public Result<DefenseDtos.FaceCheckVO> faceCheck(
            @PathVariable String sessionId,
            @RequestBody DefenseDtos.FaceCheckRequest request) {
        return Result.ok(defenseService.faceCheck(sessionId, request.imageBase64()));
    }

    @Operation(summary = "上传整场答辩录像（multipart file + durationMs），返回最新会话")
    @PostMapping(value = "/api/v1/defense/sessions/{sessionId}/video",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<DefenseDtos.SessionVO> uploadVideo(
            @PathVariable String sessionId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "durationMs", required = false) Long durationMs) {
        return Result.ok(defenseService.uploadVideo(sessionId, file, durationMs));
    }

    @Operation(summary = "答辩录像回放：登录鉴权后以 inline 流式输出")
    @GetMapping("/api/v1/defense/sessions/{sessionId}/video")
    public ResponseEntity<Resource> video(@PathVariable String sessionId) {
        DefenseService.VideoDownload download = defenseService.streamVideo(sessionId);
        if (download == null || download.path() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        String encoded = URLEncoder.encode(
                download.filename() == null ? "defense-video.webm" : download.filename(),
                StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(videoMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .body(new FileSystemResource(download.path()));
    }

    /**
     * 从存储的 Content-Type 得到可回放的视频媒体类型。
     *
     * <p>浏览器 MediaRecorder 上传的 part Content-Type 常带编码参数
     * （{@code video/webm;codecs=vp8,opus}），而 {@link MediaType} 解析不接受逗号 token，
     * 这里只保留 type/subtype；非 video/* 或解析失败统一兜底 video/webm（本接口只回放录像）。</p>
     */
    private MediaType videoMediaType(String contentType) {
        MediaType fallback = MediaType.parseMediaType("video/webm");
        if (contentType == null || contentType.isBlank()) {
            return fallback;
        }
        String value = contentType;
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon);
        }
        try {
            MediaType parsed = MediaType.parseMediaType(value.trim());
            return "video".equalsIgnoreCase(parsed.getType()) ? parsed : fallback;
        } catch (InvalidMediaTypeException ex) {
            return fallback;
        }
    }

    @Operation(summary = "结束答辩（IN_PROGRESS → COMPLETED）")
    @PostMapping("/api/v1/defense/sessions/{sessionId}/finish")
    public Result<DefenseDtos.SessionVO> finish(@PathVariable String sessionId) {
        return Result.ok(defenseService.finish(sessionId));
    }

    @Operation(summary = "教师评分：AI 失败返回 50301，绝不伪造分数")
    @PostMapping("/api/v1/defense/sessions/{sessionId}/evaluate")
    public Result<DefenseDtos.EvaluateResultVO> evaluate(@PathVariable String sessionId) {
        return Result.ok(defenseService.evaluate(sessionId));
    }

    @Operation(summary = "学生按选题查自己的答辩会话（无则 40401）")
    @GetMapping("/api/v1/student/defense/selections/{selectionId}")
    public Result<DefenseDtos.SessionVO> studentSession(@PathVariable String selectionId) {
        return Result.ok(defenseService.studentSession(selectionId));
    }

    // ---------------------------------------------------------------- 只读源码查看

    /**
     * 答辩期间查看本人提交的源码（解压后的纯文本，绝不返回 zip 字节或下载链接）。
     *
     * <p>路径挂在 {@code /defense/**} 下复用登录鉴权，真正的「只有本人 + 进行中」判定在
     * {@link DefenseSourceService#sourceCode} 里逐环执行，不依赖前端已做任何校验。</p>
     */
    @Operation(summary = "答辩期间只读查看本人源码（学生本人 + 会话进行中）")
    @GetMapping("/api/v1/defense/sessions/{sessionId}/source-code")
    public Result<DefenseDtos.SourceCodeVO> sourceCode(@PathVariable String sessionId) {
        return Result.ok(sourceService.sourceCode(sessionId));
    }

    // ---------------------------------------------------------------- 违规上报与教师裁决

    @Operation(summary = "上报一条违规证据（前端辅助检测，仅作证据不作裁决）")
    @PostMapping("/api/v1/defense/sessions/{sessionId}/violation")
    public Result<DefenseDtos.ViolationReportVO> reportViolation(
            @PathVariable String sessionId,
            @RequestBody(required = false) DefenseDtos.ViolationReportRequest request) {
        return Result.ok(violationService.report(sessionId, request));
    }

    @Operation(summary = "教师端：本开课下的答辩违规汇总（含证据与当前计数）")
    @GetMapping("/api/v1/teacher/offerings/{offeringId}/defense/violations")
    public Result<List<DefenseDtos.SessionViolationVO>> violations(@PathVariable String offeringId) {
        return Result.ok(violationService.violations(offeringId));
    }

    @Operation(summary = "教师确认误报：恢复答辩（VIOLATED → IN_PROGRESS）")
    @PostMapping("/api/v1/teacher/defense/sessions/{sessionId}/resume")
    public Result<DefenseDtos.ViolationDecisionVO> resume(@PathVariable String sessionId) {
        return Result.ok(violationService.resume(sessionId));
    }

    @Operation(summary = "教师确认违规：维持终止（VIOLATED → COMPLETED，成绩留空待评）")
    @PostMapping("/api/v1/teacher/defense/sessions/{sessionId}/uphold")
    public Result<DefenseDtos.ViolationDecisionVO> uphold(@PathVariable String sessionId) {
        return Result.ok(violationService.uphold(sessionId));
    }
}
