package com.atguigu.lease.common.constant.RedisConstant;

public class RedisConstant {
    public static final String ADMIN_LOGIN_PREFIX = "admin:login";
    public static final String ADMIN_LOGIN_CAPTCHA_TTL_SEC = "60";
    public static final String APP_LOGIN_PREFIX = "app:login:";
    public static final String JWT_TOKEN_BLACKLIST_PREFIX = "auth:jwt:blacklist:";
    public static final Integer APP_LOGIN_CODE_RESEND_TIME_SEC = 60;
    public static final Integer APP_LOGIN_CODE_TTL_SEC = 60 * 10;

    /**
     * 热数据缓存 Key 规范（读多写少）
     * - region：省/市/区县
     * - room：支付方式/租期（按 roomId 维度）
     */
    public static final String APP_REGION_PROVINCE_LIST_KEY = "app:region:province:list";
    public static final String APP_REGION_CITY_LIST_BY_PROVINCE_KEY_PREFIX = "app:region:city:list:province:";
    public static final String APP_REGION_DISTRICT_LIST_BY_CITY_KEY_PREFIX = "app:region:district:list:city:";

    public static final String APP_PAYMENT_TYPE_LIST_BY_ROOM_KEY_PREFIX = "app:payment:type:list:room:";
    public static final String APP_LEASE_TERM_LIST_BY_ROOM_KEY_PREFIX = "app:lease:term:list:room:";

    /**
     * 详情页聚合数据缓存 key
     */
    public static final String APP_APARTMENT_DETAIL_KEY_PREFIX = "app:apartment:detail:";
    public static final String APP_ROOM_DETAIL_KEY_PREFIX = "app:room:detail:";

    /**
     * 热数据缓存 TTL（秒）
     * 说明：热数据可设置较长 TTL，实际写入时建议附带随机抖动（例如 0~300s）防止缓存雪崩。
     */
    public static final long HOT_DATA_CACHE_TTL_SEC = 60 * 60 * 6; // 6h
    public static final long HOT_DATA_NULL_CACHE_TTL_SEC = 60; // 空结果短缓存，防穿透
}
