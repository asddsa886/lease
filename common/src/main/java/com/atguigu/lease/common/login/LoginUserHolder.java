package com.atguigu.lease.common.login;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class LoginUserHolder {

    public static ThreadLocal<LoginUser> threadLocal = new ThreadLocal<>();

    public static void set(LoginUser user) {
        threadLocal.set(user);
    }
    public static LoginUser get() {
        LoginUser loginUser = threadLocal.get();
        if (loginUser != null) {
            return loginUser;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof LoginUser currentUser) {
            return currentUser;
        }
        return null;
    }

    public static void clear() {
        threadLocal.remove();
    }
}
