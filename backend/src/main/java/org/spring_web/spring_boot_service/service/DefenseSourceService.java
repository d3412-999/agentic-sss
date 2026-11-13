package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.common.ArchiveGuard;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.SourceArchiveReader;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.DefenseDtos;
import org.spring_web.spring_boot_service.entity.DefenseSession;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.SubmissionVersion;
import org.spring_web.spring_boot_service.entity.SubmissionVersionFile;
import org.spring_web.spring_boot_service.mapper.DefenseSessionMapper;
import org.spring_web.spring_boot_service.mapper.FileObjectMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionVersionFileMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionVersionMapper;
import org.spring_web.spring_boot_service.security.ClientIpResolver;
import org.spring_web.spring_boot_service.security.LoginUser;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 答辩中「只读查看自己提交的源码」。
 *
 * <p>为什么不能直接复用文件下载接口：下载会把原始 zip 交给浏览器，解压后既可复制又可改，
 * 而且下载动作留下的 URL 还能被转发。这里改成「服务端解压 → 只回纯文本」，
 * 浏览器拿到的是一段不可下载的字符串，权限链每一环都能在服务端复核。</p>
 *
 * <p>权限链（任一环失败即 403，顺序固定）：</p>
 * <ol>
 *   <li>当前 token 的角色必须是 STUDENT —— 教师看源码会看到「学生没学会怎么改的代码」，
 *       不仅无必要，还会让「只有本人可见」这句话在审计里失真；</li>
 *   <li>会话归属：{@code defense_session.student_id == 当前用户}；</li>
 *   <li>会话状态必须是 IN_PROGRESS —— 开始前看不到（防提前抄答案），结束后立刻失效
 *       （防事后导出）；</li>
 *   <li>会话 → selection → submission(SOURCE) → 最新版本 → 版本内 SOURCE 文件；</li>
 *   <li>文件扩展名白名单：只认 zip / jar，库里就算被写进别的类型也不解压。</li>
 * </ol>
 *
 * <p>三个「防止把只读做成可批量导出」的限流/绑定：5 分钟缓存（同一版本不重复解压）、
 * 每学生每分钟最多 {@code coldRequestsPerMinute} 次冷解压、首次访问 IP 绑定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefenseSourceService {

    /** 只解压这两种容器；与上传侧 {@code sss.upload.submission.source.allowed-extensions} 同口径。 */
    private static final List<String> SOURCE_EXTENSIONS = List.of(".zip", ".jar");
    /** IP 绑定的保留时长：超过就允许重新绑定，避免学生换一次网络就永久不可用。 */
    private static final Duration IP_BINDING_TTL = Duration.ofMinutes(30);
    /** 单个请求体内最多保留多少个源码包，防止一次开 9 个百 MB 包把堆占满。 */
    private static final int MAX_ARCHIVES = 9;

    private final DefenseSessionMapper sessionMapper;
    private final SelectionMapper selectionMapper;
    private final SubmissionMapper submissionMapper;
    private final SubmissionVersionMapper versionMapper;
    private final SubmissionVersionFileMapper versionFileMapper;
    private final FileObjectMapper fileObjectMapper;
    private final FileStorageService fileStorage;
    private final AuditLogService auditLogService;
    private final JsonCodec json;
    private final SssProperties properties;
    private final ClientIpResolver clientIpResolver;

    /** 解压结果缓存：key = sessionId + ':' + versionId。 */
    private final Map<String, CachedSource> cache = new ConcurrentHashMap<>();
    /** 会话 → (IP, 绑定时间)。 */
    private final Map<Long, IpBinding> ipBindings = new ConcurrentHashMap<>();
    /** 学生 → 最近冷解压时间戳（毫秒）。 */
    private final Map<Long, Deque<Long>> coldRequests = new ConcurrentHashMap<>();

    /**
     * 读取当前答辩会话的源码。
     *
     * @param sessionId 答辩会话 ID（字符串形式，与前端路由保持一致）
     */
    public DefenseDtos.SourceCodeVO sourceCode(String sessionId) {
        LoginUser current = SecurityUtils.required();
        DefenseSession session = authorize(sessionId, current);
        // IP 绑定必须在读缓存之前：否则缓存热了之后，拿着转发来的 token 从别的地址
        // 也能直接命中缓存拿到内容，绑定就形同虚设。
        checkIpBinding(session.getId());
        SourcePackage source = resolvePackage(session);

        String cacheKey = session.getId() + ":" + source.version().getId();
        DefenseDtos.SourceCodeVO cached = readCache(cacheKey);
        if (cached != null) {
            audit(session, source, cached, true);
            return cached;
        }

        checkColdRate(current.id());

        DefenseDtos.SourceCodeVO vo = decompress(source);
        writeCache(cacheKey, vo);
        audit(session, source, vo, false);
        return vo;
    }

    // ---------------------------------------------------------------- 权限链

    /** 五步权限链；返回值是已通过校验的会话，后续步骤只信任它。 */
    private DefenseSession authorize(String sessionId, LoginUser current) {
        BizAssert.forbidden(current.isStudent(), "源码只能由答辩者本人查看");
        Long id = BizAssert.id(sessionId, "sessionId");
        DefenseSession session = BizAssert.found(sessionMapper.selectById(id), "答辩会话");
        BizAssert.forbidden(current.id().equals(session.getStudentId()), "只能查看自己答辩会话的源码");
        // 结束/未开始都拒绝：结束后仍可看等于给了「离线导出」的机会
        BizAssert.forbidden("IN_PROGRESS".equals(session.getStatus()),
                "答辩不在进行中，源码仅答辩期间可查看");
        return session;
    }

    /** 会话 → 选题 → 源码提交物 → 最新版本 → 版本内 zip/jar 清单。 */
    private SourcePackage resolvePackage(DefenseSession session) {
        Selection selection = firstSelection(session);
        Submission submission = submissionMapper.selectOne(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getSelectionId, selection.getId())
                .eq(Submission::getType, "SOURCE")
                .last("limit 1"));
        BizAssert.found(submission, "源码提交物");

        SubmissionVersion version = latestVersion(submission);
        BizAssert.found(version, "源码版本");

        List<SubmissionVersionFile> rows = versionFileMapper.selectList(
                new LambdaQueryWrapper<SubmissionVersionFile>()
                        .eq(SubmissionVersionFile::getVersionId, version.getId())
                        .eq(SubmissionVersionFile::getFileType, "SOURCE")
                        .orderByAsc(SubmissionVersionFile::getSortIndex)
                        .orderByAsc(SubmissionVersionFile::getId));
        List<SubmissionVersionFile> archives = new ArrayList<>();
        for (SubmissionVersionFile row : rows) {
            if (SOURCE_EXTENSIONS.stream().anyMatch(extension -> matchesExtension(row, extension))) {
                archives.add(row);
            }
        }
        BizAssert.rule(!archives.isEmpty(), "暂无可查看的源码包，请确认已提交 .zip / .jar");
        BizAssert.rule(archives.size() <= MAX_ARCHIVES, "源码包数量超出上限，请联系教师");
        return new SourcePackage(version, archives);
    }

    private Selection firstSelection(DefenseSession session) {
        for (String raw : json.stringList(session.getSelectionIds())) {
            Long id = BizAssert.parseIdOrNull(raw);
            Selection selection = id == null ? null : selectionMapper.selectById(id);
            if (selection != null) {
                return selection;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE, "答辩会话未关联选题，无法定位源码");
    }

    /**
     * 最新版本：优先用 {@code submission.latest_version_id}，为空时回退到「版本号最大的一条」。
     *
     * <p>V11 之前这个字段有历史脏数据（停在 0），只 Trust 一个字段会让部分老提交直接看不到源码。</p>
     */
    private SubmissionVersion latestVersion(Submission submission) {
        if (submission.getLatestVersionId() != null) {
            SubmissionVersion version = versionMapper.selectById(submission.getLatestVersionId());
            if (version != null) {
                return version;
            }
        }
        List<SubmissionVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<SubmissionVersion>()
                        .eq(SubmissionVersion::getSubmissionId, submission.getId())
                        .orderByDesc(SubmissionVersion::getVersion)
                        .last("limit 1"));
        return versions.isEmpty() ? null : versions.get(0);
    }

    private boolean matchesExtension(SubmissionVersionFile row, String extension) {
        String name = row.getOriginalName() == null ? "" : row.getOriginalName().toLowerCase();
        return name.endsWith(extension);
    }

    // ---------------------------------------------------------------- 解压

    /**
     * 逐个包流式解压，多个包按「包名/路径」合并成一棵树。
     *
     * <p>预算由所有包共享：第 2 个包拿到的是「总上限 - 已用」，而不是每个包都重新给一份
     * 500 文件 / 200MB 的额度——否则 9 个包就是 9 倍开销。</p>
     */
    private DefenseDtos.SourceCodeVO decompress(SourcePackage source) {
        SssProperties.Source limits = properties.getDefense().getSource();
        long deadline = System.nanoTime() + limits.getTimeoutSeconds() * 1_000_000_000L;

        List<DefenseDtos.SourceFileVO> files = new ArrayList<>();
        int skipped = 0;
        long totalBytes = 0L;
        boolean truncated = false;
        int archiveCount = 0;
        boolean multi = source.archives().size() > 1;

        for (SubmissionVersionFile row : source.archives()) {
            long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
            int remainFiles = limits.getMaxFiles() - files.size();
            long remainBytes = limits.getMaxTotalBytes() - totalBytes;
            if (remainMs <= 0 || remainFiles <= 0 || remainBytes <= 0) {
                truncated = true;
                break;
            }

            FileObject object = row.getFileId() == null ? null : fileObjectMapper.selectById(row.getFileId());
            Path path = object == null ? null : fileStorage.locate(object);
            if (path == null) {
                // 记录在、磁盘没了：跳过这个包但继续给其它包，不因为一个坏文件整场看不了
                log.warn("答辩源码文件缺失，session versionId={}, fileId={}",
                        source.version().getId(), row.getFileId());
                skipped++;
                continue;
            }

            String prefix = multi ? baseName(row.getOriginalName()) : "";
            SourceArchiveReader.Limits archiveLimits = new SourceArchiveReader.Limits(
                    remainFiles, limits.getMaxFileBytes(), remainBytes, remainMs);
            try {
                SourceArchiveReader.Result result = SourceArchiveReader.read(path, prefix, archiveLimits);
                archiveCount++;
                skipped += result.skippedFiles();
                totalBytes += result.totalSize();
                truncated = truncated || result.truncated();
                for (SourceArchiveReader.Entry entry : result.files()) {
                    files.add(new DefenseDtos.SourceFileVO(entry.path(), entry.content(),
                            entry.size(), entry.language(), entry.truncated()));
                }
            } catch (ArchiveGuard.Violation violation) {
                // 细节只进日志：对外统一一句「解析失败」，避免把包内结构当错误信息泄露出去
                log.warn("答辩源码解压被拒绝，versionId={}, fileId={}, code={}, reason={}",
                        source.version().getId(), row.getFileId(), violation.code(), violation.message());
                throw new BizException(ErrorCode.BUSINESS_RULE,
                        "源码解析失败：压缩包可能已损坏，或包含不允许的文件");
            } catch (IOException ex) {
                log.warn("答辩源码读取失败，versionId={}, fileId={}", source.version().getId(),
                        row.getFileId(), ex);
                throw new BizException(ErrorCode.BUSINESS_RULE, "源码解析失败，请稍后重试或联系教师");
            }
        }

        List<DefenseDtos.SourceFileVO> sorted = files.stream()
                .sorted(Comparator.comparing(DefenseDtos.SourceFileVO::path))
                .toList();
        return new DefenseDtos.SourceCodeVO(sorted, sorted.size(), totalBytes, truncated, skipped,
                Math.max(archiveCount, 1), "v" + source.version().getVersion(), Times.offset(Times.now()));
    }

    /** 去掉扩展名作为包的展示前缀；去掉后为空则退回 "source"。 */
    private String baseName(String filename) {
        String name = filename == null || filename.isBlank() ? "source" : filename.trim();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isBlank() ? "source" : base;
    }

    // ---------------------------------------------------------------- 缓存 / 限流 / IP 绑定

    private DefenseDtos.SourceCodeVO readCache(String key) {
        CachedSource entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt().isBefore(Times.now())) {
            cache.remove(key);
            return null;
        }
        return entry.value();
    }

    private void writeCache(String key, DefenseDtos.SourceCodeVO value) {
        SssProperties.Source limits = properties.getDefense().getSource();
        // 大结果不缓存：占着内存换来的只是「少解压一次」，不划算
        if (value.totalSize() > limits.getCacheMaxEntryBytes()) {
            return;
        }
        LocalDateTime now = Times.now();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (cache.size() >= limits.getCacheMaxEntries()) {
            cache.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                    .map(Map.Entry::getKey)
                    .ifPresent(cache::remove);
        }
        cache.put(key, new CachedSource(value, now.plus(limits.getCacheTtl())));
    }

    /** 冷解压限流：命中缓存不计，避免「刷新一下源码」把 CPU 打满。 */
    private void checkColdRate(Long studentId) {
        int allowed = properties.getDefense().getSource().getColdRequestsPerMinute();
        if (allowed <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Deque<Long> hits = coldRequests.computeIfAbsent(studentId, key -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() > 60_000L) {
                hits.pollFirst();
            }
            BizAssert.check(hits.size() < allowed, ErrorCode.TOO_MANY_REQUESTS,
                    "源码刷新过于频繁，请稍后再试");
            hits.addLast(now);
        }
    }

    /**
     * IP 绑定：同一会话首次访问的 IP 记下来，换 IP 再来就拒绝。
     *
     * <p>目的是降低「把链接连同 token 一起转发给同学帮忙看」的风险；绑定 30 分钟过期，
     * 学生换网络（宿舍 → 教室）只需联系教师重新进入，不会永久锁死。</p>
     */
    private void checkIpBinding(Long sessionId) {
        if (!properties.getDefense().getSource().isBindIp()) {
            return;
        }
        String ip = currentIp();
        if (ip == null || ip.isBlank()) {
            return;
        }
        LocalDateTime now = Times.now();
        IpBinding binding = ipBindings.compute(sessionId, (key, previous) -> {
            if (previous == null || previous.boundAt().plus(IP_BINDING_TTL).isBefore(now)) {
                return new IpBinding(ip, now);
            }
            return previous;
        });
        if (!Objects.equals(binding.ip(), ip)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "该答辩会话已绑定其他网络地址，源码查看被拒绝；如确实更换了网络，请联系监考教师");
        }
    }

    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return clientIpResolver.resolve(request);
        }
        return null;
    }

    // ---------------------------------------------------------------- 审计

    /** 每次请求都留痕（含缓存命中）：审计要回答「谁在什么时候看了哪一版源码」。 */
    private void audit(DefenseSession session, SourcePackage source,
                       DefenseDtos.SourceCodeVO vo, boolean cached) {
        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("versionId", String.valueOf(source.version().getId()));
            after.put("version", source.version().getVersion());
            after.put("files", vo.totalFiles());
            after.put("bytes", vo.totalSize());
            after.put("cached", cached);
            auditLogService.record("DEFENSE_SOURCE_VIEW", "DEFENSE_SESSION", session.getId(),
                    null, after, "答辩期间查看本人源码");
        } catch (RuntimeException ex) {
            // 审计写失败不该让学生看不了源码；记日志供后续补偿核查
            log.warn("写源码查看审计失败，sessionId={}", session.getId(), ex);
        }
    }

    /** 供运维排查：当前缓存占用情况（不在接口里暴露）。 */
    public Map<String, Object> cacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("entries", cache.size());
        stats.put("boundIps", ipBindings.size());
        return stats;
    }

    // ---------------------------------------------------------------- 内部结构

    private record CachedSource(DefenseDtos.SourceCodeVO value, LocalDateTime expiresAt) {
    }

    private record IpBinding(String ip, LocalDateTime boundAt) {
    }

    private record SourcePackage(SubmissionVersion version, List<SubmissionVersionFile> archives) {
    }
}
