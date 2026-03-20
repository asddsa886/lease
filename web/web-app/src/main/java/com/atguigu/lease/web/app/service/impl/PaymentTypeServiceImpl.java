package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.cache.HotDataCacheHelper;
import com.atguigu.lease.common.constant.RedisConstant.RedisConstant;
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.web.app.mapper.PaymentTypeMapper;
import com.atguigu.lease.web.app.service.PaymentTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author liubo
 * @description 针对表【payment_type(支付方式表)】的数据库操作Service实现
 * @createDate 2023-07-26 11:12:39
 */
@Service
public class PaymentTypeServiceImpl extends ServiceImpl<PaymentTypeMapper, PaymentType>
        implements PaymentTypeService {

    @Autowired
    private HotDataCacheHelper hotDataCacheHelper;

    @Override
    public List<PaymentType> listByRoomId(Long roomId) {
        if (roomId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        String key = RedisConstant.APP_PAYMENT_TYPE_LIST_BY_ROOM_KEY_PREFIX + roomId;

        // 热点 key 采用互斥锁模式，降低缓存失效瞬间的并发打穿
        return hotDataCacheHelper.getOrLoadWithLock(
                key,
                new TypeReference<List<PaymentType>>() {
                },
                () -> baseMapper.selectListByRoomId(roomId)
        );
    }
}
