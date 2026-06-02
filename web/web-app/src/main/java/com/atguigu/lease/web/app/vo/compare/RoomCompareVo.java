package com.atguigu.lease.web.app.vo.compare;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Room compare result")
public class RoomCompareVo {

    @Schema(description = "Compared rooms")
    private List<RoomCompareItemVo> items;

    @Schema(description = "Fields with differences across compared rooms")
    private List<String> differenceFields;
}
