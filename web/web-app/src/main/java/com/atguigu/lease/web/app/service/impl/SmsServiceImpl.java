package com.atguigu.lease.web.app.service.impl;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.filter.TraceContextFilter;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.sms.AliyunSMSProperties;
import com.atguigu.lease.web.app.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SmsServiceImpl implements SmsService {

    private static final String TEMPLATE_CODE = "100001";

    private final ObjectProvider<Client> clientProvider;
    private final AliyunSMSProperties aliyunSMSProperties;

    public SmsServiceImpl(ObjectProvider<Client> clientProvider, AliyunSMSProperties aliyunSMSProperties) {
        this.clientProvider = clientProvider;
        this.aliyunSMSProperties = aliyunSMSProperties;
    }

    @Override
    public void sendCode(String phone, String code) {
        String traceId = MDC.get(TraceContextFilter.MDC_TRACE_ID_KEY);

        if (aliyunSMSProperties.isMockEnabled()) {
            log.warn("TODO(local-dev): SMS send is mocked, phone={}, code={}, traceId={}", phone, code, traceId);
            return;
        }

        Client client = clientProvider.getIfAvailable();
        if (client == null) {
            log.warn("SMS client unavailable, phone={}, traceId={}", phone, traceId);
            throw new LeaseException(ResultCodeEnum.APP_SMS_CLIENT_INIT_ERROR);
        }

        SendSmsRequest request = new SendSmsRequest();
        request.setPhoneNumbers(phone);
        request.setSignName("閫熼€氫簰鑱旈獙璇佺爜");
        request.setTemplateCode(TEMPLATE_CODE);
        request.setTemplateParam("{\"code\":\"" + code + "\"}");

        try {
            client.sendSms(request);
        } catch (Exception e) {
            log.warn("SMS send failed, phone={}, templateCode={}, traceId={}", phone, TEMPLATE_CODE, traceId, e);
            throw new LeaseException(ResultCodeEnum.APP_SMS_SERVICE_ERROR);
        }
    }
}
