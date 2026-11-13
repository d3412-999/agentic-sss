package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.spring_web.spring_boot_service.entity.SubmissionSimilarity;

/** 相似度命中记录 Mapper。 */
@Mapper
public interface SubmissionSimilarityMapper extends BaseMapper<SubmissionSimilarity> {
}
