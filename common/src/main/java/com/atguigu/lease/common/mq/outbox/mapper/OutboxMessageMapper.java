package com.atguigu.lease.common.mq.outbox.mapper;

import com.atguigu.lease.common.mq.outbox.entity.OutboxMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface OutboxMessageMapper extends BaseMapper<OutboxMessage> {

    /**
     * 拉取待发送/待重试的消息。
     * 说明：这里先用简单 SQL，生产可加“行级锁/分片/抢占”避免多实例重复发送。
     */
    List<OutboxMessage> listPending(@Param("now") Date now,
                                   @Param("limit") int limit);
}
