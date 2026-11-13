package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.spring_web.spring_boot_service.entity.SubmissionVersionFile;

/** 提交版本文件清单 Mapper。 */
@Mapper
public interface SubmissionVersionFileMapper extends BaseMapper<SubmissionVersionFile> {
}
