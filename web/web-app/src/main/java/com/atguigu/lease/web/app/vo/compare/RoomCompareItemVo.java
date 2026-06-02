package com.atguigu.lease.web.app.vo.compare;

import com.atguigu.lease.model.entity.FacilityInfo;
import com.atguigu.lease.model.entity.LabelInfo;
import com.atguigu.lease.model.entity.LeaseTerm;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.web.app.vo.attr.AttrValueVo;
import com.atguigu.lease.web.app.vo.graph.GraphVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "Room compare item")
public class RoomCompareItemVo {

    private Long roomId;

    private String roomNumber;

    private BigDecimal rent;

    private Boolean isFavorite;

    private Long apartmentId;

    private String apartmentName;

    private String provinceName;

    private String cityName;

    private String districtName;

    private String addressDetail;

    private List<GraphVo> graphVoList;

    private List<AttrValueVo> attrValueVoList;

    private List<FacilityInfo> facilityInfoList;

    private List<LabelInfo> labelInfoList;

    private List<PaymentType> paymentTypeList;

    private List<LeaseTerm> leaseTermList;
}
