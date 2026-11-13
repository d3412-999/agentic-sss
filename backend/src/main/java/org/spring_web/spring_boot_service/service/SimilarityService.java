package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.common.ContentFingerprint;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.SubmissionSimilarity;
import org.spring_web.spring_boot_service.entity.SubmissionVersionFile;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.mapper.FileObjectMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionSimilarityMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionVersionFileMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提交文件相似度检测。
 *
 * <p><b>它在防什么：</b>两个人交上来的源码包不是「长得像」，而是逐字节一致、
 * 或者只把变量名/注释改了、文件和目录结构原封不动。这类情况教师在答辩时很难靠肉眼发现，
 * 但系统可以确定性地指出来，让老师带着证据去问一句「这段代码为什么你和他一模一样」。</p>
 *
 * <p><b>三级判定（分数取最高的一档）：</b></p>
 * <ol>
 *   <li>{@code IDENTICAL} = 1.0：SHA-256 相同，内容完全一致；</li>
 *   <li>{@code SAME_STRUCTURE} = 0.75：包内条目名集合完全相同、内容却不同——
 *       典型是「拿别人的包，批量改注释和缩进」或者「同一套脚手架各自填代码」；</li>
 *   <li>{@code CONTENT_OVERLAP} = 0.85~1.0：归一化（去注释/去空白/统一小写）后的
 *       5-gram MinHash 相似度。抓的是「结构动了、文件名改了、注释重写了，
 *       但代码主体还是同一份」——这一档以前是盲区。</li>
 * </ol>
 *
 * <p><b>它不做什么：</b>不做语义级（换实现、换算法）的抄袭识别，也不评判谁抄谁。
 * 命中只是一条线索，不是结论；定性必须由教师人工复核，且不自动扣分。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarityService {

    private static final BigDecimal IDENTICAL = new BigDecimal("1.000");
    private static final BigDecimal SAME_STRUCTURE = new BigDecimal("0.750");

    private final SubmissionVersionFileMapper versionFileMapper;
    private final SubmissionSimilarityMapper similarityMapper;
    private final SelectionMapper selectionMapper;
    private final SubmissionMapper submissionMapper;
    private final SysUserMapper userMapper;
    private final FileObjectMapper fileObjectMapper;
    private final FileStorageService fileStorage;
    private final AuditLogService auditLogService;
    private final SssProperties properties;

    /**
     * 比对本次提交的文件与同题目下其他学生的历史文件。
     *
     * <p>在事务提交后调用：比对结果不影响提交本身是否成功，
     * 万一比对逻辑出问题，也不能把「上传成功」变成「上传失败」。</p>
     */
    @Transactional
    public void check(Selection selection, Submission submission, List<SubmissionVersionFile> mine) {
        SssProperties.Similarity config = properties.getUpload().getSubmission().getSimilarity();
        if (!config.isEnabled() || mine == null || mine.isEmpty() || selection.getTopicId() == null) {
            return;
        }

        ensureFingerprints(mine, config);

        List<Selection> others = selectionMapper.selectList(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getTopicId, selection.getTopicId())
                .ne(Selection::getId, selection.getId()));
        if (others.isEmpty()) {
            return;
        }
        Map<Long, Long> studentOfSelection = new HashMap<>();
        for (Selection other : others) {
            studentOfSelection.put(other.getId(), other.getStudentId());
        }
        List<Long> otherSelectionIds = others.stream().map(Selection::getId).toList();

        // 只与其他同学的「当前最新版本」比对：
        // 历史版本也拿去比，会让同一份作品被反复命中，教师端看到一堆重复提示，反而分不清该找谁谈。
        Map<Long, Long> latestVersionBySelection = new HashMap<>();
        List<Submission> otherSubmissions = submissionMapper.selectList(
                new LambdaQueryWrapper<Submission>()
                        .in(Submission::getSelectionId, otherSelectionIds)
                        .eq(Submission::getType, mine.get(0).getFileType()));
        for (Submission other : otherSubmissions) {
            if (other.getLatestVersionId() != null) {
                latestVersionBySelection.put(other.getSelectionId(), other.getLatestVersionId());
            }
        }
        if (latestVersionBySelection.isEmpty()) {
            return;
        }

        // 内容级比对无法用 SQL 预筛（不像 sha256 能等值匹配），只能把同题同类的候选全拉出来。
        // 量级可控：同题 100 人 × 9 个文件 = 900 条，逐个算 128 维签名比较是微秒级。
        List<SubmissionVersionFile> candidates = versionFileMapper.selectList(
                new LambdaQueryWrapper<SubmissionVersionFile>()
                        .in(SubmissionVersionFile::getSelectionId, otherSelectionIds)
                        .eq(SubmissionVersionFile::getFileType, mine.get(0).getFileType()));

        int hits = 0;
        for (SubmissionVersionFile file : mine) {
            int[] mineSig = ContentFingerprint.decode(file.getContentSig());
            Set<Long> guarded = new HashSet<>();
            for (SubmissionVersionFile other : candidates) {
                Long latestVersion = latestVersionBySelection.get(other.getSelectionId());
                if (latestVersion == null || !latestVersion.equals(other.getVersionId())) {
                    continue;
                }
                if (!guarded.add(other.getId())) {
                    continue;
                }
                Long otherStudent = studentOfSelection.get(other.getSelectionId());
                boolean sameStudent = otherStudent != null && otherStudent.equals(selection.getStudentId());
                if (sameStudent && !config.isCompareWithinSameStudent()) {
                    continue;
                }
                Hit hit = scoreOf(file, other, mineSig, config);
                if (hit == null) {
                    continue;
                }
                if (record(selection, submission, file, other, hit.score(), hit.kind())) {
                    hits++;
                }
            }
        }

        if (hits > 0) {
            log.info("提交相似度命中 {} 条, submissionId={}", hits, submission.getId());
        }
    }

    /**
     * 给本次提交的源码文件补算内容指纹。
     *
     * <p>只补不算过的，且只对源码类、未超上限的文件算——这是有意的成本控制：
     * 一份 50MB 的包解压+归一化约 1~2 秒，9 份全算会让绑定请求明显变慢。</p>
     */
    private void ensureFingerprints(List<SubmissionVersionFile> mine, SssProperties.Similarity config) {
        if (!config.isFingerprintEnabled()) {
            return;
        }
        for (SubmissionVersionFile file : mine) {
            if (!"SOURCE".equals(file.getFileType()) || file.getContentSig() != null) {
                continue;
            }
            long size = file.getSizeBytes() == null ? 0 : file.getSizeBytes();
            if (size > config.getMaxFingerprintBytes()) {
                log.info("文件超过指纹计算上限，跳过内容级比对: {} ({} bytes)", file.getOriginalName(), size);
                continue;
            }
            try {
                FileObject object = fileObjectMapper.selectById(file.getFileId());
                Path path = object == null ? null : fileStorage.locate(object);
                if (path == null) {
                    continue;
                }
                int[] sig = ContentFingerprint.ofZip(path);
                if (sig == null) {
                    continue;
                }
                file.setContentSig(ContentFingerprint.encode(sig));
                file.setUpdatedAt(Times.now());
                versionFileMapper.updateById(file);
            } catch (RuntimeException ex) {
                // 指纹算不出来只影响这一档检测，不能连带影响提交与评审
                log.warn("计算内容指纹失败，跳过: {}", file.getOriginalName(), ex);
            }
        }
    }

    /** 计算一对文件的最高相似度；未达任何一档门槛返回 null。 */
    private Hit scoreOf(SubmissionVersionFile mine, SubmissionVersionFile other,
                        int[] mineSig, SssProperties.Similarity config) {
        if (mine.getSha256() != null && mine.getSha256().equals(other.getSha256())) {
            return new Hit(IDENTICAL, "IDENTICAL");
        }
        Hit best = null;
        if (mine.getStructureKey() != null && mine.getStructureKey().equals(other.getStructureKey())
                && SAME_STRUCTURE.doubleValue() >= config.getThreshold()) {
            best = new Hit(SAME_STRUCTURE, "SAME_STRUCTURE");
        }
        if (mineSig != null) {
            int[] otherSig = ContentFingerprint.decode(other.getContentSig());
            double jaccard = ContentFingerprint.similarity(mineSig, otherSig);
            if (jaccard >= config.getContentThreshold()
                    && (best == null || jaccard > best.score().doubleValue())) {
                best = new Hit(BigDecimal.valueOf(Math.round(jaccard * 1000) / 1000.0), "CONTENT_OVERLAP");
            }
        }
        return best;
    }

    /** 教师/学生查看某次提交命中的相似记录，按相似度倒序。 */
    public List<CommonDtos.SimilarityVO> list(Long submissionId) {
        List<SubmissionSimilarity> hits = similarityMapper.selectList(
                new LambdaQueryWrapper<SubmissionSimilarity>()
                        .eq(SubmissionSimilarity::getSubmissionId, submissionId)
                        .orderByDesc(SubmissionSimilarity::getScore));
        if (hits.isEmpty()) {
            return List.of();
        }
        // 对方学号/姓名：教师看到的是「和谁撞了」，只给 selectionId 没法处理
        Set<Long> selectionIds = new HashSet<>();
        hits.forEach(h -> selectionIds.add(h.getOtherSelectionId()));
        Map<Long, Long> studentOfSelection = new HashMap<>();
        selectionMapper.selectBatchIds(selectionIds)
                .forEach(s -> studentOfSelection.put(s.getId(), s.getStudentId()));
        Map<Long, SysUser> users = new HashMap<>();
        if (!studentOfSelection.isEmpty()) {
            userMapper.selectBatchIds(new HashSet<>(studentOfSelection.values()))
                    .forEach(u -> users.put(u.getId(), u));
        }

        return hits.stream().map(hit -> {
            SysUser other = users.get(studentOfSelection.get(hit.getOtherSelectionId()));
            return new CommonDtos.SimilarityVO(
                    String.valueOf(hit.getId()),
                    String.valueOf(hit.getSubmissionId()),
                    String.valueOf(hit.getSelectionId()),
                    String.valueOf(hit.getOtherSelectionId()),
                    other == null ? null : (other.getStudentNo() != null ? other.getStudentNo() : other.getUsername()),
                    other == null ? null : other.getName(),
                    String.valueOf(hit.getVersionFileId()),
                    String.valueOf(hit.getOtherVersionFileId()),
                    hit.getOriginalName(),
                    hit.getOtherOriginalName(),
                    hit.getScore() == null ? 0 : hit.getScore().doubleValue(),
                    hit.getKind(),
                    Times.offset(hit.getCreatedAt()));
        }).toList();
    }

    /** 写入命中记录；同一对文件重复检测时静默跳过。返回是否新增。 */
    private boolean record(Selection selection, Submission submission, SubmissionVersionFile mine,
                           SubmissionVersionFile other, BigDecimal score, String kind) {
        Long exists = similarityMapper.selectCount(new LambdaQueryWrapper<SubmissionSimilarity>()
                .eq(SubmissionSimilarity::getVersionFileId, mine.getId())
                .eq(SubmissionSimilarity::getOtherVersionFileId, other.getId()));
        if (exists != null && exists > 0) {
            return false;
        }

        SubmissionSimilarity hit = new SubmissionSimilarity();
        hit.setTopicId(selection.getTopicId());
        hit.setFileType(mine.getFileType());
        hit.setSelectionId(selection.getId());
        hit.setOtherSelectionId(other.getSelectionId());
        hit.setSubmissionId(submission.getId());
        hit.setOtherSubmissionId(other.getSubmissionId());
        hit.setVersionFileId(mine.getId());
        hit.setOtherVersionFileId(other.getId());
        hit.setOriginalName(mine.getOriginalName());
        hit.setOtherOriginalName(other.getOriginalName());
        hit.setScore(score);
        hit.setKind(kind);
        hit.setCreatedAt(Times.now());
        try {
            similarityMapper.insert(hit);
        } catch (RuntimeException ex) {
            // 唯一索引冲突说明另一次检测已经写过同一对，属于正常并发，不当作错误
            log.debug("相似度记录重复，已忽略，pair={}-{}", mine.getId(), other.getId());
            return false;
        }

        auditLogService.record("SUBMISSION_SIMILARITY", "SUBMISSION", submission.getId(), null,
                Map.of("otherSelectionId", String.valueOf(other.getSelectionId()),
                        "score", score.toPlainString(),
                        "kind", kind,
                        "file", mine.getOriginalName(),
                        "otherFile", other.getOriginalName()),
                "提交文件与同题目其他学生的文件相似度达到阈值（" + score.toPlainString() + "，" + kind + "）");
        return true;
    }

    /** 一次命中：分数 + 判据来源。 */
    private record Hit(BigDecimal score, String kind) {
    }
}
