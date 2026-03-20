package com.atguigu.lease.web.app.vo.user;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "APP端登录实体")
public class LoginVo {

    @Schema(description = "手机号码")
    @NotBlank(message = "phone不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "phone格式不合法")
    private String phone;

    @Schema(description = "短信验证码")
    @NotBlank(message = "code不能为空")
    private String code;
}
