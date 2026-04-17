package com.atguigu.lease.web.admin.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.mq.publisher.LeaseAgreementEventPublisher;
import com.atguigu.lease.common.mq.publisher.LeaseOrderEventPublisher;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.ApartmentInfo;
import com.atguigu.lease.model.entity.LeaseAgreement;
import com.atguigu.lease.model.entity.LeaseOrder;
import com.atguigu.lease.model.entity.LeaseTerm;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.model.entity.RoomInfo;
import com.atguigu.lease.model.enums.LeaseOrderStatus;
import com.atguigu.lease.model.enums.LeaseSourceType;
import com.atguigu.lease.model.enums.LeaseStatus;
import com.atguigu.lease.web.admin.mapper.ApartmentInfoMapper;
import com.atguigu.lease.web.admin.mapper.LeaseOrderMapper;
import com.atguigu.lease.web.admin.mapper.LeaseTermMapper;
import com.atguigu.lease.web.admin.mapper.PaymentTypeMapper;
import com.atguigu.lease.web.admin.mapper.RoomInfoMapper;
import com.atguigu.lease.web.admin.service.LeaseAgreementService;
import com.atguigu.lease.web.admin.service.LeaseOrderService;
import com.atguigu.lease.web.admin.vo.order.LeaseOrderQueryVo;
import com.atguigu.lease.web.admin.vo.order.LeaseOrderVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class LeaseOrderServiceImpl extends ServiceImpl<LeaseOrderMapper, LeaseOrder> implements LeaseOrderService {

    private static final String ROOM_LOCK_PREFIX = "lock:lease:order:room:";
    private static final String ORDER_LOCK_PREFIX = "lock:lease:order:record:";
    private static final long LOCK_WAIT_SECONDS = 2L;
    private static final long LOCK_LEASE_SECONDS = 8L;

    private final LeaseOrderMapper leaseOrderMapper;
    private final ApartmentInfoMapper apartmentInfoMapper;
    private final RoomInfoMapper roomInfoMapper;
    private final PaymentTypeMapper paymentTypeMapper;
    private final LeaseTermMapper leaseTermMapper;
    private final LeaseAgreementService leaseAgreementService;
    private final RedissonClient redissonClient;
    private final LeaseOrderEventPublisher leaseOrderEventPublisher;
    private final LeaseAgreementEventPublisher leaseAgreementEventPublisher;

    public LeaseOrderServiceImpl(LeaseOrderMapper leaseOrderMapper,
                                 ApartmentInfoMapper apartmentInfoMapper,
                                 RoomInfoMapper roomInfoMapper,
                                 PaymentTypeMapper paymentTypeMapper,
                                 LeaseTermMapper leaseTermMapper,
                                 LeaseAgreementService leaseAgreementService,
                                 RedissonClient redissonClient,
                                 ObjectProvider<LeaseOrderEventPublisher> leaseOrderEventPublisherProvider,
                                 ObjectProvider<LeaseAgreementEventPublisher> leaseAgreementEventPublisherProvider) {
        this.leaseOrderMapper = leaseOrderMapper;
        this.apartmentInfoMapper = apartmentInfoMapper;
        this.roomInfoMapper = roomInfoMapper;
        this.paymentTypeMapper = paymentTypeMapper;
        this.leaseTermMapper = leaseTermMapper;
        this.leaseAgreementService = leaseAgreementService;
        this.redissonClient = redissonClient;
        this.leaseOrderEventPublisher = leaseOrderEventPublisherProvider.getIfAvailable();
        this.leaseAgreementEventPublisher = leaseAgreementEventPublisherProvider.getIfAvailable();
    }

    @Override
    public IPage<LeaseOrderVo> pageOrder(Page<LeaseOrder> page, LeaseOrderQueryVo queryVo) {
        LambdaQueryWrapper<LeaseOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByDesc(LeaseOrder::getCreateTime, LeaseOrder::getId);
        if (queryVo != null) {
            queryWrapper.like(queryVo.getOrderNo() != null && !queryVo.getOrderNo().isBlank(),
                    LeaseOrder::getOrderNo, queryVo.getOrderNo());
            queryWrapper.like(queryVo.getPhone() != null && !queryVo.getPhone().isBlank(),
                    LeaseOrder::getPhone, queryVo.getPhone());
            queryWrapper.eq(queryVo.getStatus() != null, LeaseOrder::getStatus, queryVo.getStatus());
            queryWrapper.eq(queryVo.getApartmentId() != null, LeaseOrder::getApartmentId, queryVo.getApartmentId());
            queryWrapper.eq(queryVo.getRoomId() != null, LeaseOrder::getRoomId, queryVo.getRoomId());
            queryWrapper.eq(queryVo.getUserId() != null, LeaseOrder::getUserId, queryVo.getUserId());
        }

        Page<LeaseOrder> entityPage = leaseOrderMapper.selectPage(page, queryWrapper);
        Page<LeaseOrderVo> result = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        result.setRecords(buildVoList(entityPage.getRecords()));
        return result;
    }

    @Override
    public LeaseOrderVo getOrderById(Long id) {
        LeaseOrder order = leaseOrderMapper.selectById(id);
        if (order == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
        return buildVo(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmById(Long id) {
        if (id == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        executeWithLocks(List.of(orderLock(id)), () -> {
            LeaseOrder order = requirePaidOrder(id);
            executeWithLocks(List.of(roomLock(order.getRoomId())), () -> {
                LeaseOrder freshOrder = requirePaidOrder(id);
                ensureRoomHasNoActiveAgreement(freshOrder.getRoomId());

                LeaseAgreement agreement = new LeaseAgreement();
                agreement.setPhone(freshOrder.getPhone());
                agreement.setName(freshOrder.getName());
                agreement.setApartmentId(freshOrder.getApartmentId());
                agreement.setRoomId(freshOrder.getRoomId());
                agreement.setLeaseStartDate(freshOrder.getLeaseStartDate());
                agreement.setLeaseEndDate(freshOrder.getLeaseEndDate());
                agreement.setLeaseTermId(freshOrder.getLeaseTermId());
                agreement.setRent(freshOrder.getRent());
                agreement.setDeposit(freshOrder.getDeposit());
                agreement.setPaymentTypeId(freshOrder.getPaymentTypeId());
                agreement.setStatus(LeaseStatus.SIGNING);
                agreement.setSourceType(LeaseSourceType.NEW);
                agreement.setAdditionalInfo(freshOrder.getAdditionalInfo());

                boolean agreementSaved = leaseAgreementService.save(agreement);
                if (!agreementSaved || agreement.getId() == null) {
                    throw new LeaseException(ResultCodeEnum.SERVICE_ERROR);
                }

                LambdaUpdateWrapper<LeaseOrder> updateWrapper = new LambdaUpdateWrapper<>();
                updateWrapper.eq(LeaseOrder::getId, freshOrder.getId())
                        .eq(LeaseOrder::getStatus, LeaseOrderStatus.PAID)
                        .set(LeaseOrder::getStatus, LeaseOrderStatus.CONFIRMED)
                        .set(LeaseOrder::getAgreementId, agreement.getId());
                boolean updated = this.update(updateWrapper);
                if (!updated) {
                    throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_STATUS_ERROR);
                }

                publishOrderStatusChanged(freshOrder, agreement.getId(), LeaseOrderStatus.CONFIRMED);
                publishAgreementCreated(agreement);
                return null;
            });
            return null;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelById(Long id) {
        if (id == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        executeWithLocks(List.of(orderLock(id)), () -> {
            LeaseOrder order = requirePendingPaymentOrder(id);

            LambdaUpdateWrapper<LeaseOrder> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(LeaseOrder::getId, id)
                    .eq(LeaseOrder::getStatus, LeaseOrderStatus.PENDING_PAYMENT)
                    .set(LeaseOrder::getStatus, LeaseOrderStatus.CANCELED);
            boolean updated = this.update(updateWrapper);
            if (!updated) {
                throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_STATUS_ERROR);
            }

            publishOrderStatusChanged(order, order.getAgreementId(), LeaseOrderStatus.CANCELED);
            return null;
        });
    }

    private LeaseOrder requirePendingPaymentOrder(Long id) {
        LeaseOrder order = leaseOrderMapper.selectById(id);
        if (order == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
        if (order.getStatus() != LeaseOrderStatus.PENDING_PAYMENT) {
            throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_STATUS_ERROR);
        }
        return order;
    }

    private LeaseOrder requirePaidOrder(Long id) {
        LeaseOrder order = leaseOrderMapper.selectById(id);
        if (order == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
        if (order.getStatus() != LeaseOrderStatus.PAID) {
            throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_STATUS_ERROR);
        }
        return order;
    }

    private void ensureRoomHasNoActiveAgreement(Long roomId) {
        LambdaQueryWrapper<LeaseAgreement> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(LeaseAgreement::getRoomId, roomId)
                .in(LeaseAgreement::getStatus,
                        LeaseStatus.SIGNING,
                        LeaseStatus.SIGNED,
                        LeaseStatus.WITHDRAWING,
                        LeaseStatus.RENEWING);
        if (leaseAgreementService.count(queryWrapper) > 0) {
            throw new LeaseException(ResultCodeEnum.APP_LEASE_ORDER_ROOM_BUSY);
        }
    }

    private List<LeaseOrderVo> buildVoList(List<LeaseOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }

        Map<Long, ApartmentInfo> apartmentMap = apartmentInfoMapper.selectBatchIds(extractIds(orders, LeaseOrder::getApartmentId))
                .stream()
                .collect(Collectors.toMap(ApartmentInfo::getId, item -> item));
        Map<Long, RoomInfo> roomMap = roomInfoMapper.selectBatchIds(extractIds(orders, LeaseOrder::getRoomId))
                .stream()
                .collect(Collectors.toMap(RoomInfo::getId, item -> item));
        Map<Long, PaymentType> paymentTypeMap = paymentTypeMapper.selectBatchIds(extractIds(orders, LeaseOrder::getPaymentTypeId))
                .stream()
                .collect(Collectors.toMap(PaymentType::getId, item -> item));
        Map<Long, LeaseTerm> leaseTermMap = leaseTermMapper.selectBatchIds(extractIds(orders, LeaseOrder::getLeaseTermId))
                .stream()
                .collect(Collectors.toMap(LeaseTerm::getId, item -> item));
        Map<Long, LeaseAgreement> agreementMap = leaseAgreementService.listByIds(extractIds(orders, LeaseOrder::getAgreementId))
                .stream()
                .collect(Collectors.toMap(LeaseAgreement::getId, item -> item));

        return orders.stream()
                .map(order -> buildVo(order, apartmentMap, roomMap, paymentTypeMap, leaseTermMap, agreementMap))
                .toList();
    }

    private LeaseOrderVo buildVo(LeaseOrder order) {
        return buildVoList(List.of(order)).get(0);
    }

    private LeaseOrderVo buildVo(LeaseOrder order,
                                 Map<Long, ApartmentInfo> apartmentMap,
                                 Map<Long, RoomInfo> roomMap,
                                 Map<Long, PaymentType> paymentTypeMap,
                                 Map<Long, LeaseTerm> leaseTermMap,
                                 Map<Long, LeaseAgreement> agreementMap) {
        LeaseOrderVo vo = new LeaseOrderVo();
        BeanUtils.copyProperties(order, vo);
        vo.setApartmentInfo(apartmentMap.get(order.getApartmentId()));
        vo.setRoomInfo(roomMap.get(order.getRoomId()));
        vo.setPaymentType(paymentTypeMap.get(order.getPaymentTypeId()));
        vo.setLeaseTerm(leaseTermMap.get(order.getLeaseTermId()));
        if (order.getAgreementId() != null) {
            vo.setAgreementInfo(agreementMap.get(order.getAgreementId()));
        }
        return vo;
    }

    private <T> List<Long> extractIds(List<LeaseOrder> orders, java.util.function.Function<LeaseOrder, Long> getter) {
        return orders.stream()
                .map(getter)
                .filter(id -> id != null)
                .distinct()
                .toList();
    }

    private void publishOrderStatusChanged(LeaseOrder order, Long agreementId, LeaseOrderStatus afterStatus) {
        if (leaseOrderEventPublisher == null || order == null || afterStatus == null) {
            return;
        }
        leaseOrderEventPublisher.publishStatusChanged(
                order.getId(),
                agreementId,
                order.getUserId(),
                order.getRoomId(),
                order.getPhone(),
                order.getStatus() == null ? null : order.getStatus().name(),
                afterStatus.name()
        );
    }

    private void publishAgreementCreated(LeaseAgreement agreement) {
        if (leaseAgreementEventPublisher == null || agreement == null) {
            return;
        }
        leaseAgreementEventPublisher.publishUpsert(
                agreement.getId(),
                agreement.getPhone(),
                agreement.getStatus() == null ? null : agreement.getStatus().name(),
                true
        );
        leaseAgreementEventPublisher.publishTimeoutCheck(
                agreement.getId(),
                agreement.getPhone(),
                LeaseStatus.SIGNING.name(),
                LeaseStatus.CANCELED.name()
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
