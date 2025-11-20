package com.atguigu.lease.common.login;

public class LoginUserHolder {

    public static ThreadLocal<LoginUser> threadLocal = new ThreadLocal<>();

    public static void set(LoginUser user) {
        threadLocal.set(user);
    }
    public static LoginUser get() {
        return threadLocal.get();
    }

    public static void clear() {
        threadLocal.remove();
    }
}
