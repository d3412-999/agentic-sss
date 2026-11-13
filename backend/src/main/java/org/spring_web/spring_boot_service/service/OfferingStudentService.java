package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.OfferingDtos;
import org.spring_web.spring_boot_service.entity.Grade;
import org.spring_web.spring_boot_service.entity.OfferingStudent;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.Submission;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.mapper.GradeMapper;
import org.spring_web.spring_boot_service.mapper.OfferingStudentMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SubmissionMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.spring_web.spring_boot_service.mapper.TopicExtraMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 开课学生名单。账号按学号全局复用：同一学生在多个开课里只有一条 sys_user，
 * 只新增 offering_student 关系，避免重复账号。
 *
 * <p><b>账号密码策略</b>：学生账号统一使用 {@code sss.student.default-password}
 * （默认与教师初始密码一致），建号时置 {@code must_change_password=1} 强制首次改密。
 * 系统不再生成、也不再回传「一次性初始密码」——教师不需要记录密码，名单里也不展示密码。</p>
 *
 * <p><b>一对多</b>：一个教学班对应多条 offering_student，一个学生可以同时存在于多个教学班；
 * 移出名单只删除本开课的关联行，不改动全局账号，也不影响其他教学班。</p>
 */
@Service
@RequiredArgsConstructor
public class OfferingStudentService {

    /** 加入结果：新建账号 / 复用已有账号 / 本来就在本班。 */
    public enum EnrollStatus {
        CREATED, LINKED, ALREADY_IN_ROSTER
    }

    /** {@code existingName} 是库里已有的姓名，用于提示「姓名与已有账号不一致」。 */
    public record Enrollment(
            EnrollStatus status, SysUser user, OfferingStudent relation, String existingName) {
    }

    private final SysUserMapper userMapper;
    private final OfferingStudentMapper rosterMapper;
    private final SelectionMapper selectionMapper;
    private final TopicExtraMapper topicExtraMapper;
    private final SubmissionMapper submissionMapper;
    private final GradeMapper gradeMapper;
    private final OfferingService offeringService;
    private final PasswordEncoder passwordEncoder;
    private final SssProperties properties;
    private final AuditLogService auditLog;

