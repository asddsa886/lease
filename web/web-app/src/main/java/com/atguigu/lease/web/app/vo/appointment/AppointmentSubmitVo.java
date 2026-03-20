package com.atguigu.lease.web.app.vo.appointment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Date;

@Data
@Schema(description = "APP端预约看房提交参数")
public class AppointmentSubmitVo {

    @Schema(description = "预约id（为空表示新增；不为空表示更新）")
    @Min(value = 1, message = "id必须>=1")
    private Long id;

    @Schema(description = "用户姓名")
    @NotBlank(message = "name不能为空")
    private String name;

    @Schema(description = "用户手机号")
    @NotBlank(message = "phone不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "phone格式不合法")
    private String phone;

    @Schema(description = "公寓id")
    @NotNull(message = "apartmentId不能为空")
    @Min(value = 1, message = "apartmentId必须>=1")
    private Long apartmentId;

    @Schema(description = "预约时间")
    @NotNull(message = "appointmentTime不能为空")
    private Date appointmentTime;

    @Schema(description = "备注信息")
    private String additionalInfo;
}