package com.atguigu.lease.common.mq.outbox.service;

import com.atguigu.lease.common.mq.outbox.entity.OutboxMessage;

public interface OutboxService {

    /** 写入 outbox（通常与业务操作同事务） */
    OutboxMessage saveNew(OutboxMessage msg);

    /** 尝试投递一条 outbox 消息（会更新状态/重试次数） */
    void sendOne(Long outboxId);
}
