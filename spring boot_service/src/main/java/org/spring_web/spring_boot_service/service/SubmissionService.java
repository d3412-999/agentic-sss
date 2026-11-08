package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.entity.AsyncTask;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.entity.Offering;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.SubmissionVersion;
import org.spring_web.spring_boot_service.mapper.OfferingMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionVersionMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提交物（源码 zip / 报告 pdf、docx）的版本管理。
 *
 * <p>一次上传 = 一个 submission_version，评分始终取「最新有效版本」；
 * 删除最新版本时自动回退到上一个有效版本，保证成绩计算永远有据可依。</p>
 */
@Service
@RequiredArgsConstructor
public class SubmissionService {

    private static final long MAX_UNPACKED_BYTES = 500L * 1024 * 1024;
    private static final int MAX_ENTRIES = 3000;

    private final SubmissionMapper submissionMapper;
    private final SubmissionVersionMapper versionMapper;
    private final SelectionMapper selectionMapper;
    private final OfferingMapper offeringMapper;
    private final FileStorageService fileStorage;
    private final AsyncTaskService taskService;
    private final AiEvaluationService aiEvaluationService;
    private final SelectionService selectionService;
    private final OfferingService offeringService;
    private final SssProperties properties;
    private final JsonCodec json;

    @Transactional(rollbackFor = Exception.class)
    public CommonDtos.UploadResultVO upload(String selectionId, String type, MultipartFile file,
                                            String remark, String sha256) {
        Selection selection = ownedOrTeacherSelection(selectionId, true);
        BizAssert.param("SOURCE".equals(type) || "REPORT".equals(type), "type 只能是 SOURCE 或 REPORT");
        boolean source = "SOURCE".equals(type);

        if (source) {
            validateArchive(file);
        } else {
            String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
            BizAssert.param(filename.endsWith(".pdf") || filename.endsWith(".docx"), "报告只支持 PDF / DOCX");
        }

        long maxBytes = source ? properties.getStorage().getMaxSourceBytes()
                : properties.getStorage().getMaxReportBytes();
        FileObject stored = fileStorage.store(file, type, sha256, maxBytes);

        Submission submission = submissionMapper.selectOne(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getSelectionId, selection.getId())
                .eq(Submission::getType, type).last("limit 1"));
        if (submission == null) {
            submission = new Submission();
            submission.setSelectionId(selection.getId());
            submission.setType(type);
            submission.setLatestVersion(0);
            submission.setStatus("UPLOADED");
            submission.setCreatedAt(Times.now());
            submission.setUpdatedAt(Times.now());
            submissionMapper.insert(submission);
        }
        // 版本号由数据库原子分配：并发上传时「查出来再 +1」会撞 (submission_id, version) 唯一索引
        if (submissionMapper.bumpLatestVersion(submission.getId()) != 1) {
            throw new BizException(ErrorCode.CONFLICT, "版本号分配失败，请重试");
        }
        Submission latest = BizAssert.found(submissionMapper.selectById(submission.getId()), "提交物");
        int nextVersion = latest.getLatestVersion() == null ? 1 : latest.getLatestVersion();

        SubmissionVersion version = new SubmissionVersion();
        version.setSubmissionId(submission.getId());
        version.setVersion(nextVersion);
        version.setFileId(stored.getId());
        version.setStatus("PARSING");
        version.setRemark(remark);
        version.setCreatedBy(SecurityUtils.userId());
        version.setCreatedAt(Times.now());
        version.setUpdatedAt(Times.now());
        versionMapper.insert(version);

        submission.setLatestVersion(nextVersion);
        submission.setLatestVersionId(version.getId());
        submission.setStatus("PARSING");
        submission.setUpdatedAt(Times.now());
        submissionMapper.updateById(submission);
        selectionService.markSubmitted(selection.getId());

