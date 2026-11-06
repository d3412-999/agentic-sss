package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.FileObject;
import org.apache.ibatis.annotations.Mapper;

/** 文件对象 Mapper。 */
@Mapper
public interface FileObjectMapper extends BaseMapper<FileObject> {
}
