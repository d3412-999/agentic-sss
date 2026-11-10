package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.Times;
import org.spring_web.spring_boot_service.dto.CommonDtos;
import org.spring_web.spring_boot_service.entity.Notification;
import org.spring_web.spring_boot_service.mapper.NotificationMapper;
import org.spring_web.spring_boot_service.security.SecurityUtils;
import org.springframework.stereotype.Service;

/**
 * 站内通知。发布成绩、分析完成、答辩就绪时由业务服务调用 {@link #push}。
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;

    public void push(Long userId, String type, String title, String content, String link) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setLink(link);
        notification.setReadFlag(0);
        notification.setCreatedAt(Times.now());
        notification.setUpdatedAt(Times.now());
        notificationMapper.insert(notification);
    }

    public CommonDtos.NotificationListVO list(boolean unreadOnly, long page, long size) {
        Long userId = SecurityUtils.userId();
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(unreadOnly, Notification::getReadFlag, 0)
                .orderByDesc(Notification::getCreatedAt);
        Page<Notification> result = notificationMapper.selectPage(Page.of(page, size), wrapper);
        List<CommonDtos.NotificationVO> list = result.getRecords().stream().map(this::toVo).toList();
        long unread = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, 0));
        return new CommonDtos.NotificationListVO(list, result.getTotal(), unread);
    }

    public void markRead(String notificationId) {
        Long id = BizAssert.id(notificationId, "notificationId");
        Notification notification = BizAssert.found(notificationMapper.selectById(id), "通知");
        BizAssert.forbidden(notification.getUserId().equals(SecurityUtils.userId()), "通知");
        notification.setReadFlag(1);
        notification.setReadAt(Times.now());
        notification.setUpdatedAt(Times.now());
        notificationMapper.updateById(notification);
    }

    public int readAll() {
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, SecurityUtils.userId())
                .eq(Notification::getReadFlag, 0)
                .set(Notification::getReadFlag, 1)
                .set(Notification::getReadAt, Times.now())
                .set(Notification::getUpdatedAt, Times.now()));
    }

    private CommonDtos.NotificationVO toVo(Notification n) {
        return new CommonDtos.NotificationVO(String.valueOf(n.getId()), n.getType(), n.getTitle(),
                n.getContent(), n.getLink(), Integer.valueOf(1).equals(n.getReadFlag()),
                Times.offset(n.getCreatedAt()));
    }
}
