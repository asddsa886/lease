package com.atguigu.lease.common.utils;

/**
 * 分页参数兜底与上限保护。
 * <p>
 * 目标：
 * 1) 避免 size 被恶意/误用传成超大导致 OOM 或 DB 压力
 * 2) 为所有分页接口提供统一的默认值与边界处理
 */
public final class PageParamUtils {

    /** 默认页码（从 1 开始） */
    public static final long DEFAULT_CURRENT = 1L;
    /** 默认每页大小 */
    public static final long DEFAULT_SIZE = 20L;
    /** 每页最大大小 */
    public static final long MAX_SIZE = 200L;

    private PageParamUtils() {
    }

    public static long current(Long current) {
        if (current == null || current < 1) {
            return DEFAULT_CURRENT;
        }
        return current;
    }

    public static long size(Long size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
