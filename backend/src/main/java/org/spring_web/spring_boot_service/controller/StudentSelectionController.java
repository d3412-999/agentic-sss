package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.Idempotent;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.OfferingDtos;
import org.spring_web.spring_boot_service.dto.SelectionDtos;
import org.spring_web.spring_boot_service.service.OfferingService;
import org.spring_web.spring_boot_service.service.SelectionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端：可选开课、选题与退选。
 */
@Tag(name = "学生 - 开课与选题")
@RestController
@RequiredArgsConstructor
public class StudentSelectionController {

    private final OfferingService offeringService;
    private final SelectionService selectionService;

    @GetMapping("/api/v1/student/offerings")
    public Result<PageResult<OfferingDtos.StudentOfferingVO>> offerings(
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(offeringService.studentOfferings(semester, keyword, page, size));
    }

    @Operation(summary = "批量选题，支持 Idempotency-Key；容量由数据库原子预占")
    @Idempotent
    @PostMapping("/api/v1/student/selections")
    public Result<List<SelectionDtos.CreatedItemVO>> select(
            @Valid @RequestBody SelectionDtos.SelectionCreateRequest request) {
        return Result.ok(selectionService.create(request));
    }

    @GetMapping("/api/v1/student/selections")
    public Result<PageResult<SelectionDtos.SelectionVO>> selections(
            @RequestParam(required = false) String offeringId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(selectionService.mine(offeringId, page, size));
    }

    @GetMapping("/api/v1/student/selections/{selectionId}")
    public Result<SelectionDtos.SelectionDetailVO> selectionDetail(@PathVariable String selectionId) {
        return Result.ok(selectionService.detail(selectionId));
    }

    @Operation(summary = "退选，释放题目名额")
    @DeleteMapping("/api/v1/student/selections/{selectionId}")
    public Result<Void> cancel(@PathVariable String selectionId) {
        selectionService.cancel(selectionId);
        return Result.ok();
    }
}
