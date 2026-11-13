package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.spring_web.spring_boot_service.entity.DefenseBatchTask;

/** 批量发起答辩任务。 */
@Mapper
public interface DefenseBatchTaskMapper extends BaseMapper<DefenseBatchTask> {
}