    public PageResult<OfferingDtos.StudentVO> list(
            String offeringId, String keyword, String status, long page, long size) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, size), 200);

        LambdaQueryWrapper<OfferingStudent> wrapper = new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, oid)
                .eq(StringUtils.hasText(status), OfferingStudent::getStatus, status);
        // 关键字必须在 SQL 里过滤：先分页再在内存里筛会让 total 与当前页数据对不上，
        // 出现「共 40 人但搜出来 2 条」这种明显错误。
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.strip();
            List<Long> matched = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .and(w -> w.like(SysUser::getName, kw).or().like(SysUser::getStudentNo, kw)))
                    .stream().map(SysUser::getId).toList();
            if (matched.isEmpty()) {
                return PageResult.of(List.of(), 0, safePage, safeSize);
            }
            wrapper.in(OfferingStudent::getStudentId, matched);
        }

        Page<OfferingStudent> result = rosterMapper.selectPage(Page.of(safePage, safeSize),
                wrapper.orderByDesc(OfferingStudent::getId));
        List<Long> ids = result.getRecords().stream().map(OfferingStudent::getStudentId).toList();
        Map<Long, SysUser> users = ids.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(SysUser::getId, Function.identity()));
        List<OfferingDtos.StudentVO> list = result.getRecords().stream()
                .map(r -> toVo(r, users.get(r.getStudentId())))
                .filter(java.util.Objects::nonNull)
                .toList();
        return PageResult.of(list, result.getTotal(), safePage, safeSize);
    }

    @Transactional(rollbackFor = Exception.class)
    public OfferingDtos.StudentVO create(String offeringId, OfferingDtos.StudentCreateRequest request) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        String studentNo = normalizeStudentNo(request.studentNo());
        String name = normalizeName(request.name());
        Enrollment enrollment = enroll(oid, studentNo, name, trimToNull(request.className()),
                trimToNull(request.email()));
        return toVo(enrollment.relation(), enrollment.user());
    }

    /**
     * 把一位学生加入教学班：账号不存在则建号，存在则只建关联，已在本班则原样返回。
     *
     * <p>刻意不加 {@code @Transactional}：批量导入需要「一行一个事务」，
     * 由调用方用事务模板包住，避免 500 行挤进同一个长事务。</p>
     */
    public Enrollment enroll(Long offeringId, String studentNo, String name, String className, String email) {
        return enroll(offeringId, studentNo, name, className, email, null);
    }

    /**
     * 同 {@link #enroll}，但允许传入预计算的密码哈希。
     *
     * <p>批量导入几百个学生时，BCrypt(cost=10) 单次约 60ms，逐行同步算会把 500 行拖到 30 秒；
     * 导入服务用 {@link #hashInitialPasswords} 提前把整批哈希算好（同一密码只算一次、多核并行），
     * 这里只负责把算好的值写进新账号。</p>
     */
    public Enrollment enroll(Long offeringId, String studentNo, String name, String className, String email,
            String presetPasswordHash) {
        SysUser user = findUserByAccount(studentNo);
        boolean created = false;
        String existingName = null;

        if (user == null) {
            user = new SysUser();
            user.setUsername(studentNo);
            user.setPasswordHash(presetPasswordHash != null ? presetPasswordHash : passwordEncoder.encode(initialPassword(studentNo)));
            user.setName(name);
            user.setRole("STUDENT");
            user.setStudentNo(studentNo);
            user.setClassName(className);
            user.setEmail(email);
            user.setStatus("ACTIVE");
            user.setMustChangePassword(1);
            user.setTokenVersion(0L);
            user.setCreatedAt(Times.now());
            user.setUpdatedAt(Times.now());
            userMapper.insert(user);
            created = true;
        } else {
            BizAssert.rule("STUDENT".equals(user.getRole()),
                    "学号 " + studentNo + " 已被教师或管理员账号占用，不能作为学生加入");
            existingName = user.getName();
            if (!StringUtils.hasText(user.getStudentNo())) {
                // 历史数据里可能有「有账号没学号」的学生，这里顺手补齐，之后按学号就能命中
                SysUser patch = new SysUser();
                patch.setId(user.getId());
                patch.setStudentNo(studentNo);
                patch.setUpdatedAt(Times.now());
                userMapper.updateById(patch);
                user.setStudentNo(studentNo);
            }
        }

        OfferingStudent relation = findRelation(offeringId, user.getId());
        if (relation != null) {
            return new Enrollment(EnrollStatus.ALREADY_IN_ROSTER, user, relation, existingName);
        }

        relation = new OfferingStudent();
        relation.setOfferingId(offeringId);
        relation.setStudentId(user.getId());
        relation.setStatus("ACTIVE");
        relation.setJoinedAt(Times.now());
        relation.setCreatedAt(Times.now());
        relation.setUpdatedAt(Times.now());
        rosterMapper.insert(relation);
        auditLog.record(created ? "STUDENT_ADD" : "STUDENT_LINK", "OFFERING", offeringId, null,
                Map.of("studentNo", studentNo, "created", created),
                created ? "新增学生账号并加入教学班" : "复用已有账号加入教学班");
        return new Enrollment(created ? EnrollStatus.CREATED : EnrollStatus.LINKED, user, relation, existingName);
    }

    /**
     * 教师重置密码：重置为统一的默认密码并强制改密，不再生成 / 回传一次性密码。
     * 递增 tokenVersion 让该生已登录的会话立即失效。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(String offeringId, String studentId) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        Long uid = BizAssert.id(studentId, "studentId");
        offeringService.ownedOffering(offeringId);
        assertInRoster(oid, uid);
        SysUser user = BizAssert.found(userMapper.selectById(uid), "学生");
        SysUser patch = new SysUser();
        patch.setId(uid);
        // 重置回「初始密码」，与建号保持同一套语义（默认即学号）
        patch.setPasswordHash(passwordEncoder.encode(initialPassword(
                StringUtils.hasText(user.getStudentNo()) ? user.getStudentNo() : user.getUsername())));
        patch.setMustChangePassword(1);
        patch.setUpdatedAt(Times.now());
        userMapper.updateById(patch);
        userMapper.bumpTokenVersion(uid);
        auditLog.record("STUDENT_RESET_PASSWORD", "USER", uid, null,
                Map.of("studentNo", user.getStudentNo() == null ? "" : user.getStudentNo()),
                "教师将学生密码重置为统一默认密码");
    }

    /**
     * 禁用只影响本开课名单关系（学生仍可登录并参与其他开课），
     * 因为 sys_user 是跨开课共享的，直接禁用账号会误伤别的教学班。
     */
    @Transactional(rollbackFor = Exception.class)
    public OfferingDtos.StudentVO patchStatus(
            String offeringId, String studentId, OfferingDtos.StudentStatusPatch request) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        Long uid = BizAssert.id(studentId, "studentId");
        offeringService.ownedOffering(offeringId);
        BizAssert.param("ACTIVE".equals(request.status()) || "DISABLED".equals(request.status()),
                "status 只能是 ACTIVE 或 DISABLED");
        OfferingStudent relation = findRelation(oid, uid);
        BizAssert.found(relation, "名单关系");
        relation.setStatus(request.status());
        relation.setUpdatedAt(Times.now());
        rosterMapper.updateById(relation);
        return toVo(relation, userMapper.selectById(uid));
    }

    /**
     * 移出名单：只解除本教学班的关联，不删除全局账号。
     *
     * <p>已有提交物或成绩时拒绝移除，避免成绩 / 答辩数据失去归属；
     * 同时取消该生在本开课的有效选题，释放题目名额。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String offeringId, String studentId) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        Long uid = BizAssert.id(studentId, "studentId");
        offeringService.ownedOffering(offeringId);
        OfferingStudent relation = findRelation(oid, uid);
        BizAssert.found(relation, "名单关系");

        List<Selection> selections = selectionMapper.selectList(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getOfferingId, oid).eq(Selection::getStudentId, uid));
        if (!selections.isEmpty()) {
            List<Long> ids = selections.stream().map(Selection::getId).toList();
            Long submissions = submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                    .in(Submission::getSelectionId, ids));
            Long grades = gradeMapper.selectCount(new LambdaQueryWrapper<Grade>()
                    .eq(Grade::getOfferingId, oid).eq(Grade::getStudentId, uid));
            BizAssert.rule((submissions == null || submissions == 0)
                            && (grades == null || grades == 0),
                    "该生已有提交物或成绩，不能从名单移除");
        }

        // 移出名单必须同步取消该生在本开课的有效选题：否则记录还是 SELECTED，
        // 题目名额被永久占用（别人选不上），而该生已不在名单里也无法自己退选。
        List<Long> canceled = new ArrayList<>();
        for (Selection selection : selections) {
            if ("CANCELED".equals(selection.getStatus())) {
                continue;
            }
            selection.setStatus("CANCELED");
            selection.setCanceledAt(Times.now());
            selection.setUpdatedAt(Times.now());
            if (selectionMapper.updateById(selection) == 0) {
                // 乐观锁冲突：并发下该选题状态已变，整体回滚让教师刷新后重试
                throw new BizException(ErrorCode.CONFLICT, "选题状态已变更，请刷新后重试");
            }
            topicExtraMapper.releaseOne(selection.getTopicId());
            canceled.add(selection.getId());
        }

        rosterMapper.deleteById(relation.getId());
        auditLog.record("STUDENT_REMOVE", "OFFERING", oid, null,
                Map.of("canceledSelectionCount", canceled.size()), null);
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                            */
    /* ------------------------------------------------------------------ */

    private OfferingStudent findRelation(Long offeringId, Long studentId) {
        return rosterMapper.selectOne(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, offeringId)
                .eq(OfferingStudent::getStudentId, studentId)
                .last("limit 1"));
    }

    /** 按「学号」找账号；历史数据可能只填了 username，两个字段都查，避免建出重复账号。 */
    private SysUser findUserByAccount(String studentNo) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStudentNo, studentNo)
                .or()
                .eq(SysUser::getUsername, studentNo)
                .last("limit 1"));
    }

    private void assertInRoster(Long offeringId, Long studentId) {
        Long count = rosterMapper.selectCount(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, offeringId)
                .eq(OfferingStudent::getStudentId, studentId));
        BizAssert.forbidden(count != null && count > 0, "名单");
    }

    public OfferingDtos.StudentVO toVo(OfferingStudent relation, SysUser user) {
        if (user == null) {
            return null;
        }
        List<Selection> selections = selectionMapper.selectList(new LambdaQueryWrapper<Selection>()
                .eq(Selection::getOfferingId, relation.getOfferingId())
                .eq(Selection::getStudentId, user.getId())
                .ne(Selection::getStatus, "CANCELED"));
        boolean submitted = false;
        if (!selections.isEmpty()) {
            Long count = submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                    .in(Submission::getSelectionId,
                            selections.stream().map(Selection::getId).toList()));
            submitted = count != null && count > 0;
        }
        Grade grade = gradeMapper.selectOne(new LambdaQueryWrapper<Grade>()
                .eq(Grade::getOfferingId, relation.getOfferingId())
                .eq(Grade::getStudentId, user.getId()).last("limit 1"));
        return new OfferingDtos.StudentVO(String.valueOf(user.getId()), user.getStudentNo(),
                user.getName(), user.getClassName(), user.getEmail(), relation.getStatus(),
                selections.size(), submitted,
                grade == null ? null : grade.getStatus(),
                grade == null ? null : grade.getFinalScore(),
                Times.offset(user.getLastLoginAt()));
    }

    /** 批量导入用：按学号算初始密码的 BCrypt（密码=学号时每人不同，只能逐个算）。 */
    public String hashInitialPassword(String studentNo) {
        return passwordEncoder.encode(initialPassword(studentNo));
    }

    /**
     * 批量导入用：一次算好整批学号的初始密码哈希。
     *
     * <p>「密码=学号」时每人的明文都不同，没法像统一密码那样只算一次；这里用并行流把
     * BCrypt 摊到多核（500 行从 ~30s 降到 ~3s）。同一批里若出现重复学号，只算一次。</p>
     *
     * @return 学号 → BCrypt 哈希；调用方按 {@code row.studentNo()} 取
     */
    public Map<String, String> hashInitialPasswords(java.util.Collection<String> studentNos) {
        if (studentNos == null || studentNos.isEmpty()) {
            return Map.of();
        }
        Map<String, String> passwordByNo = new LinkedHashMap<>();
        for (String studentNo : studentNos) {
            passwordByNo.putIfAbsent(studentNo, initialPassword(studentNo));
        }
        Map<String, String> hashByPassword = passwordByNo.values().stream()
                .distinct()
                .parallel()
                .collect(Collectors.toConcurrentMap(Function.identity(), passwordEncoder::encode));
        Map<String, String> hashByNo = new LinkedHashMap<>();
        passwordByNo.forEach((studentNo, password) -> hashByNo.put(studentNo, hashByPassword.get(password)));
        return hashByNo;
    }

    /**
     * 学生初始密码。
     *
     * <p>默认「密码 = 学号」（{@code sss.student.default-password-mode=STUDENT_NO}），
     * 与校园惯例一致，教师不必再逐个告知密码；如需回到所有新账号统一密码的旧行为，
     * 把该配置改成 {@code FIXED} 并设置 {@code sss.student.default-password}。</p>
     *
     * <p>无论哪种模式，建号都会置 {@code must_change_password=1}，首次登录强制改密，
     * 所以「密码=学号」只是临时口令，不会被长期使用。</p>
     */
    public String initialPassword(String studentNo) {
        if (properties.getStudent().getDefaultPasswordMode() == SssProperties.PasswordMode.FIXED) {
            String password = properties.getStudent().getDefaultPassword();
            BizAssert.param(StringUtils.hasText(password),
                    "未配置学生默认密码，请设置 sss.student.default-password");
            return password;
        }
        BizAssert.param(StringUtils.hasText(studentNo), "学号为空，无法生成初始密码");
        return studentNo;
    }

    static String normalizeStudentNo(String raw) {
        BizAssert.param(raw != null && !raw.isBlank(), "学号不能为空");
        String value = raw.strip();
        BizAssert.param(StudentRosterParser.isValidStudentNo(value),
                "学号「" + value + "」格式不合法：只能包含字母、数字、下划线或短横线（2~32 位）");
        return value;
    }

    static String normalizeName(String raw) {
        BizAssert.param(raw != null && !raw.isBlank(), "姓名不能为空");
        String value = raw.strip();
        BizAssert.param(value.length() <= 50, "姓名不能超过 50 个字符");
        return value;
    }

    static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        return value.isEmpty() ? null : value;
    }
}
