package com.atguigu.lease.web.admin.mapper;

import com.atguigu.lease.web.admin.vo.metrics.RoomFunnelVo;
import com.atguigu.lease.web.admin.vo.metrics.RoomQualityScoreVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

public interface RoomMetricsMapper {

    IPage<RoomQualityScoreVo> pageQualityScore(IPage<RoomQualityScoreVo> page,
                                               @Param("apartmentId") Long apartmentId);

    RoomFunnelVo selectFunnel(@Param("startDate") LocalDate startDate,
                              @Param("endDate") LocalDate endDate,
                              @Param("apartmentId") Long apartmentId);
}
