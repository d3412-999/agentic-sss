package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.spring_web.spring_boot_service.entity.Submission;

/** 提交物（源码 / 报告） Mapper。 */
@Mapper
public interface SubmissionMapper extends BaseMapper<Submission> {

    /**
     * 原子分配下一个版本号：latest_version = latest_version + 1。
     *
     * <p>「查出来再 +1」在并发上传时会拿到相同的版本号，触发
     * (submission_id, version) 唯一索引冲突。把递增放进 UPDATE 里由数据库串行化。</p>
     */
    @Update("UPDATE submission SET latest_version = latest_version + 1 WHERE id = #{id} AND deleted = 0")
    int bumpLatestVersion(@Param("id") long id);
}
