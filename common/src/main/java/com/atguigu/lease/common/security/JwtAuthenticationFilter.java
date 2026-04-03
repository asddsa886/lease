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

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenClientType expectedClientType;
    private final TokenBlacklistService tokenBlacklistService;
    private final AuthenticationEntryPoint authenticationEntryPoint;

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
