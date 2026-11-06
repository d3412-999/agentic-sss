package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.DefenseQuestion;
import org.apache.ibatis.annotations.Mapper;

/** 答辩题目 Mapper。 */
@Mapper
public interface DefenseQuestionMapper extends BaseMapper<DefenseQuestion> {
}
