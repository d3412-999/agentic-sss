package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.ArchiveGuard;
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
import org.spring_web.spring_boot_service.entity.SubmissionVersionFile;
import org.spring_web.spring_boot_service.mapper.OfferingMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionVersionFileMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionVersionMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提交物（源码 / 报告）的版本与文件管理。
 *
 * <p><b>两条写入路径，一个落库出口：</b></p>
 * <ul>
 *   <li>旧接口 {@code /selections/{id}/source}：单文件直传，内部先存文件再走 {@link #bind}；</li>
 *   <li>新接口 {@code /selections/{id}/submissions}：两阶段，先 {@code POST /files} 拿 fileId
 *       列表，再确认绑定。错误隔离更好——一个坏包不会让另外 8 个好包白传。</li>
 * </ul>
 *
 * <p>一次提交 = 一个 {@code submission_version} + N 条 {@code submission_version_file}。
 * 文件挂在版本上，历史版本的多文件组合才能被完整还原。</p>
 */
@Service
@RequiredArgsConstructor
public class SubmissionService {

    /** 计算结构指纹时最多读这么多条目，避免恶意大包把这一步拖成秒级操作。 */
    private static final int MAX_ENTRIES_FOR_FINGERPRINT = 5000;

    private final SubmissionMapper submissionMapper;
    private final SubmissionVersionMapper versionMapper;
    private final SubmissionVersionFileMapper versionFileMapper;
    private final SelectionMapper selectionMapper;
    private final OfferingMapper offeringMapper;
    private final FileStorageService fileStorage;
    private final AsyncTaskService taskService;
    private final AiEvaluationService aiEvaluationService;
    private final SelectionService selectionService;
    private final OfferingService offeringService;
    private final SimilarityService similarityService;
    private final AuditLogService auditLogService;
    private final SssProperties properties;
    private final JsonCodec json;

    // ---------------------------------------------------------------- 旧接口：单文件直传

    @Transactional(rollbackFor = Exception.class)
    public CommonDtos.UploadResultVO upload(String selectionId, String type, MultipartFile file,
                                            String remark, String sha256) {
        Selection selection = ownedOrTeacherSelection(selectionId, true);
        String kind = requireType(type);
        SssProperties.SubmissionType rule = ruleOf(kind);

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        BizAssert.param(endsWithAny(filename, rule.getAllowedExtensions()), invalidExtension(kind, rule));
        BizAssert.param(file.getSize() > 0, "文件不能为空");
        BizAssert.param(file.getSize() <= rule.getMaxFileBytes(),
                "单个文件不能超过 " + humanSize(rule.getMaxFileBytes()));

        FileObject stored = fileStorage.store(file, kind, sha256, rule.getMaxFileBytes());
        try {
            return bind(selection, kind, List.of(stored), remark);
        }
        catch (RuntimeException ex) {
            // 归档校验失败时文件已经落盘，不清理就会留下查不出来的孤儿
            fileStorage.deleteQuietly(stored);
            throw ex;
        }
    }

    // ---------------------------------------------------------------- 新接口：两阶段绑定

    /**
     * 把已上传的 fileId 列表绑定成一次提交。
     *
     * <p>数量、扩展名、大小、归属、Zip Slip 全部在绑定这一步再校验一遍：
     * 文件接口不知道调用方要拿它做什么，只有这里知道「这是源码 / 报告」。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public CommonDtos.UploadResultVO uploadBatch(String selectionId, String type,
                                                 List<String> fileIds, String remark) {
        Selection selection = ownedOrTeacherSelection(selectionId, true);
        String kind = requireType(type);
        BizAssert.param(fileIds != null && !fileIds.isEmpty(), "请至少选择 1 个" + label(kind) + "文件");

        List<FileObject> files = new ArrayList<>(fileIds.size());
        for (String fileId : fileIds) {
            files.add(fileStorage.load(fileId));
        }
        return bind(selection, kind, files, remark);
    }

    /**
     * 两阶段第一步：把单个文件落盘，返回 fileId。
     *
     * <p>前端每个文件发一次请求，所以能拿到逐文件的真实进度、失败原因与重试能力——
     * 一次 multipart 传 9 个文件只有一个总进度条，失败时也不知道是哪一个坏了。</p>
     *
     * <p>这里做的校验：归属、扩展名、非空、单文件大小、文件头魔数（防改后缀），
     * 源码额外跑一次 Zip Slip / 压缩炸弹扫描。绑定阶段还会再校验一遍数量与归属。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public CommonDtos.FileVO storeFile(String selectionId, String type, MultipartFile file) {
        Selection selection = ownedOrTeacherSelection(selectionId, true);
        String kind = requireType(type);
        SssProperties.SubmissionType rule = ruleOf(kind);

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        BizAssert.param(endsWithAny(filename, rule.getAllowedExtensions()), invalidExtension(kind, rule));
        BizAssert.param(file.getSize() > 0, "文件不能为空");
        BizAssert.param(file.getSize() <= rule.getMaxFileBytes(),
                filename + " 超过单文件上限 " + humanSize(rule.getMaxFileBytes()));
        sniffSignature(file, kind, filename);
        if ("SOURCE".equals(kind)) {
            validateArchiveStream(file);
        }
        FileObject stored = fileStorage.store(file, kind, null, rule.getMaxFileBytes());
        return fileStorage.toVo(stored);
    }

    // ---------------------------------------------------------------- 查询

    public PageResult<CommonDtos.SubmissionBriefVO> list(String selectionId) {
        Selection selection = ownedOrTeacherSelection(selectionId, false);
        List<Submission> submissions = submissionMapper.selectList(
                new LambdaQueryWrapper<Submission>().eq(Submission::getSelectionId, selection.getId()));
        List<CommonDtos.SubmissionBriefVO> list = new ArrayList<>();
        for (Submission submission : submissions) {
            List<SubmissionVersionFile> files = submission.getLatestVersionId() == null
                    ? List.of() : versionFiles(submission.getLatestVersionId());
            long total = files.stream()
                    .mapToLong(f -> f.getSizeBytes() == null ? 0 : f.getSizeBytes()).sum();
            list.add(new CommonDtos.SubmissionBriefVO(String.valueOf(submission.getId()),
                    submission.getType(), submission.getLatestVersion(), submission.getStatus(),
                    total, Times.offset(submission.getCreatedAt())));
        }
        return PageResult.of(list, list.size(), 1, Math.max(1, list.size()));
    }

    public CommonDtos.SubmissionVO detail(String submissionId) {
        return detail(submissionId, null);
    }

    /**
     * 可读校验后返回提交物：学生只能看自己的，教师只能看本开课的。
     *
     * <p>给 {@link ReviewStatusService} 复用，避免把同一套归属判断抄第二遍。</p>
     */
    public Submission readable(String submissionId) {
        return readableSubmission(submissionId);
    }

    /**
     * 提交物详情，可指定历史版本。
     *
     * <p>不传 {@code versionId} 时给最新版本。历史版本是只读视图——
     * 查看旧版本不会生成新版本、不会触发评审，也不会改动 latest_version。</p>
     */
    public CommonDtos.SubmissionVO detail(String submissionId, String versionId) {
        Submission submission = readableSubmission(submissionId);
        SubmissionVersion version = resolveVersion(submission, versionId);
        List<SubmissionVersionFile> files = version == null ? List.of() : versionFiles(version.getId());
        List<CommonDtos.SubmissionFileVO> fileVos = new ArrayList<>(files.size());
        Map<Long, FileObject> objects = loadObjects(files);
        for (SubmissionVersionFile file : files) {
            fileVos.add(toVo(file, objects.get(file.getFileId())));
        }
        FileObject first = files.isEmpty() ? null : objects.get(files.get(0).getFileId());
        Integer versionNo = version == null ? submission.getLatestVersion() : version.getVersion();
        return new CommonDtos.SubmissionVO(String.valueOf(submission.getId()), submission.getType(),
                versionNo, submission.getStatus(),
                first == null ? null : fileStorage.toVo(first), fileVos,
                version == null ? null : json.object(version.getParseResult()), null,
                Times.offset(submission.getCreatedAt()), Times.offset(submission.getUpdatedAt()));
    }

    /**
     * 版本历史（供前端版本选择器使用）。
     *
     * <p>这是本次唯一的新增只读契约：{@code GET /submissions/{id}/versions}。
     * 原来的列表接口每类只返回一条「最新版本」，界面无法切到历史版本。</p>
     */
    public List<CommonDtos.SubmissionVersionVO> versions(String submissionId) {
        Submission submission = readableSubmission(submissionId);
        List<SubmissionVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<SubmissionVersion>()
                        .eq(SubmissionVersion::getSubmissionId, submission.getId())
                        .orderByDesc(SubmissionVersion::getVersion));
        List<CommonDtos.SubmissionVersionVO> list = new ArrayList<>(versions.size());
        for (SubmissionVersion version : versions) {
            List<SubmissionVersionFile> files = versionFiles(version.getId());
            long total = files.stream()
                    .mapToLong(f -> f.getSizeBytes() == null ? 0 : f.getSizeBytes()).sum();
            list.add(new CommonDtos.SubmissionVersionVO(
                    String.valueOf(version.getId()),
                    version.getVersion(),
                    version.getStatus(),
                    files.size(),
                    total,
                    version.getId().equals(submission.getLatestVersionId()),
                    Times.offset(version.getCreatedAt())));
        }
        return list;
    }

    /**
     * 定位要展示的版本。
     *
     * <p>历史数据里存在 {@code latest_version_id} 为空、或 {@code latest_version=0} 的记录
     * （早期只写 version 表没同步提交物）。这里兜底到「该提交下最大版本」，
     * 保证迁移前也能正常打开，不会白屏。</p>
     */
    private SubmissionVersion resolveVersion(Submission submission, String versionId) {
        if (versionId != null && !versionId.isBlank()) {
            SubmissionVersion target = versionMapper.selectById(BizAssert.id(versionId, "versionId"));
            BizAssert.param(target != null && submission.getId().equals(target.getSubmissionId()),
                    "该版本不属于此提交物");
            return target;
        }
        SubmissionVersion latest = submission.getLatestVersionId() == null ? null
                : versionMapper.selectById(submission.getLatestVersionId());
        if (latest != null) {
            return latest;
        }
        return versionMapper.selectOne(new LambdaQueryWrapper<SubmissionVersion>()
                .eq(SubmissionVersion::getSubmissionId, submission.getId())
                .orderByDesc(SubmissionVersion::getVersion)
                .orderByDesc(SubmissionVersion::getId)
                .last("limit 1"));
    }

    public Download download(String submissionId) {
        Submission submission = readableSubmission(submissionId);
        List<SubmissionVersionFile> files = submission.getLatestVersionId() == null
                ? List.of() : versionFiles(submission.getLatestVersionId());
        SubmissionVersionFile target = BizAssert.found(files.isEmpty() ? null : files.get(0), "提交文件");
        FileObject object = BizAssert.found(fileStorage.load(String.valueOf(target.getFileId())), "文件");
        Path path = BizAssert.found(fileStorage.locate(object), "提交文件");
        return new Download(object.getFilename(), object.getContentType(), path);
    }

    /** 按版本文件 ID 下载单个文件。旧接口仍按提交物下载首个文件。 */
    public Download downloadFile(String submissionId, String versionFileId) {
        Submission submission = readableSubmission(submissionId);
        SubmissionVersionFile target = BizAssert.found(
                versionFileMapper.selectById(BizAssert.id(versionFileId, "versionFileId")), "文件");
        BizAssert.param(submission.getId().equals(target.getSubmissionId()), "该文件不属于此提交物");
        FileObject object = BizAssert.found(fileStorage.load(String.valueOf(target.getFileId())), "文件");
        Path path = BizAssert.found(fileStorage.locate(object), "文件");
        return new Download(object.getFilename(), object.getContentType(), path);
    }

    /** 该提交命中的相似记录（本人或教师可查）。 */
    public List<CommonDtos.SimilarityVO> similarities(String submissionId) {
        Submission submission = readableSubmission(submissionId);
        return similarityService.list(submission.getId());
    }

    // ---------------------------------------------------------------- 单文件删除 / 排序

    /**
     * 删除版本内的单个文件。
     *
     * <p>刻意<b>不</b>自动重新评审：删一个误传的文件不该把已经出来的评审结果作废。
     * 需要重评时学生或教师显式点「重新评审」，把决策权留在人手里。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFile(String submissionId, String versionFileId) {
        Long sid = BizAssert.id(submissionId, "submissionId");
        Submission submission = BizAssert.found(submissionMapper.selectById(sid), "提交物");
        Selection selection = ownedOrTeacherSelection(String.valueOf(submission.getSelectionId()), true);
        checkDeadline(selection);

        SubmissionVersionFile target = BizAssert.found(
                versionFileMapper.selectById(BizAssert.id(versionFileId, "versionFileId")), "文件");
        BizAssert.param(submission.getId().equals(target.getSubmissionId()), "该文件不属于此提交物");

        versionFileMapper.deleteById(target.getId());
        auditLogService.record("SUBMISSION_FILE_DELETE", "SUBMISSION", submission.getId(),
                Map.of("file", target.getOriginalName(), "sha256", String.valueOf(target.getSha256())),
                null, "学生删除提交文件");

        List<SubmissionVersionFile> remain = versionFiles(target.getVersionId());
        if (!remain.isEmpty()) {
            return;
        }

        // 版本被删空：作废该版本，并回退到最近一个仍有文件的版本
        SubmissionVersion version = versionMapper.selectById(target.getVersionId());
        if (version != null) {
            version.setStatus("DELETED");
            version.setUpdatedAt(Times.now());
            versionMapper.updateById(version);
        }
        SubmissionVersion fallback = findPreviousWithFiles(submission.getId(), target.getVersionId());
        if (fallback == null) {
            submission.setStatus("DELETED");
            submission.setLatestVersion(0);
            submission.setLatestVersionId(null);
        }
        else {
            submission.setLatestVersion(fallback.getVersion());
            submission.setLatestVersionId(fallback.getId());
            submission.setStatus(fallback.getStatus());
        }
        submission.setUpdatedAt(Times.now());
        submissionMapper.updateById(submission);
    }

    /** 调整同一版本内的文件顺序；顺序同时决定评审时先看哪一份。 */
    @Transactional(rollbackFor = Exception.class)
    public void reorder(String submissionId, List<String> versionFileIds) {
        Long sid = BizAssert.id(submissionId, "submissionId");
        Submission submission = BizAssert.found(submissionMapper.selectById(sid), "提交物");
        ownedOrTeacherSelection(String.valueOf(submission.getSelectionId()), true);
        BizAssert.param(versionFileIds != null && !versionFileIds.isEmpty(), "排序列表不能为空");
        Long versionId = submission.getLatestVersionId();
        BizAssert.state(versionId != null, "当前没有可排序的文件");

        List<SubmissionVersionFile> current = versionFiles(versionId);
        BizAssert.param(current.size() == versionFileIds.size(),
                "排序列表与当前文件数不一致（" + current.size() + " 个）");

        Map<Long, SubmissionVersionFile> byId = new HashMap<>();
        for (SubmissionVersionFile file : current) {
            byId.put(file.getId(), file);
        }
        int index = 0;
        for (String raw : versionFileIds) {
            Long id = BizAssert.id(raw, "versionFileId");
            SubmissionVersionFile file = byId.remove(id);
            BizAssert.param(file != null, "排序列表包含不属于当前版本的文件：" + raw);
            file.setSortIndex(index++);
            file.setUpdatedAt(Times.now());
            versionFileMapper.updateById(file);
        }
    }

    // ---------------------------------------------------------------- 删除整个提交物

    @Transactional(rollbackFor = Exception.class)
    public void delete(String submissionId) {
        Long id = BizAssert.id(submissionId, "submissionId");
        Submission submission = BizAssert.found(submissionMapper.selectById(id), "提交物");
        Selection selection = ownedOrTeacherSelection(String.valueOf(submission.getSelectionId()), true);
        checkDeadline(selection);

        SubmissionVersion version = submission.getLatestVersionId() == null ? null
                : versionMapper.selectById(submission.getLatestVersionId());
        if (version != null) {
            version.setStatus("DELETED");
            version.setUpdatedAt(Times.now());
            versionMapper.updateById(version);
        }
        SubmissionVersion previous = findPreviousWithFiles(submission.getId(), submission.getLatestVersionId());
        if (previous == null) {
            submission.setStatus("DELETED");
            submission.setLatestVersion(0);
            submission.setLatestVersionId(null);
        }
        else {
            submission.setLatestVersion(previous.getVersion());
            submission.setLatestVersionId(previous.getId());
            submission.setStatus(previous.getStatus());
        }
        submission.setUpdatedAt(Times.now());
        submissionMapper.updateById(submission);
    }

    /** 手动触发重新分析/评审，返回任务号。 */
    public CommonDtos.TaskAccepted reanalyze(String submissionId, boolean force) {
        Submission submission = readableSubmission(submissionId);
        boolean source = "SOURCE".equals(submission.getType());
        Long versionId = submission.getLatestVersionId();
        List<SubmissionVersionFile> files = versionId == null ? List.of() : versionFiles(versionId);
        AsyncTask task = taskService.create(source ? "CODE_ANALYSIS" : "REPORT_EVALUATION",
                "SUBMISSION", submission.getId(), Map.of("force", force));
        aiEvaluationService.parseAndEvaluateAsync(task.getId(), submission.getId(), versionId,
                files.stream().map(SubmissionVersionFile::getFileId).toList(), submission.getType());
        return new CommonDtos.TaskAccepted(String.valueOf(task.getId()), task.getStatus());
    }

    // ---------------------------------------------------------------- 内部：绑定

    /**
     * 所有写入路径的唯一出口：校验 → 分配版本 → 写版本与文件清单 → 触发异步评审。
     */
    private CommonDtos.UploadResultVO bind(Selection selection, String type,
                                           List<FileObject> files, String remark) {
        SssProperties.SubmissionType rule = ruleOf(type);
        BizAssert.param(files.size() <= rule.getMaxFiles(),
                label(type) + "最多 " + rule.getMaxFiles() + " 个文件，当前 " + files.size() + " 个");
        long distinct = files.stream().map(FileObject::getId).distinct().count();
        BizAssert.param(distinct == files.size(), "存在重复文件，请检查后重试");

        Long uid = SecurityUtils.userId();
        for (FileObject file : files) {
            BizAssert.param(file.getCreatedBy() != null && file.getCreatedBy().equals(uid),
                    "只能提交本人上传的文件");
            String filename = file.getFilename() == null ? "" : file.getFilename();
            BizAssert.param(endsWithAny(filename, rule.getAllowedExtensions()), invalidExtension(type, rule));
            BizAssert.param(file.getSizeBytes() == null || file.getSizeBytes() > 0, "文件不能为空");
            BizAssert.param(file.getSizeBytes() == null || file.getSizeBytes() <= rule.getMaxFileBytes(),
                    file.getFilename() + " 超过单文件上限 " + humanSize(rule.getMaxFileBytes()));
            if ("SOURCE".equals(type)) {
                // 每一份 zip 都独立校验：只查第一份等于给后面几份开后门
                validateArchive(file);
            }
        }

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
        // 旧列保留并写入首个文件，历史读取路径（file_id 单文件）继续可用
        version.setFileId(files.get(0).getId());
        version.setStatus("PARSING");
        version.setRemark(remark);
        version.setCreatedBy(uid);
        version.setCreatedAt(Times.now());
        version.setUpdatedAt(Times.now());
        versionMapper.insert(version);

        List<SubmissionVersionFile> saved = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            FileObject file = files.get(i);
            SubmissionVersionFile row = new SubmissionVersionFile();
            row.setVersionId(version.getId());
            row.setSubmissionId(submission.getId());
            row.setSelectionId(selection.getId());
            row.setFileType(type);
            row.setFileId(file.getId());
            row.setOriginalName(file.getFilename());
            row.setSizeBytes(file.getSizeBytes());
            row.setSha256(file.getSha256());
            row.setStructureKey("SOURCE".equals(type) ? structureKey(file) : null);
            row.setSortIndex(i);
            row.setCreatedBy(uid);
            row.setCreatedAt(Times.now());
            row.setUpdatedAt(Times.now());
            versionFileMapper.insert(row);
            saved.add(row);
        }

        submission.setLatestVersion(nextVersion);
        submission.setLatestVersionId(version.getId());
        submission.setStatus("PARSING");
        submission.setUpdatedAt(Times.now());
        submissionMapper.updateById(submission);
        selectionService.markSubmitted(selection.getId());

        AsyncTask task = taskService.create("SOURCE".equals(type) ? "PARSE_SOURCE" : "PARSE_REPORT",
                "SUBMISSION", submission.getId(), Map.of("versionId", version.getId()));

        Long taskId = task.getId();
        Long submissionPk = submission.getId();
        Long versionPk = version.getId();
        List<Long> fileIds = files.stream().map(FileObject::getId).toList();
        Selection boundSelection = selection;
        Submission boundSubmission = submission;
        // 异步任务在另一个线程，若在事务提交前启动会读不到刚插入的记录。
        // 注册 afterCommit 回调，保证「先落库、后开跑」。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                aiEvaluationService.parseAndEvaluateAsync(taskId, submissionPk, versionPk, fileIds, type);
                try {
                    similarityService.check(boundSelection, boundSubmission, saved);
                }
                catch (RuntimeException ex) {
                    // 比对失败不能影响提交结果，只记日志等人工排查
                    org.slf4j.LoggerFactory.getLogger(SubmissionService.class)
                            .warn("相似度检测失败, submissionId={}, cause={}", submissionPk, ex.getMessage());
                }
            }
        });

        List<CommonDtos.SubmissionFileVO> fileVos = new ArrayList<>(saved.size());
        for (SubmissionVersionFile row : saved) {
            fileVos.add(toVo(row, null));
        }
        return new CommonDtos.UploadResultVO(String.valueOf(submission.getId()),
                String.valueOf(selection.getId()), type, nextVersion, "PARSING",
                fileStorage.toVo(files.get(0)), fileVos, String.valueOf(task.getId()), null,
                Times.offset(version.getCreatedAt()));
    }

    // ---------------------------------------------------------------- 内部：查询与校验

    private SssProperties.SubmissionType ruleOf(String type) {
        SssProperties.Submission submission = properties.getUpload().getSubmission();
        return "SOURCE".equals(type) ? submission.getSource() : submission.getReport();
    }

    private String requireType(String type) {
        BizAssert.param("SOURCE".equals(type) || "REPORT".equals(type), "type 只能是 SOURCE 或 REPORT");
        return type;
    }

    private String label(String type) {
        return "SOURCE".equals(type) ? "源码" : "报告";
    }

    private List<SubmissionVersionFile> versionFiles(Long versionId) {
        return versionFileMapper.selectList(new LambdaQueryWrapper<SubmissionVersionFile>()
                .eq(SubmissionVersionFile::getVersionId, versionId)
                .orderByAsc(SubmissionVersionFile::getSortIndex)
                .orderByAsc(SubmissionVersionFile::getId));
    }

    private Map<Long, FileObject> loadObjects(List<SubmissionVersionFile> files) {
        Map<Long, FileObject> map = new HashMap<>();
        for (SubmissionVersionFile file : files) {
            if (file.getFileId() == null || map.containsKey(file.getFileId())) {
                continue;
            }
            FileObject object = fileStorage.load(String.valueOf(file.getFileId()));
            if (object != null) {
                map.put(file.getFileId(), object);
            }
        }
        return map;
    }

    private CommonDtos.SubmissionFileVO toVo(SubmissionVersionFile file, FileObject object) {
        return new CommonDtos.SubmissionFileVO(String.valueOf(file.getId()),
                String.valueOf(file.getFileId()), file.getOriginalName(),
                file.getSizeBytes() == null ? 0 : file.getSizeBytes(), file.getSha256(),
                object == null ? null : object.getContentType(),
                file.getSortIndex() == null ? 0 : file.getSortIndex());
    }

    private SubmissionVersion findPreviousWithFiles(Long submissionId, Long excludeVersionId) {
        List<SubmissionVersion> candidates = versionMapper.selectList(
                new LambdaQueryWrapper<SubmissionVersion>()
                        .eq(SubmissionVersion::getSubmissionId, submissionId)
                        .ne(SubmissionVersion::getStatus, "DELETED")
                        .orderByDesc(SubmissionVersion::getVersion)
                        .last("limit 20"));
        for (SubmissionVersion candidate : candidates) {
            if (candidate.getId().equals(excludeVersionId)) {
                continue;
            }
            if (!versionFiles(candidate.getId()).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private void checkDeadline(Selection selection) {
        Offering offering = BizAssert.found(
                offeringMapper.selectById(selection.getOfferingId()), "开课");
        BizAssert.state(offering.getSubmitDeadline() == null
                        || Times.now().isBefore(offering.getSubmitDeadline()),
                "已过提交截止时间，不能修改提交");
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

    private boolean endsWithAny(String filename, List<String> extensions) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith("." + ext.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String invalidExtension(String type, SssProperties.SubmissionType rule) {
        return label(type) + "只支持 " + String.join(" / ", rule.getAllowedExtensions().stream()
                .map(e -> "." + e).toList());
    }

    private String humanSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return (bytes / (1024L * 1024 * 1024)) + "GB";
        }
        if (bytes >= 1024L * 1024) {
            return (bytes / (1024L * 1024)) + "MB";
        }
        return (bytes / 1024) + "KB";
    }

    /**
     * Zip Slip / 压缩炸弹 / 可执行文件校验，从落盘后的路径再扫一遍。
     *
     * <p>两阶段提交时文件早已落盘，只能按路径校验；.zip 与 .jar 是同一套容器格式，
     * 必须走同一条校验链——旧实现让 .jar 提前 return，等于给绕过校验留了后门。</p>
     */
    private void validateArchive(FileObject file) {
        Path path = BizAssert.found(fileStorage.locate(file), file.getFilename());
        try {
            ArchiveGuard.scan(path, ArchiveGuard.Limits.defaults());
        }
        catch (ArchiveGuard.Violation violation) {
            throw invalid(violation.code(), file.getFilename() + "：" + violation.message());
        }
        catch (IOException ex) {
            throw invalid("ZIP_BROKEN", file.getFilename() + "：压缩包无法解析");
        }
    }

    /**
     * 结构指纹：包内条目名排序后取 SHA-256。
     *
     * <p>它认的是「目录与文件清单」这个骨架。别人把你的代码批量改注释、改缩进、
     * 重命名变量，内容哈希全变，但骨架一模一样——这正是最需要提示教师的一种情形。</p>
     */
    private String structureKey(FileObject file) {
        Path path = fileStorage.locate(file);
        if (path == null) {
            return null;
        }
        try (InputStream in = Files.newInputStream(path);
             ZipInputStream zip = new ZipInputStream(in)) {
            List<String> names = new ArrayList<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                names.add(entry.getName());
                if (names.size() >= MAX_ENTRIES_FOR_FINGERPRINT) {
                    break;
                }
            }
            Collections.sort(names);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String name : names) {
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (IOException | NoSuchAlgorithmException ex) {
            return null;
        }
    }

    /**
     * 文件头魔数校验：把 .exe 改名成 .zip 是最省事的绕过方式，扩展名拦不住。
     *
     * <p>只做「容器类型是否对得上」的粗判：源码必须是 ZIP 家族（PK），
     * 报告必须是 PDF（%PDF）或 DOCX（PK）。更细的内容校验交给 ArchiveGuard 与正文抽取。</p>
     */
    private void sniffSignature(MultipartFile file, String type, String filename) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(4);
            if (head.length < 4) {
                throw invalid("EMPTY_FILE", filename + " 不是有效文件");
            }
            boolean zip = head[0] == 'P' && head[1] == 'K';
            boolean pdf = head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
            String lower = filename.toLowerCase(Locale.ROOT);
            boolean ok = "SOURCE".equals(type) ? zip : (pdf || (lower.endsWith(".docx") && zip));
            if (!ok) {
                throw invalid("MAGIC_MISMATCH", filename + " 的真实格式与扩展名不符：" + invalidExtension(type, ruleOf(type)));
            }
        }
        catch (IOException ex) {
            throw invalid("READ_FAILED", filename + " 读取失败");
        }
    }

    /** 上传阶段就在流里扫一遍归档：坏包在落盘之前被拒，磁盘上不留垃圾。 */
    private void validateArchiveStream(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "文件" : file.getOriginalFilename();
        try (InputStream in = file.getInputStream()) {
            ArchiveGuard.scan(in, ArchiveGuard.Limits.defaults());
        }
        catch (ArchiveGuard.Violation violation) {
            throw invalid(violation.code(), filename + "：" + violation.message());
        }
        catch (IOException ex) {
            throw invalid("ZIP_BROKEN", filename + "：压缩包无法解析");
        }
    }

    private BizException invalid(String code, String message) {
        return new BizException(ErrorCode.FILE_INVALID, message,
                Map.of("errors", List.of(Map.of("code", code, "message", message))));
    }

    public record Download(String filename, String contentType, Path path) {
    }
}
