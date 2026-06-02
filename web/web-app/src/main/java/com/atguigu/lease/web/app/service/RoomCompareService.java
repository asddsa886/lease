package com.atguigu.lease.web.app.service;

import com.atguigu.lease.web.app.vo.compare.RoomCompareVo;

import java.util.List;

public interface RoomCompareService {

    RoomCompareVo compareRooms(List<Long> roomIds);
}
