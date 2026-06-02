package com.atguigu.lease.web.admin.vo.metrics;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Room quality score")
public class RoomQualityScoreVo {

    private Long roomId;

    private String roomNumber;

    private BigDecimal rent;

    private Long apartmentId;

    private String apartmentName;

    private Long graphCount;

    private Long attrCount;

    private Long facilityCount;

    private Long labelCount;

    private Long paymentTypeCount;

    private Long leaseTermCount;

    private Long browseCount;

    private Long favoriteCount;

    private Long appointmentCount;

    private Long orderCount;

    private Integer contentScore;

    private Integer conversionScore;

    private Integer qualityScore;
}
