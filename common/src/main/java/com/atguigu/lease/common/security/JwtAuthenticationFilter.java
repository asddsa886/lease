package com.atguigu.lease.common.security;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.filter.TraceContextFilter;
import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器。
 * <p>
 * 作用：
 * 1. 在请求进入 Controller 前统一处理 JWT 鉴权；
 * 2. 从请求头提取 token，并完成黑名单校验、JWT 解析与端类型校验；
 * 3. 构建 Spring Security 认证对象并放入 {@link SecurityContextHolder}；
 * 4. 将当前登录用户写入 request attribute，供日志链路读取；
 * 5. 认证失败时交给 {@link AuthenticationEntryPoint} 输出统一 JSON 响应。
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * 当前过滤器期望处理的客户端类型。
     * <p>
     * 例如 APP 端过滤器只能接受 APP token，ADMIN 端过滤器只能接受 ADMIN token。
     */
    private final TokenClientType expectedClientType;

    /**
     * token 黑名单服务，用于判断 token 是否已经主动失效。
     */
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * 认证失败统一入口，用于输出标准 JSON 错误响应。
     */
    private final AuthenticationEntryPoint authenticationEntryPoint;

    /**
     * 每次请求进入过滤器时执行的核心逻辑。
     * <p>
     * 执行顺序：
     * 1. 从请求头解析 token；
     * 2. 若请求未携带 token，则直接放行，由后续 Spring Security 规则决定是否拦截；
     * 3. 若当前上下文已存在认证信息，则不重复解析；
     * 4. 校验 token 是否在黑名单中；
     * 5. 解析 JWT 并校验 clientType；
     * 6. 构建 Authentication 放入 SecurityContext；
     * 7. 将登录用户信息写入 request attribute，方便日志打印；
     * 8. 若解析失败，则统一返回认证失败 JSON。
     *
     * @param request 当前 HTTP 请求对象，包含请求头和请求路径等信息
     * @param response 当前 HTTP 响应对象，用于认证失败时写出 JSON 响应
     * @param filterChain 过滤器链，用于继续放行后续过滤器和业务处理
     * @throws ServletException Servlet 规范保留异常
     * @throws IOException 当响应写出失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = JwtTokenResolver.resolve(request);
        if (token == null || token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (tokenBlacklistService.isBlacklisted(token)) {
                throw new JwtAuthenticationException(ResultCodeEnum.TOKEN_INVALID);
            }

            Claims claims = JwtUtil.parseToken(token);
            String clientType = claims.get("clientType", String.class);
            if (!expectedClientType.name().equals(clientType)) {
                throw new JwtAuthenticationException(ResultCodeEnum.TOKEN_INVALID);
            }

            Long userId = claims.get("userId", Long.class);
            String username = claims.get("username", String.class);
            LoginUser loginUser = new LoginUser(userId, username);
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + expectedClientType.name()));

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    loginUser,
                    token,
                    authorities
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            request.setAttribute(TraceContextFilter.ATTR_LOGIN_USER_ID, userId);
            request.setAttribute(TraceContextFilter.ATTR_LOGIN_USERNAME, username);

            filterChain.doFilter(request, response);
        } catch (JwtAuthenticationException e) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response, e);
        } catch (LeaseException e) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response, new JwtAuthenticationException(resolveTokenErrorCode(e)));
        }
    }

    /**
     * 将项目业务异常映射为 token 相关错误码。
     * <p>
     * 目前主要把 LeaseException 中的错误码收敛到：
     * 1. token 过期；
     * 2. token 非法。
     *
     * @param e JWT 解析过程中抛出的业务异常
     * @return 适合返回给认证入口的 token 错误码
     */
    private static ResultCodeEnum resolveTokenErrorCode(LeaseException e) {
        if (ResultCodeEnum.TOKEN_EXPIRED.getCode().equals(e.getCode())) {
            return ResultCodeEnum.TOKEN_EXPIRED;
        }
        if (ResultCodeEnum.TOKEN_INVALID.getCode().equals(e.getCode())) {
            return ResultCodeEnum.TOKEN_INVALID;
        }
        return ResultCodeEnum.TOKEN_INVALID;
    }
}
