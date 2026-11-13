package org.spring_web.spring_boot_service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 答辩违规事件（只追加，不更新）。
 *
 * <p>三个「谁说了算」的约定：</p>
 * <ul>
 *   <li>时间用服务端 {@code occurred_at}，前端时间戳只进 detail 供参考；</li>
 *   <li>等级 {@code severity} 由服务端按类型判定，前端只能选类型；</li>
 *   <li>教师的处置（恢复 / 维持终止）也是一条事件，保证链路可回放。</li>
 * </ul>
 */
@Data
@TableName("defense_violation")
public class DefenseViolation {

    /** 主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 答辩会话 ID（defense_session.id）。 */
    private Long defenseId;

    /** 学生 ID。 */
    private Long studentId;

    /** 事件类型：TAB_SWITCH / DEVTOOLS / PASTE_PATTERN / COPY_BLOCK / SHORTCUT_BLOCK / RESUME / UPHOLD。 */
    private String violationType;

    /** 等级：MAJOR 计入终止阈值 / MINOR 仅留证。 */
    private String severity;

    /** 证据 JSON（停留时长、粘贴长度、窗口尺寸等），不含作答正文。 */
    private String detail;

    /** 客户端 IP。 */
    private String ip;

    /** 浏览器 User-Agent。 */
    private String userAgent;

    /** 发生时间（服务端时间）。 */
    private LocalDateTime occurredAt;

    /** 落库时间。 */
    private LocalDateTime createdAt;
}
