package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.SubmissionVersion;
import org.apache.ibatis.annotations.Mapper;

/** 提交物版本 Mapper。 */
@Mapper
public interface SubmissionVersionMapper extends BaseMapper<SubmissionVersion> {
}
