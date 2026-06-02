package com.atguigu.lease.web.admin.vo.metrics;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Room conversion funnel")
public class RoomFunnelVo {

    private Long browseCount;

    private Long favoriteCount;

    private Long appointmentCount;

    private Long orderCount;

    private BigDecimal favoriteRate;

    private BigDecimal appointmentRate;

    private BigDecimal orderRate;
}
