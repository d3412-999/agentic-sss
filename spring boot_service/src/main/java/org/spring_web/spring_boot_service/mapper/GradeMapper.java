package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.Grade;
import org.apache.ibatis.annotations.Mapper;

/** 学生成绩 Mapper。 */
@Mapper
public interface GradeMapper extends BaseMapper<Grade> {
}
