package com.atguigu.lease.web.app.service;

public interface SmsService {
    public void sendCode(String phone, String code);
}
