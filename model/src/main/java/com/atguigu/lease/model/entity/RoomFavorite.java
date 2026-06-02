package com.atguigu.lease.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "APP room favorite")
@TableName(value = "room_favorite")
@Data
public class RoomFavorite extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "User id")
    @TableField("user_id")
    private Long userId;

    @Schema(description = "Room id")
    @TableField("room_id")
    private Long roomId;
}
