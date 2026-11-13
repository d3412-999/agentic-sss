package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.spring_web.spring_boot_service.entity.DefenseBatchItem;

/** 批量发起答辩逐条结果。 */
@Mapper
public interface DefenseBatchItemMapper extends BaseMapper<DefenseBatchItem> {
}
