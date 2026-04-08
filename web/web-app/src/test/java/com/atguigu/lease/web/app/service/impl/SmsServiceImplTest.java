package com.atguigu.lease.web.app.service.impl;

import com.aliyun.dysmsapi20170525.Client;
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.sms.AliyunSMSProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsServiceImplTest {

    @Mock
    private ObjectProvider<Client> clientProvider;

    @Test
    void sendCode_shouldSkipRealSmsWhenMockEnabled() {
        AliyunSMSProperties properties = new AliyunSMSProperties();
        properties.setMockEnabled(true);
        SmsServiceImpl smsService = new SmsServiceImpl(clientProvider, properties);

        assertDoesNotThrow(() -> smsService.sendCode("17503976585", "123456"));
        verify(clientProvider, never()).getIfAvailable();
    }

    @Test
    void sendCode_shouldThrowWhenMockDisabledAndClientMissing() {
        AliyunSMSProperties properties = new AliyunSMSProperties();
        properties.setMockEnabled(false);
        when(clientProvider.getIfAvailable()).thenReturn(null);
        SmsServiceImpl smsService = new SmsServiceImpl(clientProvider, properties);

        LeaseException exception = assertThrows(LeaseException.class,
                () -> smsService.sendCode("17503976585", "123456"));

        assertEquals(ResultCodeEnum.APP_SMS_CLIENT_INIT_ERROR.getCode(), exception.getCode());
    }
}
