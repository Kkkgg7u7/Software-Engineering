package com.werewolf.config;

import org.springframework.util.AntPathMatcher;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CORS / WebSocket 的 Origin 匹配器。
 *
 * <p>支持三种写法：
 * <ol>
 *   <li><b>精确地址</b>：{@code https://werewolf.example.com}</li>
 *   <li><b>Ant 通配</b>：{@code http://192.168.*.*:*}、{@code https://*.trycloudflare.com}</li>
 *   <li><b>{@code *}</b>：放行任意来源（局域网开黑常用，见下）</li>
 * </ol>
 *
 * <p><b>为什么要单独做这个类</b>：本项目的部署场景是"一个人开服务、其他人用自己的电脑连进来"。
 * 只用固定白名单会遇到两个真实问题：
 * <ul>
 *   <li>很多校园网 / 企业网的地址<b>不在 RFC1918 私网段</b>（例如某校出口就是
 *       {@code 222.197.x.x}），写 {@code http://192.168.*.*} 这类规则放行不了；</li>
 *   <li>换了网络环境（宿舍 → 教室 → 热点）本机 IP 就变了，重新构建/改配置很麻烦。</li>
 * </ul>
 *
 * <p>因此这里额外做两件事：
 * <ul>
 *   <li><b>自动放行本机所有网卡地址</b>：运行时枚举本机 IPv4，凡是访问"我自己"的 Origin 一律放行，
 *       这样无论 IP 怎么变、端口是 5173 还是 4173，队友都能连上；</li>
 *   <li><b>支持 {@code *}</b>：通过环境变量 {@code WEREWOLF_CORS_ORIGINS=*} 一次性放行所有来源，
 *       适合临时演示。注意这等于对任意网站开放接口，公网长期运行请勿使用。</li>
 * </ul>
 */
public final class OriginMatcher {

    /** 放行任意来源的通配符。 */
    public static final String WILDCARD = "*";

    private static final AntPathMatcher MATCHER = new AntPathMatcher(".");

    /**
     * 本机网卡地址缓存有效期（毫秒）。
     *
     * <p><b>为什么要有缓存、且必须是"会过期"的缓存</b>：
     * 笔记本换 WiFi / 插拔网线时 IP 会变。早期实现把网卡地址放在
     * {@code static final} 字段里只算一次，结果换网络后服务仍在放行旧 IP、
     * {@code /api/health} 也一直报旧地址，别人拿到的就是错的地址。
     * 因此这里改成短 TTL 缓存：既避免每次 CORS 判定都去枚举网卡（有系统调用开销），
     * 又能让换网后 5 秒内自动跟上。
     */
    private static final long CACHE_TTL_MILLIS = 5_000L;

    private static volatile Set<String> cachedIps = Set.of();
    private static volatile long cachedAt = 0L;

    private OriginMatcher() {
    }

    /**
     * @param patterns 配置的来源模式列表
     * @return 是否允许所有来源（当列表里出现 {@code *} 时）
     */
    public static boolean allowsAll(List<String> patterns) {
        return patterns != null && patterns.stream().anyMatch(WILDCARD::equals);
    }

    /** 判断某个 Origin 是否被允许。 */
    public static boolean matches(String origin, List<String> patterns) {
        if (origin == null || origin.isBlank()) {
            // 没有 Origin 头的请求（curl、同源请求、部分代理）直接放行
            return true;
        }
        if (allowsAll(patterns)) {
            return true;
        }
        if (isLocalOrigin(origin)) {
            return true;
        }
        if (patterns != null) {
            for (String pattern : patterns) {
                if (pattern == null || pattern.isBlank()) {
                    continue;
                }
                if (pattern.equalsIgnoreCase(origin) || MATCHER.match(pattern, origin)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Origin 指向的是不是本机的某个网卡地址（含 localhost / 127.0.0.1）。 */
    public static boolean isLocalOrigin(String origin) {
        String host = hostOf(origin);
        if (host == null) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
            return true;
        }
        return currentLocalIps().contains(host);
    }

    /** 从 {@code http://192.168.1.23:5173} 里取出 {@code 192.168.1.23}。 */
    private static String hostOf(String origin) {
        String value = origin.trim();
        int schemeIdx = value.indexOf("://");
        if (schemeIdx >= 0) {
            value = value.substring(schemeIdx + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        // IPv6 形如 [::1]:5173
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            value = value.substring(0, colon);
        }
        return value;
    }

    /** 枚举本机所有 IPv4 地址（跳过回环）。 */
    private static Set<String> detectLocalIps() {
        Set<String> ips = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // 取不到网卡信息不影响服务启动，退化为"只用配置的白名单"
        }
        return ips;
    }

    /** 供 /api/health 展示：当前实际放行的本机地址（实时，便于排障）。 */
    public static List<String> localIps() {
        return new ArrayList<>(currentLocalIps());
    }

    /** 取本机网卡地址，带短 TTL 缓存（换网络后最多 5 秒自动跟上）。 */
    private static Set<String> currentLocalIps() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > CACHE_TTL_MILLIS || cachedIps.isEmpty()) {
            cachedIps = detectLocalIps();
            cachedAt = now;
        }
        return cachedIps;
    }
}
