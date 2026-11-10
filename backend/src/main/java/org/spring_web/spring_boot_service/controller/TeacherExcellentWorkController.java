package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.ExcellentWorkDtos;
import org.spring_web.spring_boot_service.service.ExcellentWorkService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师端：优秀作品模板库。
 *
 * <p>教师从自己的开课中挑选优秀报告，加入 AI 服务的模板库；
 * 后续报告评审可以把这些作品作为 RAG 参考。</p>
 */
@Tag(name = "教师 - 优秀作品模板库")
@RestController
@RequestMapping("/api/v1/teacher/offerings/{offeringId}/excellent-works")
@RequiredArgsConstructor
public class TeacherExcellentWorkController {

    private final ExcellentWorkService excellentWorkService;

    @Operation(summary = "可加入模板库的候选选题（含报告得分）")
    @GetMapping("/candidates")
    public Result<List<ExcellentWorkDtos.CandidateVO>> candidates(@PathVariable String offeringId) {
        return Result.ok(excellentWorkService.candidates(offeringId));
    }

    @Operation(summary = "已加入模板库的优秀作品")
    @GetMapping
    public Result<List<ExcellentWorkDtos.WorkVO>> list(@PathVariable String offeringId) {
        return Result.ok(excellentWorkService.list(offeringId));
    }

    @Operation(summary = "把选题的报告加入模板库")
    @PostMapping
    public Result<ExcellentWorkDtos.WorkVO> create(
            @PathVariable String offeringId,
            @Valid @RequestBody ExcellentWorkDtos.CreateRequest request) {
        return Result.ok(excellentWorkService.create(offeringId, request));
    }

    @Operation(summary = "从模板库移除")
    @DeleteMapping("/{workId}")
    public Result<Void> delete(@PathVariable String offeringId, @PathVariable String workId) {
        excellentWorkService.delete(offeringId, workId);
        return Result.ok();
    }

    @Operation(summary = "重新索引（用于失败后重试）")
    @PostMapping("/{workId}/reindex")
    public Result<ExcellentWorkDtos.WorkVO> reindex(
            @PathVariable String offeringId, @PathVariable String workId) {
        return Result.ok(excellentWorkService.reindex(offeringId, workId));
    }
}
