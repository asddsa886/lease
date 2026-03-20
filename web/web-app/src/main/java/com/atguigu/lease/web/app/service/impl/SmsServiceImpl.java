package com.atguigu.lease.web.app.service.impl;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.filter.TraceContextFilter;
import com.atguigu.lease.web.app.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SmsServiceImpl implements SmsService {

    @Autowired
    private Client client;

    @Override
    public void sendCode(String phone, String code) {
        SendSmsRequest request = new SendSmsRequest();
        request.setPhoneNumbers(phone);
        request.setSignName("速通互联验证码");
        request.setTemplateCode("100001");
        request.setTemplateParam("{\"code\":\"" + code + "\"}");

        try {
            client.sendSms(request);
        } catch (Exception e) {
            // P1-稳定性：第三方依赖失败收敛为业务异常，避免 500 放大
            String traceId = MDC.get(TraceContextFilter.MDC_TRACE_ID_KEY);
            log.warn("SMS send failed, phone={}, templateCode={}, traceId={}", phone, "100001", traceId, e);
            throw new LeaseException(ResultCodeEnum.APP_SMS_SERVICE_ERROR);
        }
    }
}
