package com.atguigu.lease.web.admin.vo.login;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "后台管理系统登录信息")
public class LoginVo {

    @Schema(description="用户名")
    @NotBlank(message = "username不能为空")
    private String username;

    @Schema(description="密码")
    @NotBlank(message = "password不能为空")
    private String password;

    @Schema(description="验证码key")
    @NotBlank(message = "captchaKey不能为空")
    private String captchaKey;

    @Schema(description="验证码code")
    @NotBlank(message = "captchaCode不能为空")
    private String captchaCode;
}
