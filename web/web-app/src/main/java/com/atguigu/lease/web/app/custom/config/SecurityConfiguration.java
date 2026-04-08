package com.atguigu.lease.web.app.custom.config;

import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.security.JsonAccessDeniedHandler;
import com.atguigu.lease.common.security.JsonAuthenticationEntryPoint;
import com.atguigu.lease.common.security.JwtAuthenticationFilter;
import com.atguigu.lease.common.security.TokenBlacklistService;
import com.atguigu.lease.common.security.TokenClientType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http,
                                                      JwtAuthenticationFilter appJwtAuthenticationFilter,
                                                      AuthenticationEntryPoint appAuthenticationEntryPoint,
                                                      ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
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
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(appAuthenticationEntryPoint)
                        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper, ResultCodeEnum.ILLEGAL_REQUEST))
                )
                .addFilterBefore(appJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .securityContext(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint appAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new JsonAuthenticationEntryPoint(objectMapper, ResultCodeEnum.APP_LOGIN_AUTH);
    }

    @Bean
    public JwtAuthenticationFilter appJwtAuthenticationFilter(TokenBlacklistService tokenBlacklistService,
                                                              AuthenticationEntryPoint appAuthenticationEntryPoint) {
        return new JwtAuthenticationFilter(TokenClientType.APP, tokenBlacklistService, appAuthenticationEntryPoint);
    }

    @Bean
    public UserDetailsService appUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("JWT-only authentication is enabled");
        };
    }
}
