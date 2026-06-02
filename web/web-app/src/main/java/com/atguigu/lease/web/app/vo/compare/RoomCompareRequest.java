package com.atguigu.lease.web.app.vo.compare;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Room compare request")
public class RoomCompareRequest {

    @NotEmpty(message = "roomIds cannot be empty")
    @Schema(description = "Room ids to compare")
    private List<Long> roomIds;
}
