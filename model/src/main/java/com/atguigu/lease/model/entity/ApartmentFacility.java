package com.atguigu.lease.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "公寓&配套关系")
@TableName(value = "apartment_facility")
@Data
@Builder
@AllArgsConstructor // <-- 添加：强制生成一个 public 的全参数构造函数
@NoArgsConstructor  // <-- 添加：JPA/MyBatis 也需要一个 public 的无参构造函数
public class ApartmentFacility extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "公寓id")
    @TableField(value = "apartment_id")
    private Long apartmentId;

    @Schema(description = "设施id")
    @TableField(value = "facility_id")
    private Long facilityId;


}