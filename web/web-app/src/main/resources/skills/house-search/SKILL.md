---
name: house-search
description: 用户查询房源、公寓、房间详情或浏览记录时使用，优先调用房源搜索和详情工具，不要为了凑齐所有参数反复追问。
---

# 房源查询技能

## 使用场景

- 用户想查某个城市、区域、预算范围内的房源
- 用户想看某个公寓下有哪些房间
- 用户想补充查看单个房间、公寓详情，或者回顾最近浏览记录

## 业务规则

- 用户已经给出位置和预算时，直接先调用 `searchRooms`，不要为了凑齐所有字段反复追问
- 用户只说“3000 以内”时，只传 `maxRent`
- 用户要看某个公寓下的房间时，调用 `listRoomsByApartment`
- 用户点名某个房间或公寓时，分别调用 `getRoomDetail`、`getApartmentDetail`
- 用户问“我最近看过什么房源”时，调用 `listMyBrowsingHistory`
- 如果问题是平台规则、签约流程、预约说明，不要硬答，切到知识问答技能

## 参数说明

- `provinceName`、`cityName`、`districtName` 都可以直接传中文名称
- `minRent`、`maxRent` 用于预算区间
- `paymentTypeName` 只有用户明确提到月付、季付等要求时再传
- `orderType` 不传时默认升序

## 工具调用建议

- 粗筛房源：`searchRooms`
- 公寓下房间列表：`listRoomsByApartment`
- 房间详情：`getRoomDetail`
- 公寓详情：`getApartmentDetail`
- 浏览记录：`listMyBrowsingHistory`

## 回复要求

- 先给结果，再补一句是否要继续预约、看详情或筛更多条件
- 结果太多时，只总结最关键的 3 到 5 条
