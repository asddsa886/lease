package com.atguigu.lease.web.ops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties("lease.ops.scan")
public class OpsLogScanProperties {

    private boolean startupEnabled = true;

    private List<String> directories = new ArrayList<>(List.of("./logs"));

    private List<String> filePatterns = new ArrayList<>(List.of("*.log", "*.txt", "*.gz"));

    private long lookbackHours = 72;

    private int maxFiles = 20;

    private long maxBytesPerFile = 262144;

    private long slowSqlThresholdMs = 2000;
}
