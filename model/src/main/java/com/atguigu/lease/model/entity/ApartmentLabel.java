package com.atguigu.lease.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "公寓标签关联表")
@TableName(value = "apartment_label")
@Data
@Builder
@AllArgsConstructor // <-- 添加：强制生成一个 public 的全参数构造函数
@NoArgsConstructor  // <-- 添加：JPA/MyBatis 也需要一个 public 的无参构造函数
public class ApartmentLabel extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "公寓id")
    @TableField(value = "apartment_id")
    private Long apartmentId;

    @Schema(description = "标签id")
    @TableField(value = "label_id")
    private Long labelId;

}