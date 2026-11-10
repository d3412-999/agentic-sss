package org.spring_web.spring_boot_service.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具：数据库存 LocalDateTime（无时区），对外统一输出 ISO-8601 带偏移。
 *
 * <p>课程设计是校内业务，固定按北京时间解释 DB 里的时间，避免不同服务器时区导致
 * 「选题窗口」判断漂移。若将来跨时区部署，改这里一处即可。</p>
 */
public final class Times {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SEMESTER = DateTimeFormatter.ofPattern("uuuu");

    private Times() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(BUSINESS_ZONE);
    }

    /** 转为带 +08:00 偏移的时间，供 DTO 输出。 */
    public static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime();
    }

    public static OffsetDateTime offset(LocalDate date) {
        return date == null ? null : date.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
    }

    /** 前端日期选择器只给到「日」，选题开始按当天 00:00 解释。 */
    public static LocalDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    /** 截止日期按当天 23:59:59.999 解释，符合「含当天」的直觉。 */
    public static LocalDateTime endOfDay(LocalDate date) {
        return date == null ? null : date.atTime(23, 59, 59, 999_000_000);
    }

    /** 校验并解析学期格式 YYYY-YYYY-N。 */
    public static boolean validSemester(String semester) {
        if (semester == null || !semester.matches("\\d{4}-\\d{4}-[12]")) {
            return false;
        }
        int first = Integer.parseInt(semester.substring(0, 4));
        int second = Integer.parseInt(semester.substring(5, 9));
        return second == first + 1;
    }

    public static String year(LocalDateTime value) {
        return value == null ? null : SEMESTER.format(value);
    }
}
