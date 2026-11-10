package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.Score;
import org.apache.ibatis.annotations.Mapper;

/** 评分明细 Mapper。 */
@Mapper
public interface ScoreMapper extends BaseMapper<Score> {
}
