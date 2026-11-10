package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.spring_web.spring_boot_service.entity.ExcellentWork;

/**
 * 优秀作品模板库 Mapper。
 */
@Mapper
public interface ExcellentWorkMapper extends BaseMapper<ExcellentWork> {
}
