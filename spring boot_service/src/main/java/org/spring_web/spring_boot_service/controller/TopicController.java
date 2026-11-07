package org.spring_web.spring_boot_service.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Result;
import org.spring_web.spring_boot_service.dto.TopicDtos;
import org.spring_web.spring_boot_service.service.TopicService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题目：教师管理（/teacher/...）、题目详情（/topics/...）、学生浏览（/student/...）。
 *
 * <p>路径分三处是有意为之：不同前缀对应不同的授权边界，
 * 但底层都复用同一套 TopicService，避免出现两套互相矛盾的容量逻辑。</p>
 */
@Tag(name = "题目管理")
@RestController
@RequiredArgsConstructor
public class TopicController {

    private final TopicService topicService;

    @GetMapping("/api/v1/teacher/offerings/{offeringId}/topics")
    public Result<PageResult<TopicDtos.TopicListItemVO>> teacherTopics(
            @PathVariable String offeringId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(topicService.teacherTopics(offeringId, status, keyword, page, size));
    }

    @PostMapping("/api/v1/teacher/offerings/{offeringId}/topics")
    public Result<TopicDtos.TopicVO> createTopic(
            @PathVariable String offeringId,
            @Valid @RequestBody TopicDtos.TopicCreateRequest request) {
        return Result.ok(topicService.create(offeringId, request));
    }

    @PostMapping("/api/v1/teacher/offerings/{offeringId}/topics/publish")
    public Result<TopicDtos.PublishBatchResult> publishBatch(
            @PathVariable String offeringId,
            @Valid @RequestBody TopicDtos.PublishBatchRequest request) {
        return Result.ok(topicService.publishBatch(offeringId, request));
    }

    @GetMapping("/api/v1/topics/{topicId}")
    public Result<TopicDtos.TopicVO> topicDetail(@PathVariable String topicId) {
        return Result.ok(topicService.detail(topicId));
    }

    @PutMapping("/api/v1/topics/{topicId}")
    public Result<TopicDtos.TopicVO> updateTopic(
            @PathVariable String topicId,
            @Valid @RequestBody TopicDtos.TopicUpdateRequest request) {
        return Result.ok(topicService.update(topicId, request));
    }

    @DeleteMapping("/api/v1/topics/{topicId}")
    public Result<Void> deleteTopic(@PathVariable String topicId) {
        topicService.delete(topicId);
        return Result.ok();
    }

    @PostMapping("/api/v1/topics/{topicId}/publish")
    public Result<TopicDtos.TopicVO> publishTopic(@PathVariable String topicId) {
        return Result.ok(topicService.publish(topicId));
    }

    @PostMapping("/api/v1/topics/{topicId}/archive")
    public Result<TopicDtos.TopicVO> archiveTopic(@PathVariable String topicId) {
        return Result.ok(topicService.archive(topicId));
    }

    @GetMapping("/api/v1/student/offerings/{offeringId}/topics")
    public Result<PageResult<TopicDtos.StudentTopicVO>> studentTopics(
            @PathVariable String offeringId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String difficulty,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(topicService.studentTopics(offeringId, keyword, difficulty, page, size));
    }

    @GetMapping("/api/v1/student/offerings/{offeringId}/topics/{topicId}")
    public Result<TopicDtos.StudentTopicDetailVO> studentTopicDetail(
            @PathVariable String offeringId, @PathVariable String topicId) {
        return Result.ok(topicService.studentTopicDetail(offeringId, topicId));
    }
}
