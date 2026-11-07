package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.OfferingStudent;
import org.apache.ibatis.annotations.Mapper;

/** 开课学生名单 Mapper。 */
@Mapper
public interface OfferingStudentMapper extends BaseMapper<OfferingStudent> {
}
