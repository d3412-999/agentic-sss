package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Times;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 开课学生名单。账号按学号全局复用：同一学生在多个开课里只有一条 sys_user，
 * 只新增 offering_student 关系，避免重复账号。
 */
@Service
@RequiredArgsConstructor
public class OfferingStudentService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SysUserMapper userMapper;
    private final OfferingStudentMapper rosterMapper;
    private final SelectionMapper selectionMapper;
    private final SubmissionMapper submissionMapper;
    private final GradeMapper gradeMapper;
    private final OfferingService offeringService;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLog;

    public PageResult<OfferingDtos.StudentVO> list(
            String offeringId, String keyword, String status, long page, long size) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);
        Page<OfferingStudent> result = rosterMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<OfferingStudent>()
                        .eq(OfferingStudent::getOfferingId, oid)
                        .eq(StringUtils.hasText(status), OfferingStudent::getStatus, status)
                        .orderByDesc(OfferingStudent::getId));
        List<Long> ids = result.getRecords().stream().map(OfferingStudent::getStudentId).toList();
        Map<Long, SysUser> users = ids.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(SysUser::getId, Function.identity()));
        List<OfferingDtos.StudentVO> list = result.getRecords().stream()
                .filter(r -> matches(users.get(r.getStudentId()), keyword))
                .map(r -> toVo(r, users.get(r.getStudentId()), null))
                .filter(java.util.Objects::nonNull)
                .toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public OfferingDtos.StudentVO create(String offeringId, OfferingDtos.StudentCreateRequest request) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        offeringService.ownedOffering(offeringId);

        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStudentNo, request.studentNo()).last("limit 1"));
        String initialPassword = null;
        if (user == null) {
            initialPassword = initialPassword(request.studentNo());
            user = new SysUser();
            user.setUsername(request.studentNo());
            user.setPasswordHash(passwordEncoder.encode(initialPassword));
            user.setName(request.name());
            user.setRole("STUDENT");
            user.setStudentNo(request.studentNo());
            user.setClassName(request.className());
            user.setEmail(request.email());
            user.setStatus("ACTIVE");
            user.setMustChangePassword(1);
            user.setCreatedAt(Times.now());
            user.setUpdatedAt(Times.now());
            userMapper.insert(user);
        }

        // 同一开课内幂等：已存在关系直接返回，不重复插入
        OfferingStudent relation = rosterMapper.selectOne(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, oid)
                .eq(OfferingStudent::getStudentId, user.getId())
                .last("limit 1"));
        if (relation == null) {
            relation = new OfferingStudent();
            relation.setOfferingId(oid);
            relation.setStudentId(user.getId());
            relation.setStatus("ACTIVE");
            relation.setJoinedAt(Times.now());
            relation.setCreatedAt(Times.now());
            relation.setUpdatedAt(Times.now());
            rosterMapper.insert(relation);
            auditLog.record("STUDENT_ADD", "OFFERING", oid, null,
                    Map.of("studentNo", request.studentNo()), null);
        }
        return toVo(relation, user, initialPassword);
    }

    @Transactional(rollbackFor = Exception.class)
    public OfferingDtos.ResetPasswordVO resetPassword(String offeringId, String studentId) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        Long uid = BizAssert.id(studentId, "studentId");
        offeringService.ownedOffering(offeringId);
        assertInRoster(oid, uid);
        SysUser user = BizAssert.found(userMapper.selectById(uid), "学生");
        String password = initialPassword(user.getStudentNo());
        SysUser patch = new SysUser();
        patch.setId(uid);
        patch.setPasswordHash(passwordEncoder.encode(password));
        patch.setMustChangePassword(1);
        userMapper.updateById(patch);
        auditLog.record("STUDENT_RESET_PASSWORD", "USER", uid, null, null, "教师重置初始密码");
        return new OfferingDtos.ResetPasswordVO(password, true);
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
        OfferingStudent relation = rosterMapper.selectOne(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, oid)
                .eq(OfferingStudent::getStudentId, uid).last("limit 1"));
        BizAssert.found(relation, "名单关系");
        relation.setStatus(request.status());
        relation.setUpdatedAt(Times.now());
        rosterMapper.updateById(relation);
        return toVo(relation, userMapper.selectById(uid), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String offeringId, String studentId) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        Long uid = BizAssert.id(studentId, "studentId");
        offeringService.ownedOffering(offeringId);
        OfferingStudent relation = rosterMapper.selectOne(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, oid)
                .eq(OfferingStudent::getStudentId, uid).last("limit 1"));
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
        rosterMapper.deleteById(relation.getId());
        auditLog.record("STUDENT_REMOVE", "OFFERING", oid, null, null, null);
    }

    private void assertInRoster(Long offeringId, Long studentId) {
        Long count = rosterMapper.selectCount(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, offeringId)
                .eq(OfferingStudent::getStudentId, studentId));
        BizAssert.forbidden(count != null && count > 0, "名单");
    }

    private boolean matches(SysUser user, String keyword) {
        if (!StringUtils.hasText(keyword) || user == null) {
            return true;
        }
        return (user.getName() != null && user.getName().contains(keyword))
                || (user.getStudentNo() != null && user.getStudentNo().contains(keyword));
    }

    private OfferingDtos.StudentVO toVo(OfferingStudent relation, SysUser user, String initialPassword) {
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
                Times.offset(user.getLastLoginAt()), initialPassword);
    }

    private String initialPassword(String studentNo) {
        if (StringUtils.hasText(studentNo) && studentNo.length() >= 6) {
            return studentNo.substring(studentNo.length() - 6);
        }
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
