package com.atguigu.lease.web.app.vo.favorite;

import com.atguigu.lease.model.entity.RoomFavorite;
import com.atguigu.lease.web.app.vo.graph.GraphVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "APP favorite room item")
public class RoomFavoriteItemVo extends RoomFavorite {

    @Schema(description = "Favorite time")
    private Date favoriteTime;

    @Schema(description = "Room number")
    private String roomNumber;

    @Schema(description = "Rent")
    private BigDecimal rent;

    @Schema(description = "Room images")
    private List<GraphVo> roomGraphVoList;

    @Schema(description = "Apartment id")
    private Long apartmentId;

    @Schema(description = "Apartment name")
    private String apartmentName;

    @Schema(description = "Province name")
    private String provinceName;

    @Schema(description = "City name")
    private String cityName;

    @Schema(description = "District name")
    private String districtName;
}
