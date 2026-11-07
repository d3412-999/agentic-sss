package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.Submission;
import org.apache.ibatis.annotations.Mapper;

/** 提交物（源码 / 报告） Mapper。 */
@Mapper
public interface SubmissionMapper extends BaseMapper<Submission> {
}
