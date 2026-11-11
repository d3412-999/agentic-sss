package org.spring_web.spring_boot_service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TrustedProxyIp 的离线行为测试：纯静态方法，不依赖 Spring 容器、不发起网络 / DNS 请求。
 *
 * <p>核心安全断言：<b>默认（白名单为空）绝不采信 X-Forwarded-For</b>，伪造来源 IP 不能
 * 影响登录限流与审计日志；只有直连对端命中可信代理白名单时才按代理链解析。</p>
 */
class TrustedProxyIpTest {

    // ------------------------------------------------------------ IP 字面量校验

    @Test
    void validIpv4LiteralsAccepted() {
        assertTrue(TrustedProxyIp.isIpLiteral("127.0.0.1"));
        assertTrue(TrustedProxyIp.isIpLiteral("10.0.0.0"));
        assertTrue(TrustedProxyIp.isIpLiteral("0.0.0.0"));
        assertTrue(TrustedProxyIp.isIpLiteral("255.255.255.255"));
    }

    @Test
    void invalidIpv4LiteralsRejected() {
        assertFalse(TrustedProxyIp.isIpLiteral(null));
        assertFalse(TrustedProxyIp.isIpLiteral(""));
        assertFalse(TrustedProxyIp.isIpLiteral("256.0.0.1"));
        assertFalse(TrustedProxyIp.isIpLiteral("192.168.1"));
        assertFalse(TrustedProxyIp.isIpLiteral("192.168.1.1.1"));
        assertFalse(TrustedProxyIp.isIpLiteral("192.168.1.-1"));
        assertFalse(TrustedProxyIp.isIpLiteral("192.168.01.1"));
        assertFalse(TrustedProxyIp.isIpLiteral("localhost"));
        assertFalse(TrustedProxyIp.isIpLiteral(" 192.168.1.1"));
    }

    @Test
    void ipv6LiteralsAccepted() {
        assertTrue(TrustedProxyIp.isIpLiteral("::1"));
        assertTrue(TrustedProxyIp.isIpLiteral("::"));
        assertTrue(TrustedProxyIp.isIpLiteral("2001:db8::1"));
        assertTrue(TrustedProxyIp.isIpLiteral("fe80::a1b2:3c4d:5e6f:7a8b"));
        assertTrue(TrustedProxyIp.isIpLiteral("2001:0DB8:0000:0000:0000:0000:0000:0001"));
    }

    @Test
    void invalidIpv6LiteralsRejected() {
        assertFalse(TrustedProxyIp.isIpLiteral("2001:db8:::1"));
        assertFalse(TrustedProxyIp.isIpLiteral("gggg::1"));
        assertFalse(TrustedProxyIp.isIpLiteral("1:2:3:4:5:6:7"));
        assertFalse(TrustedProxyIp.isIpLiteral("1:2:3:4:5:6:7:8:9"));
        assertFalse(TrustedProxyIp.isIpLiteral("::ffff:192.168.1.1"));
        assertFalse(TrustedProxyIp.isIpLiteral("[::1]"));
        assertFalse(TrustedProxyIp.isIpLiteral("fe80::1%eth0"));
    }

    // ------------------------------------------------------------ 可信代理判定

    @Test
    void exactIpMatchIsTrusted() {
        assertTrue(TrustedProxyIp.isTrusted("127.0.0.1", List.of("127.0.0.1")));
        assertFalse(TrustedProxyIp.isTrusted("127.0.0.2", List.of("127.0.0.1")));
    }

