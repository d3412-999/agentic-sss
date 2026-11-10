package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.Offering;
import org.apache.ibatis.annotations.Mapper;

/** 开课 Mapper。 */
@Mapper
public interface OfferingMapper extends BaseMapper<Offering> {
}
