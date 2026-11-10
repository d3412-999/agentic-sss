package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.Topic;
import org.apache.ibatis.annotations.Mapper;

/** 课程设计题目 Mapper。 */
@Mapper
public interface TopicMapper extends BaseMapper<Topic> {
}
