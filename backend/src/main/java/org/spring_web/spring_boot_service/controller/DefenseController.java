package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.DefenseDtos;
import org.spring_web.spring_boot_service.service.DefenseService;
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

    @Operation(summary = "教师发起答辩：AI 根据选题与已提交材料出题并创建会话")
    @PostMapping("/api/v1/teacher/defense/sessions")
    public Result<DefenseDtos.SessionVO> createSession(
            @RequestBody DefenseDtos.SessionCreateRequest request) {
        return Result.ok(defenseService.createSession(request.selectionId(), request.questionCount()));
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

    @Operation(summary = "摄像头在场检测：只判画面里有没有人，图片不落库")
    @PostMapping("/api/v1/defense/sessions/{sessionId}/face-check")
    public Result<DefenseDtos.FaceCheckVO> faceCheck(
            @PathVariable String sessionId,
            @RequestBody DefenseDtos.FaceCheckRequest request) {
        return Result.ok(defenseService.faceCheck(sessionId, request.imageBase64()));
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
}
