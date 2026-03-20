package com.atguigu.lease.common.filter;

import com.atguigu.lease.common.login.LoginUserHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * P0 可观测性：traceId 注入 + 统一 access log
 * <p>
 * - 从请求头透传 X-Trace-Id（若无则生成）
 * - 写入 MDC，便于日志按 traceId 关联
 * - 响应头回写 X-Trace-Id
 * - 请求结束打印统一 access log（method/uri/status/cost/userId/ip/ua）
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceContextFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_ID_KEY = "traceId";

    /**
     * 为了避免拦截器里 ThreadLocal 在 afterCompletion 被 clear 后拿不到 userId，
     * 在拦截器 preHandle 阶段写入 request attribute，filter 最终在这里读取。
     */
    public static final String ATTR_LOGIN_USER_ID = "loginUserId";
    public static final String ATTR_LOGIN_USERNAME = "loginUsername";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String traceId = getOrCreateTraceId(request);
        MDC.put(MDC_TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        long startNs = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long costMs = (System.nanoTime() - startNs) / 1_000_000;

            String method = request.getMethod();
            String uri = request.getRequestURI();
            String query = request.getQueryString();
            if (query != null && !query.isBlank()) {
                uri = uri + "?" + query;
            }

            int status = response.getStatus();
            String ip = resolveClientIp(request);
            String ua = Optional.ofNullable(request.getHeader("User-Agent")).orElse("-");

            String userId = resolveLoginUserId(request);
            String username = resolveLoginUsername(request);

            log.info("access method={} uri={} status={} costMs={} traceId={} userId={} username={} ip={} ua={}",
                    method, uri, status, costMs, traceId, userId, username, ip, ua);

            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }

    private static String getOrCreateTraceId(HttpServletRequest request) {
        String fromHeader = request.getHeader(TRACE_ID_HEADER);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // 多级代理时取第一个非空 ip
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

    private static String resolveLoginUserId(HttpServletRequest request) {
        Object attr = request.getAttribute(ATTR_LOGIN_USER_ID);
        if (attr != null) {
            return String.valueOf(attr);
        }
        try {
            if (LoginUserHolder.get() != null) {
                return String.valueOf(LoginUserHolder.get().getId());
            }
        } catch (Exception ignored) {
            // ignore
        }
        return "-";
    }

    private static String resolveLoginUsername(HttpServletRequest request) {
        Object attr = request.getAttribute(ATTR_LOGIN_USERNAME);
        if (attr != null) {
            return String.valueOf(attr);
        }
        try {
            if (LoginUserHolder.get() != null) {
                return String.valueOf(LoginUserHolder.get().getUsername());
            }
        } catch (Exception ignored) {
            // ignore
        }
        return "-";
    }
}