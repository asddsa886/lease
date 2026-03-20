package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.cache.HotDataCacheHelper;
import com.atguigu.lease.common.constant.RedisConstant.RedisConstant;
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.LeaseTerm;
import com.atguigu.lease.web.app.mapper.LeaseTermMapper;
import com.atguigu.lease.web.app.service.LeaseTermService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author liubo
 * @description 针对表【lease_term(租期)】的数据库操作Service实现
 * @createDate 2023-07-26 11:12:39
 */
@Service
public class LeaseTermServiceImpl extends ServiceImpl<LeaseTermMapper, LeaseTerm>
        implements LeaseTermService {

    @Autowired
    private HotDataCacheHelper hotDataCacheHelper;

    @Override
    public List<LeaseTerm> listByRoomId(Long roomId) {
        if (roomId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        String key = RedisConstant.APP_LEASE_TERM_LIST_BY_ROOM_KEY_PREFIX + roomId;

        return hotDataCacheHelper.getOrLoadWithLock(
                key,
                new TypeReference<List<LeaseTerm>>() {
                },
                () -> baseMapper.selectListByRoomId(roomId)
        );
    }
}
