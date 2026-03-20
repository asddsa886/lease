package com.atguigu.lease.web.app.vo.graph;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;


@Data
@Schema(description = "图片信息")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class GraphVo implements Serializable {

    /**
     * 仅用于批量查询时的归属字段（例如：按 apartmentId 分组）
     * <p>
     * 说明：不影响原有按 itemType+id 查询的接口，只是为了避免 N+1 时在 service 层做组装。
     */
    private Long apartmentId;


    @Schema(description = "图片名称")
    private String name;

    @Schema(description = "图片地址")
    private String url;

}
