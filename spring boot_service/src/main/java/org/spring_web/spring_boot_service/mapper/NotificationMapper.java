package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring_web.spring_boot_service.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/** 站内通知 Mapper。 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
