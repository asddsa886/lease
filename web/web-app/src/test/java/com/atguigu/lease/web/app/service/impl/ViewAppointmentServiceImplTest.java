package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.model.enums.AppointmentStatus;
import com.atguigu.lease.web.app.mapper.ViewAppointmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ViewAppointmentServiceImplTest {

    @Mock
    private ViewAppointmentMapper viewAppointmentMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock apartmentSlotLock;
    @Mock
    private RLock userSlotLock;
    @Mock
    private RLock recordLock;

    private ViewAppointmentServiceImpl service;

    @BeforeEach
    void setUp() throws InterruptedException {
        service = new ViewAppointmentServiceImpl();
        ReflectionTestUtils.setField(service, "viewAppointmentMapper", viewAppointmentMapper);
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(service, "baseMapper", viewAppointmentMapper);

        lenient().when(userSlotLock.getName()).thenReturn("lock-user");
        lenient().when(apartmentSlotLock.getName()).thenReturn("lock-apartment");
        lenient().when(recordLock.getName()).thenReturn("lock-record");
        lenient().when(userSlotLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(apartmentSlotLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(recordLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(userSlotLock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(apartmentSlotLock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(recordLock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void saveOrUpdateForCurrentUser_shouldRejectUserTimeConflict() {
        Date futureTime = new Date(System.currentTimeMillis() + 60_000);
        ViewAppointment appointment = new ViewAppointment();
        appointment.setApartmentId(11L);
        appointment.setAppointmentTime(futureTime);

        when(redissonClient.getLock("lock:app:appointment:user:8:" + futureTime.getTime())).thenReturn(userSlotLock);
        when(redissonClient.getLock("lock:app:appointment:apartment:11:" + futureTime.getTime())).thenReturn(apartmentSlotLock);
        when(viewAppointmentMapper.countWaitingDuplicate(8L, 11L, futureTime, null)).thenReturn(0);
        when(viewAppointmentMapper.countWaitingByUserAndTime(8L, futureTime, null)).thenReturn(1);

        LeaseException exception = assertThrows(LeaseException.class,
                () -> service.saveOrUpdateForCurrentUser(appointment, 8L));

        assertEquals(ResultCodeEnum.APP_APPOINTMENT_TIME_CONFLICT.getCode(), exception.getCode());
        verify(viewAppointmentMapper, never()).insert(any(ViewAppointment.class));
    }

    @Test
    void saveOrUpdateForCurrentUser_shouldRejectApartmentSlotConflict() {
        Date futureTime = new Date(System.currentTimeMillis() + 120_000);
        ViewAppointment appointment = new ViewAppointment();
        appointment.setApartmentId(11L);
        appointment.setAppointmentTime(futureTime);

        when(redissonClient.getLock("lock:app:appointment:user:8:" + futureTime.getTime())).thenReturn(userSlotLock);
        when(redissonClient.getLock("lock:app:appointment:apartment:11:" + futureTime.getTime())).thenReturn(apartmentSlotLock);
        when(viewAppointmentMapper.countWaitingDuplicate(8L, 11L, futureTime, null)).thenReturn(0);
        when(viewAppointmentMapper.countWaitingByUserAndTime(8L, futureTime, null)).thenReturn(0);
        when(viewAppointmentMapper.countWaitingByApartmentAndTime(11L, futureTime, null)).thenReturn(1);

        LeaseException exception = assertThrows(LeaseException.class,
                () -> service.saveOrUpdateForCurrentUser(appointment, 8L));

        assertEquals(ResultCodeEnum.APP_APARTMENT_APPOINTMENT_CONFLICT.getCode(), exception.getCode());
        verify(viewAppointmentMapper, never()).insert(any(ViewAppointment.class));
    }

    @Test
    void cancelForCurrentUser_shouldReturnCurrentRecordWhenAlreadyCanceled() {
        ViewAppointment appointment = new ViewAppointment();
        appointment.setId(21L);
        appointment.setUserId(8L);
        appointment.setAppointmentStatus(AppointmentStatus.CANCELED);

        when(redissonClient.getLock("lock:app:appointment:record:21")).thenReturn(recordLock);
        when(viewAppointmentMapper.selectById(21L)).thenReturn(appointment);

        ViewAppointment result = service.cancelForCurrentUser(21L, 8L);

        assertSame(appointment, result);
        verify(viewAppointmentMapper, never()).cancelWaitingByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void rescheduleForCurrentUser_shouldRejectViewedAppointment() {
        Date targetTime = new Date(System.currentTimeMillis() + 180_000);
        ViewAppointment appointment = new ViewAppointment();
        appointment.setId(31L);
        appointment.setUserId(8L);
        appointment.setApartmentId(12L);
        appointment.setAppointmentStatus(AppointmentStatus.VIEWED);
        appointment.setAppointmentTime(new Date(System.currentTimeMillis() + 60_000));

        when(viewAppointmentMapper.selectById(31L)).thenReturn(appointment);
        when(redissonClient.getLock("lock:app:appointment:record:31")).thenReturn(recordLock);
        when(redissonClient.getLock("lock:app:appointment:user:8:" + targetTime.getTime())).thenReturn(userSlotLock);
        when(redissonClient.getLock("lock:app:appointment:apartment:12:" + targetTime.getTime())).thenReturn(apartmentSlotLock);

        LeaseException exception = assertThrows(LeaseException.class,
                () -> service.rescheduleForCurrentUser(31L, targetTime, 8L));

        assertEquals(ResultCodeEnum.APP_APPOINTMENT_STATUS_ERROR.getCode(), exception.getCode());
        verify(viewAppointmentMapper, never()).updateById(any(ViewAppointment.class));
    }

    @Test
    void rescheduleForCurrentUser_shouldReturnCurrentRecordWhenTimeUnchanged() {
        Date targetTime = new Date(System.currentTimeMillis() + 240_000);
        ViewAppointment appointment = new ViewAppointment();
        appointment.setId(41L);
        appointment.setUserId(8L);
        appointment.setApartmentId(12L);
        appointment.setAppointmentStatus(AppointmentStatus.WAITING);
        appointment.setAppointmentTime(targetTime);

        when(viewAppointmentMapper.selectById(41L)).thenReturn(appointment);
        when(redissonClient.getLock("lock:app:appointment:record:41")).thenReturn(recordLock);
        when(redissonClient.getLock("lock:app:appointment:user:8:" + targetTime.getTime())).thenReturn(userSlotLock);
        when(redissonClient.getLock("lock:app:appointment:apartment:12:" + targetTime.getTime())).thenReturn(apartmentSlotLock);

        ViewAppointment result = service.rescheduleForCurrentUser(41L, targetTime, 8L);

        assertSame(appointment, result);
        verify(viewAppointmentMapper, never()).updateById(any(ViewAppointment.class));
        verify(viewAppointmentMapper, never()).countWaitingByUserAndTime(eq(8L), any(Date.class), eq(41L));
    }
}
