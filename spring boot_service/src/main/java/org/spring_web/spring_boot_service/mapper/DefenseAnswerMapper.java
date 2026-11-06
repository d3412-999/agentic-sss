package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.DefenseAnswer;
import org.apache.ibatis.annotations.Mapper;

/** 答辩作答 Mapper。 */
@Mapper
public interface DefenseAnswerMapper extends BaseMapper<DefenseAnswer> {
}
