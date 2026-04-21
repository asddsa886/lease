---
name: lease-order
description: 用户查看、创建或取消签约订单时使用，优先调用订单工具，缺少关键参数时只追问最少信息。
---

# 签约订单技能

## 使用场景

- 用户查看自己的签约订单
- 用户创建或取消待处理签约订单
- 用户围绕签约订单详情继续追问

## 业务规则

- 查看订单列表时，直接调用 `listMyLeaseOrders`
- 查看某个订单详情时，调用 `getLeaseOrderDetail`
- 创建订单前，必须拿到 `roomId`、`leaseTermId`、`paymentTypeId`、`leaseStartDate`
- 如果用户没给全参数，先结合上下文补齐；确实缺失时再最小化追问
- `leaseStartDate` 按中国时区本地日期理解，不要转成 UTC
- 在调用签约工具前，优先把日期整理成 `yyyy-MM-dd` 再传参，减少解析歧义
- 用户如果问“超时会怎么处理”“为什么订单关闭了”，这是平台规则说明，优先调用知识库工具，不要把规则编造成实时订单状态

## 参数说明

- `roomId` 是签约房间 ID
- `leaseTermId` 是租期 ID
- `paymentTypeId` 是支付方式 ID
- `leaseStartDate` 支持 `yyyy-MM-dd` 和自然语言日期
- `additionalInfo` 是可选备注

## 工具调用建议

- 查看订单列表：`listMyLeaseOrders`
- 查看订单详情：`getLeaseOrderDetail`
- 创建订单：`createLeaseOrder`
- 取消订单：`cancelLeaseOrder`
- 查询订单超时、支付规则、签约流程：`searchKnowledge`

## 回复要求

- 创建或取消后，明确告诉用户订单结果和下一步
- 不要虚构订单状态、金额、日期或编号
