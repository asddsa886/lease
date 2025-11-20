package com.atguigu.lease.common.utils;

import java.util.Random;

public class CodeUtil {
    public static String getRandomCode(Integer length) {
        StringBuilder builder = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < length; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }
}
