package org.spring_web.spring_boot_service.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 可信反向代理场景下的客户端 IP 解析。
 *
 * <p><b>为什么不能无条件信任 X-Forwarded-For</b>：该请求头完全由客户端可控，任何人都能
 * 伪造 {@code X-Forwarded-For: 1.2.3.4}。若直接取第一段，攻击者可以通过轮换伪造 IP 绕过
 * 「按来源 IP 的登录失败限流」，也会把假 IP 写进审计日志。正确做法是先判断直连对端
 * （{@code remoteAddr}）是否命中可信代理白名单，只有命中时才采信这个头。</p>
 *
 * <p>白名单默认空 = 永不信任，此时行为与「直接取 remoteAddr」完全一致，单机部署不受影响。
 * 判定规则支持精确 IP 与 IPv4 CIDR（如 {@code 10.0.0.0/8}），不支持域名——本类刻意不做
 * DNS 解析，避免解析失败 / DNS 重绑定带来新的不确定性。</p>
 *
 * <p>本类只依赖 {@code java.*}，不依赖 Spring，便于单元测试与离线编译。</p>
 */
public final class TrustedProxyIp {

    private TrustedProxyIp() {
    }

    /**
     * 是否为合法 IP 字面量：严格 IPv4 点分十进制（每段 0-255，拒绝前导零）或 IPv6 字面量。
     *
     * <p>IPv6 只认「十六进制 + 冒号」的标准写法，因此带方括号、带 zone id（{@code %eth0}）、
     * 内嵌 IPv4（{@code ::ffff:1.2.3.4}）的写法都不算字面量，会被丢弃而不是原样返回。</p>
     */
    public static boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            return isIpv6Literal(value);
        }
        return isIpv4Literal(value);
    }

    /**
     * 对端 IP 是否命中可信代理白名单：精确匹配或 IPv4 CIDR 匹配。
     *
     * <p>空白项直接忽略；白名单为空（含 {@code null}）时任何 IP 都不可信。</p>
     */
    public static boolean isTrusted(String ip, Collection<String> trustedProxies) {
        if (ip == null || ip.isBlank() || trustedProxies == null || trustedProxies.isEmpty()) {
            return false;
        }
        String candidate = ip.trim();
        for (String trustedProxy : trustedProxies) {
            if (trustedProxy == null || trustedProxy.isBlank()) {
                continue;
            }
            String rule = trustedProxy.trim();
            if (rule.equals(candidate)) {
                return true;
            }
            if (rule.indexOf('/') >= 0 && matchesIpv4Cidr(candidate, rule)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析真实客户端 IP。
     *
     * <p>规则：</p>
     * <ol>
     *   <li>X-Forwarded-For 为空，或直连对端不是可信代理 —— 一律忽略该头，返回 remoteAddr；</li>
     *   <li>否则按逗号拆分并丢弃非法 / 空白段，从右往左跳过可信代理，第一个不可信的
     *       跳点即真实客户端（最靠近服务器的不可信中继）；</li>
     *   <li>若所有跳点都是可信代理，取最左侧的字面量；一个合法段都没有则回退 remoteAddr。</li>
     * </ol>
     *
     * <p>从右往左的原因是：X-Forwarded-For 由各级代理按「客户端在前、每经过一跳在尾部追加」
     * 的顺序写入，左侧段可被客户端伪造，右侧段才是离服务器最近的代理亲手写下的。</p>
     */
    public static String resolve(String remoteAddr, String forwardedFor,
                                 Collection<String> trustedProxies) {
        if (forwardedFor == null || forwardedFor.isBlank()
                || !isTrusted(remoteAddr, trustedProxies)) {
            // 默认配置下走到这里：X-Forwarded-For 一律不采信，伪造头无效
            return remoteAddr;
        }
        List<String> hops = new ArrayList<>();
        for (String rawHop : forwardedFor.split(",")) {
            String hop = rawHop.trim();
            if (isIpLiteral(hop)) {
                hops.add(hop);
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (!isTrusted(hop, trustedProxies)) {
                return hop;
            }
        }
        // 所有跳点都是可信代理，或头里没有任何合法字面量：退化到最左值 / 直连地址
        return hops.isEmpty() ? remoteAddr : hops.get(0);
    }

    private static boolean isIpv4Literal(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                // 拒绝 010 这类前导零写法：不同解析器会按八进制处理，存在歧义
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6Literal(String value) {
        if (value.indexOf(':') < 0) {
            return false;
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && value.indexOf("::", compression + 1) >= 0) {
            return false;
        }
        if (compression >= 0) {
            String head = value.substring(0, compression);
            String tail = value.substring(compression + 2);
            int headGroups = countIpv6Groups(head);
            int tailGroups = countIpv6Groups(tail);
            // 任一侧含非法分组（非 hex / 空组 / 超长）都直接判非法：
            // 若把 -1 和另一侧相加，可能刚好抵消成 0 而被误判为合法（如 gggg::1）。
            if (headGroups < 0 || tailGroups < 0) {
                return false;
            }
            // "::" 至少要压缩掉一组，所以显式分组数必须小于 8
            return headGroups + tailGroups < 8;
        }
        return countIpv6Groups(value) == 8;
    }

    /** 返回 16 位分组的个数；出现空分组 / 非十六进制 / 超长分组时返回 -1。 */
    private static int countIpv6Groups(String part) {
        if (part.isEmpty()) {
            return 0;
        }
        String[] groups = part.split(":", -1);
        for (String group : groups) {
            if (group.isEmpty() || group.length() > 4) {
                return -1;
            }
            for (int i = 0; i < group.length(); i++) {
                if (Character.digit(group.charAt(i), 16) < 0) {
                    return -1;
                }
            }
        }
        return groups.length;
    }

    private static boolean matchesIpv4Cidr(String ip, String rule) {
        int slash = rule.indexOf('/');
        if (slash <= 0 || slash == rule.length() - 1) {
            return false;
        }
        String network = rule.substring(0, slash);
        String prefixText = rule.substring(slash + 1);
        if (!isIpv4Literal(ip) || !isIpv4Literal(network)) {
            return false;
        }
        if (prefixText.length() > 2) {
            return false;
        }
        for (int i = 0; i < prefixText.length(); i++) {
            char c = prefixText.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        int prefix = Integer.parseInt(prefixText);
        if (prefix > 32) {
            return false;
        }
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (ipv4ToLong(ip) & mask) == (ipv4ToLong(network) & mask);
    }

    private static long ipv4ToLong(String value) {
        String[] parts = value.split("\\.", -1);
        long result = 0L;
        for (String part : parts) {
            result = (result << 8) | Integer.parseInt(part);
        }
        return result;
    }
}
