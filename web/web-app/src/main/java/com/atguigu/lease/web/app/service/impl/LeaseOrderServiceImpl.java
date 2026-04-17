package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.mq.publisher.LeaseOrderEventPublisher;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.ApartmentInfo;
import com.atguigu.lease.model.entity.LeaseAgreement;
import com.atguigu.lease.model.entity.LeaseOrder;
import com.atguigu.lease.model.entity.LeaseTerm;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.model.entity.RoomInfo;
import com.atguigu.lease.model.entity.RoomLeaseTerm;
import com.atguigu.lease.model.entity.RoomPaymentType;
import com.atguigu.lease.model.entity.UserInfo;
import com.atguigu.lease.model.enums.LeaseOrderStatus;
import com.atguigu.lease.model.enums.LeaseStatus;
import com.atguigu.lease.model.enums.ReleaseStatus;
import com.atguigu.lease.web.app.mapper.ApartmentInfoMapper;
import com.atguigu.lease.web.app.mapper.LeaseAgreementMapper;
import com.atguigu.lease.web.app.mapper.LeaseOrderMapper;
import com.atguigu.lease.web.app.mapper.LeaseTermMapper;
import com.atguigu.lease.web.app.mapper.PaymentTypeMapper;
import com.atguigu.lease.web.app.mapper.RoomInfoMapper;
import com.atguigu.lease.web.app.mapper.RoomLeaseTermMapper;
import com.atguigu.lease.web.app.mapper.RoomPaymentTypeMapper;
import com.atguigu.lease.web.app.mapper.UserInfoMapper;
import com.atguigu.lease.web.app.service.LeaseOrderService;
import com.atguigu.lease.web.app.vo.order.LeaseOrderDetailVo;
import com.atguigu.lease.web.app.vo.order.LeaseOrderItemVo;
import com.atguigu.lease.web.app.vo.order.LeaseOrderSubmitVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class LeaseOrderServiceImpl extends ServiceImpl<LeaseOrderMapper, LeaseOrder> implements LeaseOrderService {

    private static final String ROOM_LOCK_PREFIX = "lock:lease:order:room:";
    private static final String ORDER_LOCK_PREFIX = "lock:lease:order:record:";
    private static final long LOCK_WAIT_SECONDS = 2L;
    private static final long LOCK_LEASE_SECONDS = 8L;

    private final LeaseOrderMapper leaseOrderMapper;
    private final RoomInfoMapper roomInfoMapper;
    private final RoomLeaseTermMapper roomLeaseTermMapper;
    private final RoomPaymentTypeMapper roomPaymentTypeMapper;
    private final LeaseTermMapper leaseTermMapper;
    private final PaymentTypeMapper paymentTypeMapper;
    private final ApartmentInfoMapper apartmentInfoMapper;
    private final UserInfoMapper userInfoMapper;
    private final LeaseAgreementMapper leaseAgreementMapper;
    private final RedissonClient redissonClient;
    private final LeaseOrderEventPublisher leaseOrderEventPublisher;

    @Value("${lease.mq.order-timeout-ttl-ms:900000}")
    private long orderTimeoutTtlMs;

    public LeaseOrderServiceImpl(LeaseOrderMapper leaseOrderMapper,
                                 RoomInfoMapper roomInfoMapper,
                                 RoomLeaseTermMapper roomLeaseTermMapper,
                                 RoomPaymentTypeMapper roomPaymentTypeMapper,
                                 LeaseTermMapper leaseTermMapper,
                                 PaymentTypeMapper paymentTypeMapper,
                                 ApartmentInfoMapper apartmentInfoMapper,
                                 UserInfoMapper userInfoMapper,
                                 LeaseAgreementMapper leaseAgreementMapper,
                                 RedissonClient redissonClient,
                                 ObjectProvider<LeaseOrderEventPublisher> leaseOrderEventPublisherProvider) {
        this.leaseOrderMapper = leaseOrderMapper;
        this.roomInfoMapper = roomInfoMapper;
        this.roomLeaseTermMapper = roomLeaseTermMapper;
        this.roomPaymentTypeMapper = roomPaymentTypeMapper;
        this.leaseTermMapper = leaseTermMapper;
        this.paymentTypeMapper = paymentTypeMapper;
        this.apartmentInfoMapper = apartmentInfoMapper;
        this.userInfoMapper = userInfoMapper;
        this.leaseAgreementMapper = leaseAgreementMapper;
        this.redissonClient = redissonClient;
        this.leaseOrderEventPublisher = leaseOrderEventPublisherProvider.getIfAvailable();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submit(LeaseOrderSubmitVo submitVo, Long currentUserId) {
        if (submitVo == null || currentUserId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        UserInfo userInfo = userInfoMapper.selectById(currentUserId);
        RoomInfo roomInfo = roomInfoMapper.selectById(submitVo.getRoomId());
        LeaseTerm leaseTerm = leaseTermMapper.selectById(submitVo.getLeaseTermId());
        PaymentType paymentType = paymentTypeMapper.selectById(submitVo.getPaymentTypeId());

        validateSubmitBasics(submitVo, userInfo, roomInfo, leaseTerm, paymentType);

        return executeWithLocks(List.of(roomLock(submitVo.getRoomId())), () -> {
            ensureRoomCanCreateOrder(submitVo.getRoomId());

            LeaseOrder order = new LeaseOrder();
            order.setOrderNo(generateOrderNo(currentUserId));
            order.setUserId(currentUserId);
            order.setPhone(userInfo.getPhone());
            order.setName(resolveOrderName(userInfo));
            order.setApartmentId(roomInfo.getApartmentId());
            order.setRoomId(roomInfo.getId());
            order.setLeaseStartDate(submitVo.getLeaseStartDate());
            order.setLeaseEndDate(calculateLeaseEndDate(submitVo.getLeaseStartDate(), leaseTerm.getMonthCount()));
            order.setLeaseTermId(leaseTerm.getId());
            order.setRent(roomInfo.getRent());
            order.setDeposit(roomInfo.getRent());
            order.setPaymentTypeId(paymentType.getId());
            order.setStatus(LeaseOrderStatus.PENDING_PAYMENT);
            order.setAdditionalInfo(submitVo.getAdditionalInfo());
            order.setExpireTime(Date.from(Instant.now().plusMillis(orderTimeoutTtlMs)));

            leaseOrderMapper.insert(order);
            publishCreated(order);
            return order.getId();
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void payById(Long id, Long currentUserId) {
        if (id == null || currentUserId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        executeWithLocks(List.of(orderLock(id)), () -> {
            LeaseOrder order = requireOwnedOrder(id, currentUserId);
            if (order.getStatus() != LeaseOrderStatus.PENDING_PAYMENT) {
                throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_STATUS_ERROR);
            }

            LambdaUpdateWrapper<LeaseOrder> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(LeaseOrder::getId, id)
                    .eq(LeaseOrder::getUserId, currentUserId)
                    .eq(LeaseOrder::getStatus, LeaseOrderStatus.PENDING_PAYMENT)
                    .set(LeaseOrder::getStatus, LeaseOrderStatus.PAID);
            boolean updated = this.update(updateWrapper);
            if (!updated) {
                throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_STATUS_ERROR);
            }

            publishStatusChanged(order, null, LeaseOrderStatus.PAID);
            return null;
        });
    }

    @Override
    public List<LeaseOrderItemVo> listItemByCurrentUser(Long currentUserId) {
        if (currentUserId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        LambdaQueryWrapper<LeaseOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(LeaseOrder::getUserId, currentUserId)
                .orderByDesc(LeaseOrder::getCreateTime, LeaseOrder::getId);
        List<LeaseOrder> orders = leaseOrderMapper.selectList(queryWrapper);
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }

        Map<Long, ApartmentInfo> apartmentMap = apartmentMap(orders);
        Map<Long, RoomInfo> roomMap = roomMap(orders);

        return orders.stream().map(order -> {
            LeaseOrderItemVo itemVo = new LeaseOrderItemVo();
            itemVo.setId(order.getId());
            itemVo.setOrderNo(order.getOrderNo());
            itemVo.setRent(order.getRent());
            itemVo.setStatus(order.getStatus());
            itemVo.setExpireTime(order.getExpireTime());
            itemVo.setAgreementId(order.getAgreementId());
            itemVo.setCreateTime(order.getCreateTime());

            ApartmentInfo apartmentInfo = apartmentMap.get(order.getApartmentId());
            if (apartmentInfo != null) {
                itemVo.setApartmentName(apartmentInfo.getName());
            }
            RoomInfo roomInfo = roomMap.get(order.getRoomId());
            if (roomInfo != null) {
                itemVo.setRoomNumber(roomInfo.getRoomNumber());
            }
            return itemVo;
        }).toList();
    }

    @Override
    public LeaseOrderDetailVo getDetailById(Long id, Long currentUserId) {
        LeaseOrder order = requireOwnedOrder(id, currentUserId);

        LeaseOrderDetailVo detailVo = new LeaseOrderDetailVo();
        BeanUtils.copyProperties(order, detailVo);
        detailVo.setApartmentInfo(apartmentInfoMapper.selectById(order.getApartmentId()));
        detailVo.setRoomInfo(roomInfoMapper.selectById(order.getRoomId()));
        detailVo.setPaymentType(paymentTypeMapper.selectById(order.getPaymentTypeId()));
        detailVo.setLeaseTerm(leaseTermMapper.selectById(order.getLeaseTermId()));
        if (order.getAgreementId() != null) {
            detailVo.setAgreementInfo(leaseAgreementMapper.selectById(order.getAgreementId()));
        }
        return detailVo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelById(Long id, Long currentUserId) {
        if (id == null || currentUserId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        executeWithLocks(List.of(orderLock(id)), () -> {
            LeaseOrder order = requireOwnedOrder(id, currentUserId);
            if (order.getStatus() != LeaseOrderStatus.PENDING_PAYMENT) {
                throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_STATUS_ERROR);
            }

            LambdaUpdateWrapper<LeaseOrder> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(LeaseOrder::getId, id)
                    .eq(LeaseOrder::getUserId, currentUserId)
                    .eq(LeaseOrder::getStatus, LeaseOrderStatus.PENDING_PAYMENT)
                    .set(LeaseOrder::getStatus, LeaseOrderStatus.CANCELED);
            boolean updated = this.update(updateWrapper);
            if (!updated) {
                throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_STATUS_ERROR);
            }

            publishStatusChanged(order, null, LeaseOrderStatus.CANCELED);
            return null;
        });
    }

    private void validateSubmitBasics(LeaseOrderSubmitVo submitVo,
                                      UserInfo userInfo,
                                      RoomInfo roomInfo,
                                      LeaseTerm leaseTerm,
                                      PaymentType paymentType) {
        if (userInfo == null || roomInfo == null || leaseTerm == null || paymentType == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
        if (roomInfo.getIsRelease() != ReleaseStatus.RELEASED) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }

        LocalDate startDate = toLocalDate(submitVo.getLeaseStartDate());
        if (!startDate.isAfter(LocalDate.now())) {
            throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_START_DATE_INVALID);
        }

        LambdaQueryWrapper<RoomLeaseTerm> leaseTermWrapper = new LambdaQueryWrapper<>();
        leaseTermWrapper.eq(RoomLeaseTerm::getRoomId, submitVo.getRoomId())
                .eq(RoomLeaseTerm::getLeaseTermId, submitVo.getLeaseTermId());
        if (roomLeaseTermMapper.selectCount(leaseTermWrapper) == 0) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        LambdaQueryWrapper<RoomPaymentType> paymentTypeWrapper = new LambdaQueryWrapper<>();
        paymentTypeWrapper.eq(RoomPaymentType::getRoomId, submitVo.getRoomId())
                .eq(RoomPaymentType::getPaymentTypeId, submitVo.getPaymentTypeId());
        if (roomPaymentTypeMapper.selectCount(paymentTypeWrapper) == 0) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
    }

    private void ensureRoomCanCreateOrder(Long roomId) {
        LambdaQueryWrapper<LeaseOrder> orderWrapper = new LambdaQueryWrapper<>();
        orderWrapper.eq(LeaseOrder::getRoomId, roomId)
                .in(LeaseOrder::getStatus,
                        LeaseOrderStatus.PENDING_PAYMENT,
                        LeaseOrderStatus.PAID);
        if (leaseOrderMapper.selectCount(orderWrapper) > 0) {
            throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_ROOM_BUSY);
        }

        LambdaQueryWrapper<LeaseAgreement> agreementWrapper = new LambdaQueryWrapper<>();
        agreementWrapper.eq(LeaseAgreement::getRoomId, roomId)
                .in(LeaseAgreement::getStatus,
                        LeaseStatus.SIGNING,
                        LeaseStatus.SIGNED,
                        LeaseStatus.WITHDRAWING,
                        LeaseStatus.RENEWING);
        if (leaseAgreementMapper.selectCount(agreementWrapper) > 0) {
            throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_ROOM_BUSY);
        }
    }

    private LeaseOrder requireOwnedOrder(Long id, Long currentUserId) {
        LeaseOrder order = leaseOrderMapper.selectById(id);
        if (order == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
        if (!currentUserId.equals(order.getUserId())) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }
        return order;
    }

    private Map<Long, ApartmentInfo> apartmentMap(List<LeaseOrder> orders) {
        List<Long> apartmentIds = orders.stream()
                .map(LeaseOrder::getApartmentId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (apartmentIds.isEmpty()) {
            return Map.of();
        }
        return apartmentInfoMapper.selectBatchIds(apartmentIds).stream()
                .collect(Collectors.toMap(ApartmentInfo::getId, item -> item));
    }

    private Map<Long, RoomInfo> roomMap(List<LeaseOrder> orders) {
        List<Long> roomIds = orders.stream()
                .map(LeaseOrder::getRoomId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        return roomInfoMapper.selectBatchIds(roomIds).stream()
                .collect(Collectors.toMap(RoomInfo::getId, item -> item));
    }

    private Date calculateLeaseEndDate(Date leaseStartDate, Integer monthCount) {
        LocalDate start = toLocalDate(leaseStartDate);
        LocalDate end = start.plusMonths(monthCount == null ? 0L : monthCount.longValue());
        return Date.from(end.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private LocalDate toLocalDate(Date value) {
        return value.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private String resolveOrderName(UserInfo userInfo) {
        if (userInfo.getNickname() != null && !userInfo.getNickname().isBlank()) {
            return userInfo.getNickname();
        }
        return userInfo.getPhone();
    }

    private String generateOrderNo(Long currentUserId) {
        String timePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = ThreadLocalRandom.current().nextInt(1000, 10000);
        String suffix = currentUserId == null ? "0" : String.valueOf(currentUserId % 1000);
        return timePart + suffix + random;
    }

    private void publishCreated(LeaseOrder order) {
        if (leaseOrderEventPublisher == null || order == null) {
            return;
        }
        leaseOrderEventPublisher.publishCreated(
                order.getId(),
                order.getUserId(),
                order.getRoomId(),
                order.getPhone(),
                order.getStatus().name()
        );
        leaseOrderEventPublisher.publishTimeoutCheck(
                order.getId(),
                order.getUserId(),
                order.getRoomId(),
                order.getPhone(),
                LeaseOrderStatus.PENDING_PAYMENT.name(),
                LeaseOrderStatus.TIMEOUT_CANCELED.name()
        );
    }

    private void publishStatusChanged(LeaseOrder order, Long agreementId, LeaseOrderStatus targetStatus) {
        if (leaseOrderEventPublisher == null || order == null || targetStatus == null) {
            return;
        }
        leaseOrderEventPublisher.publishStatusChanged(
                order.getId(),
                agreementId,
                order.getUserId(),
                order.getRoomId(),
                order.getPhone(),
                order.getStatus() == null ? null : order.getStatus().name(),
                targetStatus.name()
        );
    }

    private RLock roomLock(Long roomId) {
        return redissonClient.getLock(ROOM_LOCK_PREFIX + roomId);
    }

    private RLock orderLock(Long orderId) {
        return redissonClient.getLock(ORDER_LOCK_PREFIX + orderId);
    }

    private <T> T executeWithLocks(List<RLock> locks, ThrowingSupplier<T> supplier) {
        List<RLock> acquired = acquireLocks(locks);
        try {
            return supplier.get();
        } finally {
            unlockAll(acquired);
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
                    throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_BUSY);
                }
                acquired.add(lock);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_BUSY);
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

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }
}
