package com.cdtubear.xpwechatcall.trigger;

import java.net.InetAddress;

/**
 * 来源 IP/CIDR 白名单，纯 Java 实现，便于单测。
 */
public final class IpWhitelist {

    private IpWhitelist() {
    }

    /**
     * @param remote     实际来源 IP
     * @param configured 逗号/分号/空白分隔的 IP 或 a.b.c.d/prefix；空串表示不限制
     */
    public static boolean allows(String remote, String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return true;
        }
        if (remote == null || remote.isEmpty()) {
            return false;
        }
        var entries = configured.trim().split("[,;\\s]+");
        for (var entry : entries) {
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.equals(remote)) {
                return true;
            }
            var slash = entry.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            try {
                var prefix = Integer.parseInt(entry.substring(slash + 1));
                if (prefix < 0 || prefix > 32) {
                    continue;
                }
                var network = InetAddress.getByName(entry.substring(0, slash));
                var address = InetAddress.getByName(remote);
                var mask = prefix == 0 ? 0 : (-1 << (32 - prefix));
                if ((ipv4ToInt(network.getHostAddress()) & mask)
                    == (ipv4ToInt(address.getHostAddress()) & mask)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 单条写错时忽略，不影响其它条目
            }
        }
        return false;
    }

    private static long ipv4ToInt(String value) throws Exception {
        var parts = value.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("not ipv4");
        }
        long result = 0;
        for (var part : parts) {
            var octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("invalid octet");
            }
            result = (result << 8) | octet;
        }
        return result;
    }
}
