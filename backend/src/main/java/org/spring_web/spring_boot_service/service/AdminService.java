package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.common.PageResult;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.AdminDtos;
import org.spring_web.spring_boot_service.entity.Offering;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.mapper.OfferingMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 管理员端的唯一业务：教师账号管理。
 *
 * <p><b>权限模型说明</b>：本系统沿用「角色即权限包」的 RBAC。
 * 教师账号创建后 role 固定为 {@code TEACHER}，即可使用教师端的全部能力
 * （教学班与学生名单、题目与选题、提交评审、成绩、答辩），等同学习通里的教师角色；
 * 管理员账号只允许访问 {@code /api/v1/admin/**}，由 SecurityConfig 兜底。</p>
 *
 * <p>教师使用统一初始密码，{@code must_change_password=1} 记录首次登录需改密；
 * 重置密码与删除都会递增 tokenVersion，令已签发的令牌立即失效。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final String ROLE_TEACHER = "TEACHER";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{2,64}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** 教师角色的权限目录：只用于管理员端展示「这个账号将获得什么能力」。 */
    private static final List<AdminDtos.PermissionVO> TEACHER_PERMISSIONS = List.of(
            new AdminDtos.PermissionVO("TEACHER_OFFERING", "教学班与学生名单",
                    "创建 / 发布教学班，导入、停用、重置学生账号"),
            new AdminDtos.PermissionVO("TEACHER_TOPIC", "题目与选题",
                    "出题、批量发布、归档，查看学生选题与退选"),
            new AdminDtos.PermissionVO("TEACHER_REVIEW", "提交评审与模板库",
                    "查看源码 / 报告评审结果，维护优秀作品模板库"),
            new AdminDtos.PermissionVO("TEACHER_GRADE", "成绩管理与发布",
                    "三维加权算分、人工调整、发布与导出"),
            new AdminDtos.PermissionVO("TEACHER_DEFENSE", "答辩组织与评分",
                    "发起答辩、AI 出题、查看录像与评分结果"));

    private final SysUserMapper userMapper;
    private final OfferingMapper offeringMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLog;
    private final SssProperties properties;

    public PageResult<AdminDtos.TeacherVO> list(String keyword, String status, long page, long size) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, size), 100);

        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, ROLE_TEACHER)
                .eq(StringUtils.hasText(status), SysUser::getStatus, status)
                .orderByDesc(SysUser::getCreatedAt);
        String kw = normalize(keyword);
        if (kw != null) {
            wrapper.and(w -> w.like(SysUser::getUsername, kw)
                    .or().like(SysUser::getName, kw)
                    .or().like(SysUser::getDepartment, kw)
                    .or().like(SysUser::getPhone, kw)
                    .or().like(SysUser::getEmail, kw));
        }

        Page<SysUser> result = userMapper.selectPage(Page.of(safePage, safeSize), wrapper);
        List<AdminDtos.TeacherVO> list = result.getRecords().stream().map(this::toVo).toList();
        return PageResult.of(list, result.getTotal(), safePage, safeSize);
    }

    public List<AdminDtos.PermissionVO> permissions() {
        return TEACHER_PERMISSIONS;
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminDtos.TeacherVO create(AdminDtos.TeacherCreateRequest request) {
        BizAssert.param(request != null, "请求内容不能为空");
        String username = requireUsername(request.username());
        String name = requireName(request.name());
        String department = optional(request.department(), 64, "所属院系 / 部门");
        String phone = optional(request.phone(), 32, "手机号");
        String email = optionalEmail(request.email());

        assertUsernameFree(username);

        SysUser user = new SysUser();
        user.setUsername(username);
        String initialPassword = defaultPassword();
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setName(name);
        user.setRole(ROLE_TEACHER);
        user.setDepartment(department);
        user.setPhone(phone);
        user.setEmail(email);
        user.setStatus(STATUS_ACTIVE);
        user.setMustChangePassword(1);
        user.setTokenVersion(0L);
        user.setCreatedAt(Times.now());
        user.setUpdatedAt(Times.now());
        userMapper.insert(user);

        auditLog.record("TEACHER_CREATE", "USER", user.getId(), null, snapshot(user),
                "管理员新增教师账号");
        return toVo(user, initialPassword);
    }

    /**
     * 批量新增：逐行校验、逐行返回结果。
     *
     * <p>刻意不把整批放在同一个事务里：一行工号重复不应该回滚其他合法行，
     * 前端也能直接展示「第几行失败、为什么失败」。</p>
     */
    public AdminDtos.BatchCreateResult batchCreate(AdminDtos.BatchCreateRequest request) {
        List<AdminDtos.TeacherCreateRequest> rows = request == null ? null : request.teachers();
        BizAssert.param(rows != null && !rows.isEmpty(), "请至少填写一位教师");
        int max = Math.max(1, properties.getAdmin().getMaxBatchSize());
        BizAssert.param(rows.size() <= max, "单次最多添加 " + max + " 位教师");

        Set<String> seen = new HashSet<>();
        List<AdminDtos.BatchRowResult> items = new ArrayList<>();
        int success = 0;

        for (int i = 0; i < rows.size(); i++) {
            AdminDtos.TeacherCreateRequest row = rows.get(i);
            if (row == null) {
                items.add(new AdminDtos.BatchRowResult(i + 1, null, null, false, "该行内容为空"));
                continue;
            }
            String username = normalize(row.username());
            String name = normalize(row.name());
            try {
                BizAssert.param(username != null, "工号不能为空");
                BizAssert.param(seen.add(username.toLowerCase(Locale.ROOT)),
                        "本次提交中工号重复");
                create(row);
                success++;
                items.add(new AdminDtos.BatchRowResult(i + 1, username, name, true, "已创建"));
            } catch (BizException ex) {
                items.add(new AdminDtos.BatchRowResult(i + 1, username, name, false, ex.getMessage()));
            } catch (DuplicateKeyException ex) {
                items.add(new AdminDtos.BatchRowResult(i + 1, username, name, false, "工号已存在"));
            }
        }
        return new AdminDtos.BatchCreateResult(rows.size(), success, rows.size() - success, items);
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminDtos.TeacherVO update(String teacherId, AdminDtos.TeacherUpdateRequest request) {
        BizAssert.param(request != null, "请求内容不能为空");
        SysUser user = requireTeacher(teacherId);
        String name = requireName(request.name());
        String department = optional(request.department(), 64, "所属院系 / 部门");
        String phone = optional(request.phone(), 32, "手机号");
        String email = optionalEmail(request.email());

        Map<String, Object> before = snapshot(user);
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getName, name)
                .set(SysUser::getDepartment, department)
                .set(SysUser::getPhone, phone)
                .set(SysUser::getEmail, email)
                .set(SysUser::getUpdatedAt, Times.now()));

        SysUser updated = BizAssert.found(userMapper.selectById(user.getId()), "教师");
        auditLog.record("TEACHER_UPDATE", "USER", user.getId(), before, snapshot(updated),
                "管理员修改教师资料");
        return toVo(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminDtos.TeacherVO updateStatus(String teacherId, String status) {
        BizAssert.param(STATUS_ACTIVE.equals(status) || STATUS_DISABLED.equals(status),
                "状态只能是 ACTIVE 或 DISABLED");
        SysUser user = requireTeacher(teacherId);
        if (!status.equals(user.getStatus())) {
            userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, user.getId())
                    .set(SysUser::getStatus, status)
                    .set(SysUser::getUpdatedAt, Times.now()));
            if (STATUS_DISABLED.equals(status)) {
                // 停用后立即踢下线，避免旧令牌继续使用
                userMapper.bumpTokenVersion(user.getId());
            }
            auditLog.record("TEACHER_STATUS", "USER", user.getId(), user.getStatus(), status,
                    STATUS_ACTIVE.equals(status) ? "管理员启用教师账号" : "管理员停用教师账号");
        }
        return toVo(BizAssert.found(userMapper.selectById(user.getId()), "教师"));
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminDtos.ResetPasswordVO resetPassword(String teacherId) {
        SysUser user = requireTeacher(teacherId);
        String password = defaultPassword();
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getPasswordHash, passwordEncoder.encode(password))
                .set(SysUser::getMustChangePassword, 1)
                .set(SysUser::getUpdatedAt, Times.now()));
        userMapper.bumpTokenVersion(user.getId());
        auditLog.record("TEACHER_RESET_PASSWORD", "USER", user.getId(), null, null,
                "管理员重置教师初始密码");
        return new AdminDtos.ResetPasswordVO(password, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String teacherId) {
        SysUser user = requireTeacher(teacherId);
        Long activeOfferings = offeringMapper.selectCount(new LambdaQueryWrapper<Offering>()
                .eq(Offering::getTeacherId, user.getId())
                .ne(Offering::getStatus, "ARCHIVED"));
        BizAssert.state(activeOfferings == null || activeOfferings == 0,
                "该教师名下还有 " + (activeOfferings == null ? 0 : activeOfferings)
                        + " 个未归档教学班，暂不能删除");

        int affected = userMapper.logicalDeleteTeacher("deleted#" + user.getId(), user.getId());
        BizAssert.state(affected > 0, "账号状态已变更，请刷新后重试");
        auditLog.record("TEACHER_DELETE", "USER", user.getId(), snapshot(user), null,
                "管理员删除教师账号");
    }

    /* ------------------------------------------------------------------ */
    /* 校验与转换                                                          */
    /* ------------------------------------------------------------------ */

    private SysUser requireTeacher(String rawId) {
        Long id = BizAssert.id(rawId, "教师 ID");
        SysUser user = BizAssert.found(userMapper.selectById(id), "教师");
        BizAssert.param(ROLE_TEACHER.equals(user.getRole()), "该账号不是教师");
        return user;
    }

    private void assertUsernameFree(String username) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        BizAssert.check(count == null || count == 0, ErrorCode.CONFLICT,
                "工号 " + username + " 已被占用");
    }

    private String requireUsername(String raw) {
        String username = normalize(raw);
        BizAssert.param(username != null, "工号不能为空");
        BizAssert.param(USERNAME_PATTERN.matcher(username).matches(),
                "工号只能包含字母、数字、下划线或短横线（2~64 位）");
        return username;
    }

    private String requireName(String raw) {
        String name = normalize(raw);
        BizAssert.param(name != null, "姓名不能为空");
        BizAssert.param(name.length() <= 50, "姓名不能超过 50 个字符");
        return name;
    }

    private String optional(String raw, int maxLength, String label) {
        String value = normalize(raw);
        BizAssert.param(value == null || value.length() <= maxLength,
                label + "不能超过 " + maxLength + " 个字符");
        return value;
    }

    private String optionalEmail(String raw) {
        String email = normalize(raw);
        if (email == null) {
            return null;
        }
        BizAssert.param(email.length() <= 128, "邮箱不能超过 128 个字符");
        BizAssert.param(EMAIL_PATTERN.matcher(email).matches(), "邮箱格式不正确");
        return email;
    }

    private String defaultPassword() {
        String password = properties.getAdmin().getDefaultTeacherPassword();
        BizAssert.param(StringUtils.hasText(password), "未配置教师初始密码");
        return password;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    private Map<String, Object> snapshot(SysUser user) {
        Map<String, Object> map = new HashMap<>();
        map.put("username", user.getUsername());
        map.put("name", user.getName());
        map.put("department", user.getDepartment());
        map.put("phone", user.getPhone());
        map.put("email", user.getEmail());
        map.put("status", user.getStatus());
        return map;
    }

    private AdminDtos.TeacherVO toVo(SysUser user) {
        return toVo(user, null);
    }

    private AdminDtos.TeacherVO toVo(SysUser user, String initialPassword) {
        return new AdminDtos.TeacherVO(
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getName(),
                user.getDepartment(),
                user.getPhone(),
                user.getEmail(),
                user.getStatus(),
                Integer.valueOf(1).equals(user.getMustChangePassword()),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                TEACHER_PERMISSIONS.stream().map(AdminDtos.PermissionVO::code).toList(),
                initialPassword);
    }
}
