package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.Selection;
import org.apache.ibatis.annotations.Mapper;

/** 学生选题 Mapper。 */
@Mapper
public interface SelectionMapper extends BaseMapper<Selection> {
}
