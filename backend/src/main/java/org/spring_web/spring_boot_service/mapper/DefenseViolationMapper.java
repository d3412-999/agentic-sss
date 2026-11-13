package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.spring_web.spring_boot_service.entity.DefenseViolation;

/** 答辩违规事件表（只追加）。 */
@Mapper
public interface DefenseViolationMapper extends BaseMapper<DefenseViolation> {
}
