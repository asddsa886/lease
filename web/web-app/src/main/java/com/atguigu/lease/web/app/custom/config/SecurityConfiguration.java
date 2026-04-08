package com.atguigu.lease.web.app.custom.config;

import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.security.JsonAccessDeniedHandler;
import com.atguigu.lease.common.security.JsonAuthenticationEntryPoint;
import com.atguigu.lease.common.security.JwtAuthenticationFilter;
import com.atguigu.lease.common.security.TokenBlacklistService;
import com.atguigu.lease.common.security.TokenClientType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * App 端 Spring Security 配置类。
 * <p>
 * 作用：
 * 1. 定义 App 端的安全过滤器链；
 * 2. 声明哪些接口需要登录，哪些接口放行；
 * 3. 接入自定义 JWT 过滤器；
 * 4. 统一配置认证失败与权限不足时的 JSON 响应；
 * 5. 关闭 Session，明确采用 JWT 无状态认证。
 */
@Configuration
public class SecurityConfiguration {

    /**
     * 构建 App 端安全过滤器链。
     *
     * @param http Spring Security 的 HttpSecurity 配置入口
     * @param appJwtAuthenticationFilter App 端 JWT 认证过滤器
     * @param appAuthenticationEntryPoint App 端认证失败入口
     * @param objectMapper JSON 序列化工具，用于构造授权失败响应
     * @return 配置完成的 SecurityFilterChain
     * @throws Exception 当 Security 配置构建失败时抛出
     */
    @Bean
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http,
                                                      JwtAuthenticationFilter appJwtAuthenticationFilter,
                                                      AuthenticationEntryPoint appAuthenticationEntryPoint,
                                                      ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // 关闭 CSRF 保护，因为 App 端通常不使用 Cookie 进行认证
                .formLogin(AbstractHttpConfigurer::disable) // 关闭表单登录
                .httpBasic(AbstractHttpConfigurer::disable) // 关闭 HTTP Basic 认证
                .logout(AbstractHttpConfigurer::disable) // 关闭默认的注销功能
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 设置 Session 策略为无状态
                .authorizeHttpRequests(auth -> auth       // 配置接口访问权限
                        .requestMatchers(
                                "/app/login/**",
                                "/assistant.html",
                                "/doc.html",
                                "/v3/api-docs/**",
                                "/swagger-resources/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/webjars/**",
                                "/favicon.ico",
                                "/error"
                        ).permitAll() // 允许匿名访问登录接口和 Swagger 文档相关接口
                        .anyRequest().authenticated()   // 其他接口都需要认证
                )
                .exceptionHandling(ex -> ex // 配置认证失败和权限不足的处理器
                        .authenticationEntryPoint(appAuthenticationEntryPoint) // 认证失败时返回统一的 JSON 响应
                        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper, ResultCodeEnum.ILLEGAL_REQUEST)) // 权限不足时返回统一的 JSON 响应
                )
                .addFilterBefore(appJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class) // 将自定义 JWT 认证过滤器添加到 Spring Security 过滤链中，确保在用户名密码认证过滤器之前执行
                .securityContext(Customizer.withDefaults()); // 使用默认的 SecurityContext 配置
        return http.build();
    }

    /**
     * 声明 App 端认证失败入口。
     *
     * @param objectMapper JSON 序列化工具
     * @return App 端统一认证失败处理器
     */
    @Bean
    public AuthenticationEntryPoint appAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new JsonAuthenticationEntryPoint(objectMapper, ResultCodeEnum.APP_LOGIN_AUTH);
    }

    /**
     * 声明 App 端 JWT 认证过滤器。
     *
     * @param tokenBlacklistService token 黑名单服务，用于校验 token 是否已失效
     * @param appAuthenticationEntryPoint App 端认证失败入口
     * @return App 端 JWT 认证过滤器实例
     */
    @Bean
    public JwtAuthenticationFilter appJwtAuthenticationFilter(TokenBlacklistService tokenBlacklistService,
                                                              AuthenticationEntryPoint appAuthenticationEntryPoint) {
        return new JwtAuthenticationFilter(TokenClientType.APP, tokenBlacklistService, appAuthenticationEntryPoint);
    }

    /**
     * 提供一个占位的 UserDetailsService。
     * <p>
     * 当前项目采用 JWT 无状态认证，不走用户名密码登录过滤器，
     * 因此这里仅用于关闭 Spring Security 默认生成用户的行为。
     *
     * @return 一个始终抛出异常的 UserDetailsService
     */
    @Bean
    public UserDetailsService appUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("JWT-only authentication is enabled");
        };
    }
}
