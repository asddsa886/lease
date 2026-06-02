package com.atguigu.lease.web.admin.service;

import com.atguigu.lease.web.admin.vo.metrics.RoomFunnelVo;
import com.atguigu.lease.web.admin.vo.metrics.RoomQualityScoreVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDate;

public interface RoomMetricsService {

    IPage<RoomQualityScoreVo> pageQualityScore(Page<RoomQualityScoreVo> page, Long apartmentId);

    RoomFunnelVo getFunnel(LocalDate startDate, LocalDate endDate, Long apartmentId);
}
