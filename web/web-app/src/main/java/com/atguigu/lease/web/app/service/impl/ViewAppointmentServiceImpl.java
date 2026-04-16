package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.model.enums.AppointmentStatus;
import com.atguigu.lease.web.app.mapper.ViewAppointmentMapper;
import com.atguigu.lease.web.app.service.ViewAppointmentService;
import com.atguigu.lease.web.app.vo.appointment.AppointmentItemVo;
import com.atguigu.lease.web.app.vo.graph.GraphVo;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author liubo
 * @description 针对表【view_appointment(预约看房信息表)】的数据库操作Service实现
 * @createDate 2023-07-26 11:12:39
 */
@Service
public class ViewAppointmentServiceImpl extends ServiceImpl<ViewAppointmentMapper, ViewAppointment>
        implements ViewAppointmentService {

    private static final String APPOINTMENT_RECORD_LOCK_PREFIX = "lock:app:appointment:record:";
    private static final String APPOINTMENT_USER_SLOT_LOCK_PREFIX = "lock:app:appointment:user:";
    private static final String APPOINTMENT_APARTMENT_SLOT_LOCK_PREFIX = "lock:app:appointment:apartment:";
    private static final long LOCK_WAIT_SECONDS = 2L;
    private static final long LOCK_LEASE_SECONDS = 8L;

    @Autowired
    private ViewAppointmentMapper viewAppointmentMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public List<AppointmentItemVo> getDetailByUserId(Long id) {
        List<AppointmentItemVo> items = viewAppointmentMapper.getDetailByUserId(id);
        if (items == null || items.isEmpty()) {
            return items;
        }

        // 批量查询图片并按 apartmentId 分组，避免 N+1
        List<Long> apartmentIds = items.stream()
                .map(AppointmentItemVo::getApartmentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (apartmentIds.isEmpty()) {
            return items;
        }

        List<GraphVo> graphs = viewAppointmentMapper.listApartmentGraphsByApartmentIds(apartmentIds);
        Map<Long, List<GraphVo>> graphMap = (graphs == null ? Collections.<GraphVo>emptyList() : graphs)
                .stream()
                .filter(g -> g.getApartmentId() != null)
                .collect(Collectors.groupingBy(GraphVo::getApartmentId));

        for (AppointmentItemVo item : items) {
            if (item.getApartmentId() == null) {
                item.setGraphVoList(Collections.emptyList());
                continue;
            }
            item.setGraphVoList(graphMap.getOrDefault(item.getApartmentId(), Collections.emptyList()));
        }
        return items;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOrUpdateForCurrentUser(ViewAppointment viewAppointment, Long currentUserId) {
        if (currentUserId == null || viewAppointment == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
        if (viewAppointment.getApartmentId() == null || viewAppointment.getAppointmentTime() == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        ensureAppointmentTimeValid(viewAppointment.getAppointmentTime());
        if (viewAppointment.getId() == null) {
            executeWithSlotLocks(currentUserId, viewAppointment.getApartmentId(), viewAppointment.getAppointmentTime(), () -> {
                ensureNoCreateConflict(currentUserId, viewAppointment.getApartmentId(), viewAppointment.getAppointmentTime());
                viewAppointment.setUserId(currentUserId);
                viewAppointment.setAppointmentStatus(AppointmentStatus.WAITING);
                viewAppointmentMapper.insert(viewAppointment);
            });
            return;
        }

        ViewAppointment db = requireOwnedAppointment(viewAppointment.getId(), currentUserId);
        if (db.getAppointmentStatus() != AppointmentStatus.WAITING) {
            throw new LeaseException(ResultCodeEnum.APP_APPOINTMENT_STATUS_ERROR);
        }

        viewAppointment.setUserId(db.getUserId());
        viewAppointment.setAppointmentStatus(db.getAppointmentStatus());
        viewAppointment.setApartmentId(db.getApartmentId());

        executeWithRecordAndSlotLocks(db.getId(), currentUserId, db.getApartmentId(), viewAppointment.getAppointmentTime(), () -> {
            ViewAppointment fresh = requireOwnedAppointment(db.getId(), currentUserId);
            if (fresh.getAppointmentStatus() != AppointmentStatus.WAITING) {
                throw new LeaseException(ResultCodeEnum.APP_APPOINTMENT_STATUS_ERROR);
            }
            ensureNoUpdateConflict(currentUserId, fresh.getApartmentId(), viewAppointment.getAppointmentTime(), fresh.getId());
            viewAppointmentMapper.updateById(viewAppointment);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ViewAppointment cancelForCurrentUser(Long appointmentId, Long currentUserId) {
        if (appointmentId == null || currentUserId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        return executeWithRecordLock(appointmentId, () -> {
            ViewAppointment db = requireOwnedAppointment(appointmentId, currentUserId);
            if (db.getAppointmentStatus() == AppointmentStatus.CANCELED) {
                return db;
            }
            if (db.getAppointmentStatus() != AppointmentStatus.WAITING) {
                throw new LeaseException(ResultCodeEnum.APP_APPOINTMENT_STATUS_ERROR);
            }
            int updated = viewAppointmentMapper.cancelWaitingByIdAndUserId(appointmentId, currentUserId);
            if (updated == 0) {
                ViewAppointment latest = requireOwnedAppointment(appointmentId, currentUserId);
                if (latest.getAppointmentStatus() == AppointmentStatus.CANCELED) {
                    return latest;
                }
                throw new LeaseException(ResultCodeEnum.APP_APPOINTMENT_STATUS_ERROR);
            }
            db.setAppointmentStatus(AppointmentStatus.CANCELED);
            return db;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ViewAppointment rescheduleForCurrentUser(Long appointmentId, Date appointmentTime, Long currentUserId) {
        if (appointmentId == null || appointmentTime == null || currentUserId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        ensureAppointmentTimeValid(appointmentTime);
        ViewAppointment db = requireOwnedAppointment(appointmentId, currentUserId);
        return executeWithRecordAndSlotLocks(db.getId(), currentUserId, db.getApartmentId(), appointmentTime, () -> {
            ViewAppointment fresh = requireOwnedAppointment(appointmentId, currentUserId);
            if (fresh.getAppointmentStatus() != AppointmentStatus.WAITING) {
                throw new LeaseException(ResultCodeEnum.APP_APPOINTMENT_STATUS_ERROR);
            }
            if (fresh.getAppointmentTime() != null && fresh.getAppointmentTime().getTime() == appointmentTime.getTime()) {
                return fresh;
            }
            ensureNoUpdateConflict(currentUserId, fresh.getApartmentId(), appointmentTime, fresh.getId());
            fresh.setAppointmentTime(appointmentTime);
            viewAppointmentMapper.updateById(fresh);
            return fresh;
        });
    }

    private void ensureAppointmentTimeValid(Date appointmentTime) {
        if (appointmentTime == null || !appointmentTime.after(new Date())) {
            throw new LeaseException(ResultCodeEnum.APP_APPOINTMENT_TIME_INVALID);
        }
    }

    private void ensureNoCreateConflict(Long userId, Long apartmentId, Date appointmentTime) {
        if (viewAppointmentMapper.countWaitingDuplicate(userId, apartmentId, appointmentTime, null) > 0) {
            throw new LeaseException(ResultCodeEnum.REPEAT_SUBMIT.getCode(), "相同预约已存在，请勿重复提交");
        }
        ensureNoUpdateConflict(userId, apartmentId, appointmentTime, null);
    }

    private void ensureNoUpdateConflict(Long userId, Long apartmentId, Date appointmentTime, Long excludeId) {
        if (viewAppointmentMapper.countWaitingByUserAndTime(userId, appointmentTime, excludeId) > 0) {
            throw new LeaseException(ResultCodeEnum.APP_APPOINTMENT_TIME_CONFLICT);
        }
        if (viewAppointmentMapper.countWaitingByApartmentAndTime(apartmentId, appointmentTime, excludeId) > 0) {
            throw new LeaseException(ResultCodeEnum.APP_APARTMENT_APPOINTMENT_CONFLICT);
        }
    }

    private ViewAppointment requireOwnedAppointment(Long appointmentId, Long currentUserId) {
        ViewAppointment appointment = viewAppointmentMapper.selectById(appointmentId);
        if (appointment == null || Byte.valueOf((byte) 1).equals(appointment.getIsDeleted())) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
        if (!currentUserId.equals(appointment.getUserId())) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }
        return appointment;
    }

    private void executeWithSlotLocks(Long userId, Long apartmentId, Date appointmentTime, Runnable action) {
        List<RLock> locks = acquireLocks(List.of(
                userSlotLock(userId, appointmentTime),
                apartmentSlotLock(apartmentId, appointmentTime)
        ));
        try {
            action.run();
        } finally {
            unlockAll(locks);
        }
    }

    private <T> T executeWithRecordAndSlotLocks(Long appointmentId,
                                                Long userId,
                                                Long apartmentId,
                                                Date appointmentTime,
                                                ThrowingSupplier<T> action) {
        return executeWithRecordLock(appointmentId, () -> {
            List<RLock> slotLocks = acquireLocks(List.of(
                    userSlotLock(userId, appointmentTime),
                    apartmentSlotLock(apartmentId, appointmentTime)
            ));
            try {
                return action.get();
            } finally {
                unlockAll(slotLocks);
            }
        });
    }

    private void executeWithRecordAndSlotLocks(Long appointmentId,
                                               Long userId,
                                               Long apartmentId,
                                               Date appointmentTime,
                                               Runnable action) {
        executeWithRecordAndSlotLocks(appointmentId, userId, apartmentId, appointmentTime, () -> {
            action.run();
            return null;
        });
    }

    private <T> T executeWithRecordLock(Long appointmentId, ThrowingSupplier<T> action) {
        List<RLock> locks = acquireLocks(List.of(recordLock(appointmentId)));
        try {
            return action.get();
        } finally {
            unlockAll(locks);
        }
    }

    private List<RLock> acquireLocks(List<RLock> locks) {
        List<RLock> orderedLocks = new ArrayList<>(locks);
        orderedLocks.sort(Comparator.comparing(RLock::getName));
        List<RLock> acquired = new ArrayList<>(orderedLocks.size());
        try {
            for (RLock lock : orderedLocks) {
                boolean locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
                if (!locked) {
                    throw new LeaseException(ResultCodeEnum.APP_APPOINTMENT_BUSY);
                }
                acquired.add(lock);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LeaseException(ResultCodeEnum.APP_APPOINTMENT_BUSY);
        } catch (RuntimeException e) {
            unlockAll(acquired);
            throw e;
        }
    }

    private void unlockAll(List<RLock> locks) {
        for (int i = locks.size() - 1; i >= 0; i--) {
            RLock lock = locks.get(i);
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private RLock recordLock(Long appointmentId) {
        return redissonClient.getLock(APPOINTMENT_RECORD_LOCK_PREFIX + appointmentId);
    }

    private RLock userSlotLock(Long userId, Date appointmentTime) {
        return redissonClient.getLock(APPOINTMENT_USER_SLOT_LOCK_PREFIX + userId + ":" + appointmentTime.getTime());
    }

    private RLock apartmentSlotLock(Long apartmentId, Date appointmentTime) {
        return redissonClient.getLock(APPOINTMENT_APARTMENT_SLOT_LOCK_PREFIX + apartmentId + ":" + appointmentTime.getTime());
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }
}
