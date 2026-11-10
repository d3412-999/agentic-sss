package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.DefenseSession;
import org.apache.ibatis.annotations.Mapper;

/** 答辩会话 Mapper。 */
@Mapper
public interface DefenseSessionMapper extends BaseMapper<DefenseSession> {
}
