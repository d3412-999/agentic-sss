package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.spring_web.spring_boot_service.entity.SysUser;

/** 用户（教师 / 学生） Mapper。 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 原子递增令牌版本，令该用户所有已签发令牌立即失效（改密码 / 教师重置密码 / 强制下线）。
     *
     * <p>用一条 UPDATE 完成「读 + 写」，避免高并发下两个请求各自读到旧版本再写回、只 +1 一次；
     * 返回 0 表示用户不存在或已逻辑删除。</p>
     */
    @Update("UPDATE sys_user SET token_version = token_version + 1, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND deleted = 0")
    int bumpTokenVersion(@Param("id") long id);
}
