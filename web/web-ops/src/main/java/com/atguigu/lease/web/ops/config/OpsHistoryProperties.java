package com.atguigu.lease.web.ops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("lease.ops.history")
public class OpsHistoryProperties {

    private String dir = "./data/ops-history";

    private int maxScans = 50;
}
