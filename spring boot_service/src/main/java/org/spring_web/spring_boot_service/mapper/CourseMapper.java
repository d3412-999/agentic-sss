package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.Course;
import org.apache.ibatis.annotations.Mapper;

/** 课程模板 Mapper。 */
@Mapper
public interface CourseMapper extends BaseMapper<Course> {
}
