package com.atguigu.lease.common.security;

/**
 * token 客户端类型枚举。
 * <p>
 * 作用：
 * 1. 在签发 JWT 时标记 token 属于哪个端；
 * 2. 在认证过滤器中校验当前 token 是否访问了正确的业务端；
 * 3. 避免 APP 端 token 与 Admin 端 token 混用。
 */
public enum TokenClientType {
    /**
     * 租客端 / App 端 token。
     */
    APP,

    /**
     * 后台管理端 / Admin 端 token。
     */
    ADMIN
}
