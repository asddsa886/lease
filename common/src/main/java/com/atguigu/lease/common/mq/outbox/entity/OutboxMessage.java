package com.atguigu.lease.common.mq.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Outbox 本地消息表：保障“业务落库 + 发消息”最终一致性。
 * <p>
 * 推荐建表：outbox_message
 */
@Data
@TableName("outbox_message")
public class OutboxMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务名（如 leaseAgreement） */
    private String biz;

    /** 业务 key（如 agreementId / 订单号） */
    private String bizKey;

    /** 交换机 */
    private String exchange;

    /** 路由键 */
    private String routingKey;

    /** payload 对应的 Java 类型（用于回放时反序列化），如 com.xxx.LeaseAgreementEvent */
    private String payloadType;

    /** JSON 消息体 */
    private String payload;

    /**
     * 消息状态：
     * 0=NEW；1=SENT；2=ACKED；3=FAILED
     */
    private Integer status;

    /** 投递尝试次数 */
    private Integer tryCount;

    /** 下次重试时间（用于定时补偿任务筛选） */
    private Date nextRetryAt;

    /** 最后一次错误信息（截断） */
    private String lastError;

    /** 创建时间 */
    @TableField("create_time")
    private Date createTime;

    /** 更新时间 */
    @TableField("update_time")
    private Date updateTime;
}