        AsyncTask task = taskService.create(source ? "PARSE_SOURCE" : "PARSE_REPORT",
                "SUBMISSION", submission.getId(), Map.of("versionId", version.getId()));
        // 异步任务在另一个线程，若在事务提交前启动会读不到刚插入的记录。
        // 注册 afterCommit 回调，保证「先落库、后开跑」。
        Long taskId = task.getId();
        Long submissionPk = submission.getId();
        Long versionPk = version.getId();
        Long filePk = stored.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                aiEvaluationService.parseAndEvaluateAsync(taskId, submissionPk, versionPk, filePk, type);
            }
        });

        return new CommonDtos.UploadResultVO(String.valueOf(submission.getId()),
                String.valueOf(selection.getId()), type, nextVersion, "PARSING",
                fileStorage.toVo(stored), String.valueOf(task.getId()), null,
                Times.offset(version.getCreatedAt()));
    }

    public PageResult<CommonDtos.SubmissionBriefVO> list(String selectionId) {
        Selection selection = ownedOrTeacherSelection(selectionId, false);
        List<Submission> submissions = submissionMapper.selectList(
                new LambdaQueryWrapper<Submission>().eq(Submission::getSelectionId, selection.getId()));
        List<CommonDtos.SubmissionBriefVO> list = new ArrayList<>();
        for (Submission submission : submissions) {
            FileObject file = fileOf(submission);
            list.add(new CommonDtos.SubmissionBriefVO(String.valueOf(submission.getId()),
                    submission.getType(), submission.getLatestVersion(), submission.getStatus(),
                    file == null ? 0 : file.getSizeBytes(), Times.offset(submission.getCreatedAt())));
        }
        return PageResult.of(list, list.size(), 1, Math.max(1, list.size()));
    }

    public CommonDtos.SubmissionVO detail(String submissionId) {
        Submission submission = readableSubmission(submissionId);
        FileObject file = fileOf(submission);
        SubmissionVersion version = submission.getLatestVersionId() == null ? null
                : versionMapper.selectById(submission.getLatestVersionId());
        return new CommonDtos.SubmissionVO(String.valueOf(submission.getId()), submission.getType(),
                submission.getLatestVersion(), submission.getStatus(),
                file == null ? null : fileStorage.toVo(file),
                version == null ? null : json.object(version.getParseResult()), null,
                Times.offset(submission.getCreatedAt()), Times.offset(submission.getUpdatedAt()));
    }

    public Download download(String submissionId) {
        Submission submission = readableSubmission(submissionId);
        FileObject file = BizAssert.found(fileOf(submission), "提交文件");
        Path path = BizAssert.found(fileStorage.locate(file), "提交文件");
        return new Download(file.getFilename(), file.getContentType(), path);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String submissionId) {
        Long id = BizAssert.id(submissionId, "submissionId");
        Submission submission = BizAssert.found(submissionMapper.selectById(id), "提交物");
        Selection selection = ownedOrTeacherSelection(String.valueOf(submission.getSelectionId()), true);
        Offering offering = BizAssert.found(
                offeringMapper.selectById(selection.getOfferingId()), "开课");
        BizAssert.state(offering.getSubmitDeadline() == null
                        || Times.now().isBefore(offering.getSubmitDeadline()),
                "已过提交截止时间，不能删除");
        SubmissionVersion version = submission.getLatestVersionId() == null ? null
                : versionMapper.selectById(submission.getLatestVersionId());
        if (version != null) {
            version.setStatus("DELETED");
            version.setUpdatedAt(Times.now());
            versionMapper.updateById(version);
        }
        // 回退到上一个有效版本
        SubmissionVersion previous = versionMapper.selectOne(new LambdaQueryWrapper<SubmissionVersion>()
                .eq(SubmissionVersion::getSubmissionId, submission.getId())
                .ne(SubmissionVersion::getStatus, "DELETED")
                .orderByDesc(SubmissionVersion::getVersion).last("limit 1"));
        if (previous == null) {
            submission.setStatus("DELETED");
            submission.setLatestVersion(0);
            submission.setLatestVersionId(null);
        } else {
            submission.setLatestVersion(previous.getVersion());
            submission.setLatestVersionId(previous.getId());
            submission.setStatus("VALID");
        }
        submission.setUpdatedAt(Times.now());
        submissionMapper.updateById(submission);
    }

    /** 手动触发重新分析/评审，返回任务号。 */
    public CommonDtos.TaskAccepted reanalyze(String submissionId, boolean force) {
        Submission submission = readableSubmission(submissionId);
        boolean source = "SOURCE".equals(submission.getType());
        AsyncTask task = taskService.create(source ? "CODE_ANALYSIS" : "REPORT_EVALUATION",
                "SUBMISSION", submission.getId(), Map.of("force", force));
        FileObject file = fileOf(submission);
        aiEvaluationService.parseAndEvaluateAsync(task.getId(), submission.getId(),
                submission.getLatestVersionId(), file == null ? null : file.getId(), submission.getType());
        return new CommonDtos.TaskAccepted(String.valueOf(task.getId()), task.getStatus());
    }

    // ---------------------------------------------------------------- 内部

    private FileObject fileOf(Submission submission) {
        if (submission.getLatestVersionId() == null) {
            return null;
        }
        SubmissionVersion version = versionMapper.selectById(submission.getLatestVersionId());
        if (version == null || version.getFileId() == null) {
            return null;
        }
        return fileStorage.load(String.valueOf(version.getFileId()));
    }

    /** 学生只能操作自己的选题；教师可查看本开课学生的选题，但不能替学生上传。 */
    private Selection ownedOrTeacherSelection(String selectionId, boolean requireOwner) {
        Selection selection = BizAssert.found(
                selectionMapper.selectById(BizAssert.id(selectionId, "selectionId")), "选题");
        Long uid = SecurityUtils.userId();
        if (selection.getStudentId().equals(uid)) {
            return selection;
        }
        if (requireOwner) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权上传该选题的材料");
        }
        offeringService.ownedOffering(String.valueOf(selection.getOfferingId()));
        return selection;
    }

    private Submission readableSubmission(String submissionId) {
        Submission submission = BizAssert.found(
                submissionMapper.selectById(BizAssert.id(submissionId, "submissionId")), "提交物");
        ownedOrTeacherSelection(String.valueOf(submission.getSelectionId()), false);
        return submission;
    }

    /** Zip Slip / 压缩炸弹 / 可执行文件校验，全部在入库前完成。 */
    private void validateArchive(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        BizAssert.param(filename.endsWith(".zip") || filename.endsWith(".jar"), "源码包只支持 ZIP / JAR");
        if (!filename.endsWith(".zip")) {
            return;
        }
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            int count = 0;
            long total = 0;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRIES) {
                    throw invalid("TOO_MANY_ENTRIES", "压缩包文件数超过 3000");
                }
                String name = entry.getName();
                java.nio.file.Path normalized = java.nio.file.Paths.get(name).normalize();
                if (normalized.isAbsolute() || name.contains("..")
                        || name.startsWith("/") || name.startsWith("\\")) {
                    throw invalid("ZIP_SLIP", "压缩包包含非法路径：" + name);
                }
                if (!entry.isDirectory()) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".exe") || lower.endsWith(".dll") || lower.endsWith(".so")
                            || lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".sh")) {
                        throw invalid("UNSAFE_FILE", "压缩包包含可执行文件：" + name);
                    }
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_UNPACKED_BYTES) {
                            throw invalid("UNPACKED_TOO_LARGE", "解压后超过 500 MB");
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw invalid("ZIP_BROKEN", "压缩包无法解析");
        }
    }

    private BizException invalid(String code, String message) {
        return new BizException(ErrorCode.FILE_INVALID, message,
                Map.of("errors", List.of(Map.of("code", code, "message", message))));
    }

    public record Download(String filename, String contentType, Path path) {
    }
}