    @Test
    void cidrMatchIsTrusted() {
        List<String> trusted = List.of("192.168.1.0/24");
        assertTrue(TrustedProxyIp.isTrusted("192.168.1.1", trusted));
        assertTrue(TrustedProxyIp.isTrusted("192.168.1.255", trusted));
        assertFalse(TrustedProxyIp.isTrusted("192.168.2.1", trusted));
        // 主机位非 0 的 CIDR 按标准掩码语义处理
        assertTrue(TrustedProxyIp.isTrusted("10.1.2.3", List.of("10.1.2.99/24")));
        assertTrue(TrustedProxyIp.isTrusted("8.8.8.8", List.of("0.0.0.0/0")));
        assertTrue(TrustedProxyIp.isTrusted("203.0.113.7", List.of("203.0.113.7/32")));
        // 非法规则不参与匹配
        assertFalse(TrustedProxyIp.isTrusted("10.0.0.1", List.of("10.0.0.0/33")));
        assertFalse(TrustedProxyIp.isTrusted("10.0.0.1", List.of("10.0.0.0/")));
        assertFalse(TrustedProxyIp.isTrusted("10.0.0.1", List.of("10.0.0.0/-1")));
    }

    @Test
    void emptyTrustListTrustsNothing() {
        assertFalse(TrustedProxyIp.isTrusted("127.0.0.1", List.of()));
        assertFalse(TrustedProxyIp.isTrusted("127.0.0.1", null));
        assertEquals("127.0.0.1", TrustedProxyIp.resolve("127.0.0.1", "1.2.3.4", List.of()));
    }

    @Test
    void blankEntriesAreIgnored() {
        List<String> trusted = List.of("", "   ", "203.0.113.9");
        assertTrue(TrustedProxyIp.isTrusted("203.0.113.9", trusted));
        assertFalse(TrustedProxyIp.isTrusted("203.0.113.10", trusted));
    }

    // ---------------------------------------------------------- 客户端 IP 解析

    @Test
    void untrustedPeerIgnoresForwardedFor() {
        // 默认场景：直连对端不是代理，客户端伪造的 X-Forwarded-For 必须被忽略
        assertEquals("198.51.100.7",
                TrustedProxyIp.resolve("198.51.100.7", "1.2.3.4", List.of("10.0.0.0/8")));
        assertEquals("198.51.100.7",
                TrustedProxyIp.resolve("198.51.100.7", "1.2.3.4, 5.6.7.8", List.of()));
    }

    @Test
    void blankForwardedForFallsBackToRemoteAddr() {
        List<String> trusted = List.of("127.0.0.1");
        assertEquals("127.0.0.1", TrustedProxyIp.resolve("127.0.0.1", null, trusted));
        assertEquals("127.0.0.1", TrustedProxyIp.resolve("127.0.0.1", "   ", trusted));
    }

    @Test
    void trustedPeerResolvesClientFromForwardedFor() {
        List<String> trusted = List.of("127.0.0.1");
        assertEquals("203.0.113.7",
                TrustedProxyIp.resolve("127.0.0.1", "203.0.113.7", trusted));
        // 可信代理也能传递 IPv6 客户端
        assertEquals("2001:db8::1",
                TrustedProxyIp.resolve("127.0.0.1", "2001:db8::1", trusted));
    }

    @Test
    void trustedProxyChainSkipsKnownProxies() {
        // XFF 从左到右：客户端, 外层代理, 内层代理；直连对端是内层代理
        String client = TrustedProxyIp.resolve("10.0.0.2",
                "198.51.100.7, 10.0.0.9, 10.0.0.2", List.of("10.0.0.0/8"));
        assertEquals("198.51.100.7", client);
    }

    @Test
    void allHopsTrustedReturnsLeftmostLiteral() {
        assertEquals("10.0.0.5", TrustedProxyIp.resolve("10.0.0.2",
                "10.0.0.5, 10.0.0.6", List.of("10.0.0.0/8")));
    }

    @Test
    void invalidForwardedEntriesAreIgnored() {
        List<String> trusted = List.of("127.0.0.1");
        // 非法段丢弃后，从右往左第一个合法且不可信的段就是真实客户端
        assertEquals("198.51.100.7",
                TrustedProxyIp.resolve("127.0.0.1", "198.51.100.7, not-an-ip", trusted));
        // 全是垃圾：回退直连地址，绝不把垃圾串当成客户端 IP
        assertEquals("127.0.0.1",
                TrustedProxyIp.resolve("127.0.0.1", "unknown, ", trusted));
        assertEquals("127.0.0.1",
                TrustedProxyIp.resolve("127.0.0.1", "1.2.3.4.5", trusted));
    }
}
