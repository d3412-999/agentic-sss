package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户（教师 / 学生）。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /**
     * 主键（雪花 ID）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 登录账号：学号 / 工号
     */
    private String username;

    /**
     * BCrypt 密码哈希
     */
    private String passwordHash;

    /**
     * 姓名
     */
    private String name;

    /**
     * 角色：STUDENT / TEACHER / ADMIN
     */
    private String role;

    /**
     * 学号（学生）
     */
    private String studentNo;

    /**
     * 班级
     */
    private String className;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 手机号（教师档案）
     */
    private String phone;

    /**
     * 所属院系 / 部门（教师档案）
     */
    private String department;

    /**
     * 头像文件 ID
     */
    private Long avatarFileId;

    /**
     * 状态：ACTIVE / DISABLED
     */
    private String status;

    /**
     * 是否必须修改密码：0 否 / 1 是
     */
    private Integer mustChangePassword;

    /**
     * 令牌版本：递增即吊销该用户所有已签发令牌。
     *
     * <p>存 MySQL 而不是 Redis：Redis 被 flush 或换实例后版本号不能跟着归零，
     * 否则改密码 / 教师重置密码 / 强制下线都会失效。</p>
     */
    private Long tokenVersion;

    /**
     * 最近登录时间
     */
    private LocalDateTime lastLoginAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除：0 正常 / 1 已删除
     */
    @TableLogic
    private Integer deleted;

}
