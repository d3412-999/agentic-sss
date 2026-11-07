package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.GradeItem;
import org.apache.ibatis.annotations.Mapper;

/** 成绩明细 Mapper。 */
@Mapper
public interface GradeItemMapper extends BaseMapper<GradeItem> {
}
