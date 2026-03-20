package com.atguigu.lease.common.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 获取客户端 IP（兼容反向代理场景）
 */
public class IpUtil {

    private IpUtil() {
    }

    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "-";
        }

        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            for (String part : xff.split(",")) {
                String ip = part.trim();
                if (!ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                    return ip;
                }
            }
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp.trim();
        }

        return request.getRemoteAddr();
    }
}