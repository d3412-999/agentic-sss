package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.AsyncTask;
import org.apache.ibatis.annotations.Mapper;

/** 异步任务 Mapper。 */
@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {
}
