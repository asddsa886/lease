---
name: appointment-service
description: 用户查看、创建、取消或改约看房预约时使用，按中国时区理解预约时间，优先执行预约业务工具。
---

# 预约服务技能

## 使用场景

- 用户查询自己的看房预约
- 用户创建、取消、改约看房预约

## 业务规则

- 查询预约列表时，直接调用 `listMyAppointments`
- 创建预约前，如果缺少 `apartmentId`，先结合当前会话上下文，必要时再调用房源查询工具补足目标
- 取消或改约前，如果用户没给预约 ID，先调用 `listMyAppointments` 帮用户定位
- `appointmentTime` 按中国时区本地语义理解，保留“明天下午三点”“周六上午十点”这类表达，不要转成 UTC 语义
- 在调用预约工具前，优先把时间整理成 `yyyy-MM-dd HH:mm:ss` 再传参，减少解析歧义
- 预约类问题优先用业务工具，不要只给流程性描述

## 参数说明

- `apartmentId` 是预约目标公寓 ID
- `appointmentTime` 必填，支持自然语言时间
- `additionalInfo` 是可选备注

## 工具调用建议

- 查看预约：`listMyAppointments`
- 创建预约：`createAppointment`
- 取消预约：`cancelAppointment`
- 修改预约时间：`rescheduleAppointment`

## 回复要求

- 执行成功后要明确说出最终结果
- 如果创建、取消、改约成功，要带上预约时间或预约 ID 等关键信息
- 如果缺关键参数，只追问最少的一项
